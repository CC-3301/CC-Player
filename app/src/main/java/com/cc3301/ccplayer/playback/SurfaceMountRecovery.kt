package com.cc3301.ccplayer.playback

import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Surface 挂载恢复：挂载三态、挂载队列、挂载超时、按最新意图重挂与「挂载 → 尺寸 → 开输出」
 * 组合顺序的唯一所有者。
 *
 * 由界面在接线处创建并持有；Surface 会话只报生命周期，播放引擎只提供 libmpv 操作与
 * 把任务投递到视频输出线程的入口。三态与队列仍是可独立测试的内部接缝，
 * 完整恢复由本类自己的组合验证。
 */
internal class SurfaceMountRecovery(
    /** 视频输出线程的投递入口；引擎不在时抛异常，队列据此结算这次投递。 */
    private val execute: (() -> Unit) -> Unit,
    /** libmpv 挂载。 */
    private val mount: (Surface) -> Unit,
    /** libmpv 分离：先关输出再撤窗口，最后摘挂载。 */
    private val detachOutput: () -> Unit,
    /** libmpv 下发 Surface 尺寸。 */
    private val resize: (Int, Int) -> Unit,
    /** libmpv 开窗口并恢复视频输出。 */
    private val openOutput: () -> Unit,
    /** 超时计时；不注入时用本类自己的计时线程。 */
    schedule: ((delayMillis: Long, task: () -> Unit) -> Boolean)? = null,
    private val isValid: (Surface) -> Boolean = { it.isValid },
    private val onDiagnostic: (String) -> Unit = {},
) {

    private val state = SurfaceMountState()

    /** 计时线程按需创建：注入计时器时这里不占线程。 */
    private val timer = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "surface-mount-timeout").apply { isDaemon = true }
        }
    }

    private val scheduleTask: (delayMillis: Long, task: () -> Unit) -> Boolean =
        schedule ?: { delayMillis, task ->
            runCatching { timer.value.schedule({ task() }, delayMillis, TimeUnit.MILLISECONDS) }.isSuccess
        }

    /** 挂载队列：挂载、尺寸、开输出与分离都在视频输出线程上按投递顺序执行。 */
    private val queue = VideoOutputQueue(
        execute = execute,
        schedule = scheduleTask,
        onDiagnostic = onDiagnostic,
    )

    private var width = 0
    private var height = 0

    /**
     * 会话要求挂载 [surface]：旧 Surface 已销毁时先分离，再按队列挂载。
     * 队列空闲时当场挂；队列忙时排队，超过 [ATTACH_TIMEOUT_MILLIS] 仍未执行就回滚意图，
     * 队列恢复后按最新意图重挂一次。
     */
    fun attach(surface: Surface) {
        if (state.hasMount() && !state.isMounted(surface)) detach()
        if (!state.require(surface)) return
        queue.attach(
            stillCurrent = { state.isIntended(surface) },
            attach = {
                mount(surface)
                state.onMounted(surface)
                applySurfaceSize()
                openMountedSurface()
            },
            onSkipped = {
                if (state.onSkipped(surface)) {
                    onDiagnostic("挂载: 这次挂载没执行（Surface 已换、已释放或视频输出线程已关）")
                }
            },
            timeoutMillis = ATTACH_TIMEOUT_MILLIS,
            onTimeout = {
                if (state.onTimedOut(surface)) {
                    onDiagnostic("挂载: 视频输出线程 ${ATTACH_TIMEOUT_MILLIS}ms 内没有执行，放弃这次挂载")
                    scheduleRemount(surface)
                }
            },
        )
    }

    /** 记录尺寸；已挂载才下发，未挂载等挂载时补下，保证先挂载、再尺寸、再开输出。 */
    fun setSurfaceSize(width: Int, height: Int) {
        this.width = width
        this.height = height
        if (!state.hasMount()) return
        queue.postSurfaceSize(width, height) { w, h -> resize(w, h) }
    }

    /** Surface 真正销毁：清掉三态、作废排队中的开输出，再投递分离任务。 */
    fun detach() {
        if (!state.detach()) return
        queue.invalidate()
        if (!queue.postAlways(detachOutput)) {
            onDiagnostic("分离: 视频输出线程不再受理，分离任务没投递")
        }
    }

    /** 引擎断开：旧引擎的挂载不再有效，只清挂载事实，不动引擎。 */
    fun reset() {
        state.detach()
    }

    /** 界面销毁：清挂载事实并停掉计时线程。 */
    fun release() {
        state.detach()
        if (timer.isInitialized()) timer.value.shutdownNow()
    }

    /** 是否有在途或已挂载的 Surface。 */
    fun hasMount(): Boolean = state.hasMount()

    private fun applySurfaceSize() {
        if (width <= 0 || height <= 0) return
        queue.postSurfaceSize(width, height) { w, h -> resize(w, h) }
    }

    /** 挂载完成后开窗口并恢复视频输出：只对真正挂着的 Surface 开。 */
    private fun openMountedSurface() {
        val target = state.intended() ?: return
        if (state.isMounted(target)) openOutput()
    }

    /**
     * 排队挂载超时后的重挂路径：排到队列后面，等视频输出线程恢复时按会话最新意图真正挂上。
     * 重挂在 [REMOUNT_TIMEOUT_MILLIS] 内没有执行就放弃，等下一次会话重建恢复。
     */
    private fun scheduleRemount(surface: Surface) {
        val settled = AtomicBoolean(false)
        val armed = scheduleTask(REMOUNT_TIMEOUT_MILLIS) {
            if (settled.compareAndSet(false, true)) {
                onDiagnostic("重挂: 视频输出线程 ${REMOUNT_TIMEOUT_MILLIS}ms 内仍未执行，等下一次会话重建")
            }
        }
        if (!armed) onDiagnostic("重挂: 超时计时没有排上，重挂只等视频输出线程恢复")
        val accepted = queue.postAlways {
            if (!settled.compareAndSet(false, true)) return@postAlways
            if (!isValid(surface)) {
                onDiagnostic("重挂: Surface 已失效，跳过")
                return@postAlways
            }
            when (state.remount(surface) { mount(surface) }) {
                SurfaceMountState.RemountResult.Attached -> {
                    onDiagnostic("挂载: 视频输出线程恢复后重挂一次")
                    applySurfaceSize()
                    openMountedSurface()
                }
                SurfaceMountState.RemountResult.Superseded ->
                    onDiagnostic("重挂: 会话已换 Surface 或已分离，跳过")
                SurfaceMountState.RemountResult.Pending ->
                    onDiagnostic("重挂: 已有挂载在途，跳过")
            }
        }
        if (!accepted) onDiagnostic("挂载: 重挂任务没有投递上（视频输出线程已关）")
    }

    companion object {
        /** 排队挂载等视频输出线程的上限；超时按失败收尾，迟到的任务不再挂载。 */
        const val ATTACH_TIMEOUT_MILLIS = 2_000L

        /** 重挂等视频输出线程的上限；到点仍未执行就放弃，等下一次会话重建。 */
        const val REMOUNT_TIMEOUT_MILLIS = 3_000L
    }
}

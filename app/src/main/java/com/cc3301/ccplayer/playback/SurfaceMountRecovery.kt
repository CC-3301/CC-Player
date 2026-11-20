package com.cc3301.ccplayer.playback

import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Surface 挂载恢复：挂载三态、挂载队列、挂载超时、按最新意图重挂与「挂载 → 尺寸 → 开输出」
 * 组合顺序的唯一所有者。
 *
 * 调用方只报 Surface 与引擎生命周期，不逐方法维护挂载、尺寸与输出顺序；
 * 三态与队列仍是可独立测试的内部接缝，完整恢复由本类自己的组合验证。
 * 挂载、分离、尺寸与开输出都是 libmpv 操作，由播放引擎以回调提供，本类不碰原生调用。
 */
internal class SurfaceMountRecovery(
    /** 视频输出线程：挂载、尺寸、开输出与分离都在这条线程上按投递顺序执行。 */
    execute: (() -> Unit) -> Unit,
    /** 挂载超时与重挂超时的计时器；返回是否排上。 */
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Boolean,
    /** libmpv 挂载。 */
    private val mount: (Surface) -> Unit,
    /** libmpv 分离：先关输出再撤窗口，最后摘挂载。 */
    private val detachOutput: () -> Unit,
    /** libmpv 下发 Surface 尺寸。 */
    private val setProperty: (String, String) -> Unit,
    /** libmpv 开窗口并恢复视频输出。 */
    private val openOutput: () -> Unit,
    /** 挂载意图被放弃时引擎的清场：跳过与超时都要停输出，超时还要撤等帧。 */
    private val onMountGivenUp: (timedOut: Boolean) -> Unit = {},
    /** 挂载事实被清掉时引擎的清场：撤等帧、停输出。 */
    private val onMountCleared: () -> Unit = {},
    private val isValid: (Surface) -> Boolean = { it.isValid },
    private val onDiagnostic: (String) -> Unit = {},
) {

    private val state = SurfaceMountState()

    /** 挂载队列：挂载、尺寸、开输出与分离都在视频输出线程上按投递顺序执行。 */
    private val queue = VideoOutputQueue(
        execute = execute,
        schedule = schedule,
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
                startVideoOutput()
            },
            onSkipped = {
                if (state.onSkipped(surface)) {
                    onMountGivenUp(false)
                    onDiagnostic("挂载: 这次挂载没执行（Surface 已换、已释放或视频输出线程已关）")
                }
            },
            timeoutMillis = ATTACH_TIMEOUT_MILLIS,
            onTimeout = {
                if (state.onTimedOut(surface)) {
                    onMountGivenUp(true)
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
        queue.postSurfaceSize(width, height, setProperty)
    }

    /** 挂载之后开窗口并恢复视频输出：只对真正挂着的 Surface 开。 */
    fun startVideoOutput() {
        val target = state.intended() ?: return
        queue.postOutput {
            if (state.isMounted(target)) openOutput()
        }
    }

    /** Surface 真正销毁：清掉三态、作废排队中的开输出，再投递分离任务。 */
    fun detach() {
        if (!state.detach()) return
        onMountCleared()
        queue.invalidate()
        postDetach()
    }

    /** 释放前的收尾：作废排队中的开输出；还挂着就先投分离，之后的排空闸门排在它后面。 */
    fun release() {
        queue.invalidate()
        if (!state.detach()) return
        onMountCleared()
        postDetach()
    }

    /** 只清三态、不投递任务：释放收尾时用。 */
    fun clear() {
        state.detach()
    }

    /** 投一条参与代际作废的开输出任务（翻转、刷新）。 */
    fun postOutput(task: () -> Unit) {
        queue.postOutput(task)
    }

    /** 投一条必须执行的任务（释放排空闸门）；投递被拒时返回 false。 */
    fun postMustRun(task: () -> Unit): Boolean = queue.postAlways(task)

    /** 是否有在途或已挂载的 Surface。 */
    fun hasMount(): Boolean = state.hasMount()

    private fun applySurfaceSize() {
        if (width <= 0 || height <= 0) return
        queue.postSurfaceSize(width, height, setProperty)
    }

    private fun postDetach() {
        if (!queue.postAlways(detachOutput)) {
            onDiagnostic("分离: 视频输出线程不再受理，分离任务没投递")
        }
    }

    /**
     * 排队挂载超时后的重挂路径：排到队列后面，等视频输出线程恢复时按会话最新意图真正挂上。
     * 重挂在 [REMOUNT_TIMEOUT_MILLIS] 内没有执行就放弃，等下一次会话重建恢复。
     */
    private fun scheduleRemount(surface: Surface) {
        val settled = AtomicBoolean(false)
        val armed = schedule(REMOUNT_TIMEOUT_MILLIS) {
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
                    startVideoOutput()
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

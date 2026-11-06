# ADR-0019 libmpv 集成方式

状态：已接受

## 决策
- 基于 **mpv-android**（官方 Android 示例工程）改造集成 libmpv：预编译 so 或本地 NDK 构建，版本锁定并在 ADR 记录
- 渲染：MPVView（libmpv OpenGL 渲染到 Surface）嵌入 Compose 的 AndroidView
- 前台服务播放器：单一 PlaybackService 持有 mpv 实例与 MediaSession，Activity 仅绑定
- 字幕渲染：交给 mpv 内部 libass（不使用外层叠加），外挂字幕通过 sub-add 加载

## 版本与产物
- libmpv：`mpv v0.41.0`，libass 静态链入 `libmpv.so`
- 来源：mpv-android release tag `2026-09-17` 的 `app-default-arm64-v8a-release.apk`，取其中 `lib/arm64-v8a/` 全部 `.so` 存入 `app/src/main/jniLibs/arm64-v8a/`
- ABI：仅 arm64-v8a
- 源码：官方 `MPVLib.kt`（MIT）原样复制到 `app/src/main/java/is/xyz/mpv/`；`MpvSurfaceView` 按官方 `BaseMPVView.kt` 的 Surface 挂载顺序改写
- 渲染入口：`MPVLib.attachSurface(Surface)`，libmpv 的 render API 直接把画面画到视图的 Surface 上，承担官方 `MPVView` 的渲染职责，因此不需要单独移植 `MPVView`
- Surface 生命周期：以 SurfaceHolder 回调为唯一事实源。Android 不保证 `surfaceDestroyed` 与 `onStop` 的顺序，熄屏可能只有 `onPause` 而没有 Surface 回调，回前台也不保证触发 `surfaceCreated`；Surface 对象身份在 Android N 后可能被复用，不能作为会话 ID。
- Surface 会话：挂载、分离、尺寸变化与视频输出重建由单一 Surface 会话状态机持有，Activity 生命周期只向它转发事件；挂载与分离幂等，可重复、可乱序。拆挂只由 SurfaceHolder 回调触发：`surfaceDestroyed` 才分离，`surfaceCreated` 才挂载，Activity 的 `onStop`/`onStart` 不拆不挂。挂载顺序为 `attachSurface` → `android-surface-size` → `force-window=yes` → 恢复 VO；分离顺序为 VO 置空 → `force-window=no` → `detachSurface`，每次真实重挂都重建 VO。`surfaceCreated` 早于 `surfaceChanged`，尺寸尚未回报时先按已知步骤挂上，再由 `surfaceChanged` 补设尺寸。挂载与分离的属性设置与摘挂载走同一条视频输出线程、按投递顺序执行；分离任务不参与"作废已排队任务"的代际判断，保证关闭播放页时一定先分离再销毁。挂载前先等队列排空，超时则把挂载排到已投递的分离之后，绝不抢跑到尚未执行的分离之前；排队挂载执行前再校验 Surface 仍是当前这块、引擎未释放，校验不过就跳过并回滚挂载记录。
- 画面恢复：libmpv 没有 API 能证明画面已显示到屏幕，因此不靠任何证据推断。视频输出重建后由播放核心自行重解码并输出首个新帧；暂停态 Surface 重挂后发一次位置中性刷新，以刷新自己的 PLAYBACK_RESTART（帧上屏回执）作为撤黑判据，仅暂停态生效；播放态不 seek，只等播放位置前进。外层不抓帧探测、不校验表面代数；每次进入恢复（Surface 重挂、回前台播放态撤帧、回前台暂停态无画面）都进入同一条等新帧等待，并各允许一次刷新（暂停态发位置中性刷新，片尾暂停、已到结尾与切集仍在等目标帧时不刷新；播放态只等播放位置前进）。片尾与切集等目标帧期间不刷新。挂载或撤帧后 3 秒仍无新帧时只做一次 VO 翻转（置空后恢复），不 seek；翻转后仍无帧保持黑底并结束本次恢复，不无限等待。
- 许可文本：`third_party/mpv-android/LICENSE`

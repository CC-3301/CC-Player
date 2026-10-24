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
- Surface 会话：挂载、分离、尺寸变化与视频输出重建由单一 Surface 会话状态机持有，Activity 生命周期只向它转发事件；挂载与分离幂等，可重复、可乱序。挂载顺序为 `attachSurface` → `force-window=yes` → 恢复 VO；分离顺序为 VO 置空 → `force-window=no` → `detachSurface`，每次真实重挂都重建 VO。
- 出帧判定：libmpv 没有 API 能证明画面已显示到屏幕，不以「刷新指令成功」作为证据；只接受抓帧成功或播放位置前进。无证据时不撤旧画面。
- 许可文本：`third_party/mpv-android/LICENSE`

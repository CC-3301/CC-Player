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
- Surface 生命周期：退后台时分离并关闭视频输出，回前台重新挂载同一 Surface 并恢复输出；挂载与分离幂等，重复调用不重建渲染上下文
- 许可文本：`third_party/mpv-android/LICENSE`

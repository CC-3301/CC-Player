# ADR-0019 libmpv 集成方式

状态：已接受（2026-10-05，实现层，无需用户决策）

## 决策
- 基于 **mpv-android**（官方 Android 示例工程）改造集成 libmpv：预编译 so 或本地 NDK 构建，版本锁定并在 ADR 记录
- 渲染：MPVView（libmpv OpenGL 渲染到 Surface）嵌入 Compose 的 AndroidView
- 前台服务播放器：单一 PlaybackService 持有 mpv 实例与 MediaSession，Activity 仅绑定
- 字幕渲染：交给 mpv 内部 libass（不使用外层叠加），外挂字幕通过 sub-add 加载

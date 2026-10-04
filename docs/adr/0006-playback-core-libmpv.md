# ADR-0006 播放核心：libmpv

状态：已接受（2026-10-05，第 2 轮 Q1，经 MX Player / nPlayer 内核调查后确认）

## 决策
- 播放核心采用 **libmpv**（FFmpeg + 完整播放引擎 + libass）
- 硬解走 mpv 的 MediaCodec 后端，软解走 FFmpeg，二者由 mpv `hwdec` 机制统一管理

## 调查依据
- MX Player：FFmpeg 自研封装（内置解码器 + MediaCodec 硬解 + 可下载 FFmpeg custom codec）
- nPlayer：官方 GitHub 维护 nplayer-ffmpeg，核心为 FFmpeg 定制构建
- 主流播放器解码底座均为 FFmpeg；libmpv = FFmpeg 底座 + 现成引擎层（demux/libass/A-V 同步/seek）

## 备选被否
- ijkplayer：约 2020 年后停止维护
- Media3(ExoPlayer)+FFmpeg 扩展：ASS 渲染弱
- MX 式自研管线：工作量以年计

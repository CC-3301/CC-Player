# ADR-0003 支持的媒体格式

状态：已接受（2026-10-05，第 1 轮 Q4）

## 决策
- 视频：mp4、mkv、webm、avi、mov、flv、ts、wmv
- 音频：mp3、opus、wav、flac、m4a、aac、ogg
- 用户原始输入中的 "flav" 确认为 flac 笔误
- 清单为"尽力支持"基准，实际覆盖由播放内核能力决定（mkv/ASS 是硬指标）

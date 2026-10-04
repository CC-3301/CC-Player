# ADR-0021 缩略图缓存

状态：已接受（2026-10-05，实现层，无需用户决策）

## 决策
- 视频抽帧：本地文件用 MediaMetadataRetriever 取帧，存 files/thumbnails/ 磁盘缓存（Coil 管理加载），LRU 上限
- 音频封面：从 ID3 APIC / flac PICTURE / m4a covr 提取，同目录缓存
- 网络文件不抽帧（避免拉流开销），显示类型占位图

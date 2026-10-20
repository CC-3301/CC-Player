# ADR-0021 缩略图缓存

状态：已接受

## 决策
- 视频抽帧：本地文件用 MediaMetadataRetriever 取帧，存 files/thumbnails/ 磁盘缓存（Coil 管理加载），LRU 上限。
- 音频封面：从 ID3 APIC / flac PICTURE / m4a covr 提取，同目录缓存。
- 网络文件同样抽帧：SMB/WebDAV 按字节范围读取文件开头，交给 MediaMetadataRetriever；不整文件下载。
- 网络缩略图缓存键为“媒体位置 + 修改时间”；修改时间变化视为新文件。
- 网络缩略图按需提取，失败或超时不写入缓存，界面保持占位图。
- 同一目录内并发提取有上限，避免一次进入目录发起过多网络请求。

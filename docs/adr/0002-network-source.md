# ADR-0002 网络源接入：应用层文件抽象

状态：已接受（2026-10-05，第 1 轮 Q3）

## 决策
- SMB/WebDAV 由应用层实现，不依赖播放内核的网络能力（libmpv 的 smb 支持陈旧且不可靠）
- SMB 客户端：SMBJ（SMB2/3）；**禁用 SMB1**（安全且 Windows 已默认关闭）
- WebDAV：基于 OkHttp 自实现（PROPFIND/GET/Range）或 sardine-android
- 向下抽象统一 `MediaFile` 接口（本地/远程同一套列表、排序、播放、字幕查找逻辑）
- 播放远程媒体：按需拉流（HTTP Range / SMB 顺序读），必要时走本地缓存

## 备选被否
- 依赖内核网络能力：SMB1 时代实现，WebDAV 不稳定

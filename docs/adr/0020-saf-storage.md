# ADR-0020 本地媒体目录来源（SAF）

状态：已接受（2026-10-05，实现层，无需用户决策；2026-10-06 修订：取消媒体索引）

## 决策
- 本地媒体采用 SAF：首次启动引导选择媒体根目录，用 ACTION_OPEN_DOCUMENT_TREE + takePersistableUriPermission 持久授权，支持多个根目录
- 不申请 READ_EXTERNAL_STORAGE / READ_MEDIA_* 权限（MediaStore 方案被否，见第 2 轮 Q12）
- 目录列举每次实时查询文档提供者（DocumentsContract），**不建本地媒体索引**：媒体信息不落 Room、不做增量刷新
- 子目录在列表中作为可进入的条目展示，不递归展开

## 理由

媒体索引需与真实目录持续同步，同步成本与不一致风险高于实时查询的耗时。

## 后果

列表打开耗时取决于文档提供者；目录内文件极多时需自行承担等待。

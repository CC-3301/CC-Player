# ADR-0020 本地媒体目录来源（SAF）

状态：已接受

## 决策
- 本地媒体采用 SAF：从首页“本地”入口选择文件夹，用 ACTION_OPEN_DOCUMENT_TREE + takePersistableUriPermission 持久授权，支持多个根目录
- 每个本地根目录是一个本地连接源，显示在首页“本地”源列表中
- 不申请 READ_EXTERNAL_STORAGE / READ_MEDIA_* 权限（MediaStore 方案被否）
- 目录列举每次实时查询文档提供者（DocumentsContract），不建本地媒体索引：媒体信息不落 Room、不做增量刷新
- 子目录在列表中作为可进入的条目展示，不递归展开

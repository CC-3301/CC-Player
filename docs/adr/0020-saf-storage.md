# ADR-0020 本地媒体目录来源（SAF）

状态：已接受（2026-10-05，实现层，无需用户决策）

## 决策
- 本地媒体采用 SAF：首次启动引导选择媒体根目录，用 ACTION_OPEN_DOCUMENT_TREE + takePersistableUriPermission 持久授权，支持多个根目录
- 不申请 READ_EXTERNAL_STORAGE / READ_MEDIA_* 权限（MediaStore 方案被否，见第 2 轮 Q12）
- 目录树扫描入 Room，增量刷新（按修改时间/文件树 diff）

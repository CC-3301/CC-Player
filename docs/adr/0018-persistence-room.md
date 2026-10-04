# ADR-0018 数据持久化

状态：已接受（2026-10-05，实现层，无需用户决策）

## 决策
- 使用 **Room (SQLite)** 持久化：每文件进度记录（含路径唯一键、位置、时长、修改时间）、应用设置、网络源书签（SMB/WebDAV 服务器凭据，EncryptedSharedPreferences 存密码）
- 设置用 DataStore 或 Room 之一统一管理（实现时定，倾向 DataStore 存设置、Room 存进度）
- 排序/网格视图偏好、方向模式、解码模式等均为持久设置

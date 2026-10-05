# ADR-0018 数据持久化

状态：已接受（2026-10-05，实现层，无需用户决策；2026-10-06 修订：网络源书签改用 DataStore）

## 决策
- 使用 **Room (SQLite)** 持久化：每文件进度记录（含路径唯一键、位置、时长、修改时间）
- 网络源书签（SMB/WebDAV 的服务器地址、用户名等非密码字段）用 DataStore 存，与协议无关；密码入 EncryptedSharedPreferences
- 设置用 DataStore 或 Room 之一统一管理（实现时定，倾向 DataStore 存设置、Room 存进度）
- 排序/网格视图偏好、方向模式、解码模式等均为持久设置

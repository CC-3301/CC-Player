# ADR-0018 数据持久化

状态：已接受

## 决策
- 使用 **Room (SQLite)** 持久化：每文件进度记录（含路径唯一键、位置、时长、修改时间）
- 网络源书签保存名称、服务器地址、路径或起始目录、用户名等非密码字段，用 DataStore 存，与协议无关；密码入 EncryptedSharedPreferences
- 目录位置记忆按连接源与相对目录为键存 DataStore（ADR-0023）
- 设置用 DataStore 或 Room 之一统一管理（实现时定，倾向 DataStore 存设置、Room 存进度）
- 排序/网格视图偏好、方向模式、解码模式等均为持久设置

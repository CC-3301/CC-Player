# ADR-0001 技术栈与 SDK 区间

状态：已接受（2026-10-05，第 1 轮 Q2）

## 决策
- 语言：Kotlin
- UI：Jetpack Compose（列表/网格用 LazyGrid，播放器浮层自绘）
- minSdk 26（覆盖 ~99% 设备），targetSdk 35（满足 edge-to-edge 全面屏要求与商店合规）

## 后果
- Compose 加快浮层/控制条开发；列表与网格切换共用同一数据源
- 放弃 Android 8.0 以下设备

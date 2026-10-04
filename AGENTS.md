# CC Player — 仓库级 Agent 说明

安卓视频/音频播放器。核心决策见 `docs/adr/`（0001~0021），术语见 `GLOSSARY.md`。

## 代理技能（Agent skills）

### 工单（Issue tracker）

Issues 存于 GitHub 仓库 CC-3301/CC-Player，统一用 gh CLI 操作。见 `docs/agents/issue-tracker.md`

### 分诊标签（Triage labels）

沿用五个默认角色标签（needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix）。见 `docs/agents/triage-labels.md`

### 领域文档（Domain docs）

单上下文布局：根级 GLOSSARY.md + docs/adr/。见 `docs/agents/domain.md`

## 文档与注释的写法

- **只写事实与规则**，不写修饰：
- **代码注释不写票号**；
- 不写事故叙述与事故措辞：「已真实发生」「实测」「真机」「有人踩过」「维护者拍板」、「评审 r2-b1 P2-1」、批次与轮次（r9 / b3）；
- 不写口号（「宁可重复，不要指望继承」）与自我辩解（「这是有意的：…」）；
- 不写口语与语气词（「别指望」「人话」）；
- 理由只留一句，不使用「很重要 / 务必」等强调词。

## 要维护者拍板时

- **假定维护者是技术小白**：用大白话，不出现内部术语 / 函数名 / 文件路径；非要出现，先一句说清它是干什么的。
- **有明确选项要维护者选时，用 `ask_user_question` 弹窗。**
- 每个选项写清**做什么、好处、坏处**（填进选项的 description，不是正文）。
- 不要求维护者先去读别处才能决定；该说明的当场说明。

## 需求边界与决策原则

- **严格按约定实现**：票面、规格及需求未明确的功能，不得自行设计或扩展。
- **不确定就确认**：存在歧义或无法判断时，不得猜测，先向维护者确认。

## 最终产物（APK）

- 一批出一次，只在最终收尾时出，出release包而不是debug包。
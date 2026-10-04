# CC Player — 仓库级 Agent 说明

安卓视频/音频播放器。核心决策见 `docs/adr/`（0001~0021），术语见 `GLOSSARY.md`。

## Agent skills

### Issue tracker

Issues 存于 GitHub 仓库 CC-3301/CC-Player，统一用 gh CLI 操作。见 `docs/agents/issue-tracker.md`

### Triage labels

沿用五个默认角色标签（needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix）。见 `docs/agents/triage-labels.md`

### Domain docs

单上下文布局：根级 GLOSSARY.md + docs/adr/。见 `docs/agents/domain.md`

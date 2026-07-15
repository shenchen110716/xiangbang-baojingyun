# AI 任务交接目录

每个任务使用一个独立 Markdown 文件，文件名采用稳定任务编号，例如：

```text
docs/ai-handoffs/recharge-accounts-phase-a.md
docs/ai-handoffs/role-timeliness-v42.md
```

代理开始或恢复工作前，应阅读所有状态不是 `merged`、`released`、`cancelled` 的文件。不要让多个任务共同编辑一个“总表”，避免交接文件本身产生冲突。

创建新任务时复制 `TEMPLATE.md`，并在开始代码实现前填写负责人、基线、范围、公共文件和迁移所有权。

# 任务跟踪：本地Markdown

本项目使用本地任务文件，不发布GitLab或其他远端工单。

- 工作目录：`.scratch/cms-ai-workbench/`；每票位于`issues/<NN>-ticket.md`，按阻塞关系编号。
- 任务索引和需求基线位于同一目录；每票独立维护验收标准、Status及Blocked by。
- 仅当阻塞任务完成且已有开发启动授权时领取；ready-for-agent不代表已启动。
- 完成后记录验证命令、结果、关键变更和遗留问题。不得仅因生成代码或文档而关闭任务。
- outputs中的打包副本只供查看与交付，更新以此本地任务目录为准。

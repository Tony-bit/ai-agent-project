---
name: openspec-archive-change
description: 将已完成的变更归档到实验工作流中。当实现完成、用户想要最终确认并归档变更时使用。
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.1"
---

将已完成的变更归档到实验工作流中。

**输入**：可选地指定 change 名称。如果未指定，则检查能否从当前对话上下文中推断。如果描述模糊或存在歧义，**必须**提示用户从可用变更中选择。

**步骤**

1. **如果未提供 change 名称，提示用户选择**

   运行 `openspec list --json` 获取可用变更。使用 **AskUserQuestion tool** 让用户选择。

   只展示激活中的变更（尚未归档的）。
   如果可用，同时展示每个变更使用的 schema。

   **重要**：不要自行猜测或自动选择一个 change。始终让用户选择。

2. **检查 artifact 完成状态**

   运行 `openspec status --change "<name>" --json` 检查 artifact 完成情况。

   解析 JSON 以理解：
   - `schemaName`：当前使用的工作流
   - `artifacts`：各 artifact 及其状态列表（`done` 或其他）

   **如果存在尚未 `done` 的 artifact：**
   - 展示警告，列出未完成的 artifacts
   - 使用 **AskUserQuestion tool** 确认用户是否仍要继续归档
   - 用户确认后继续执行

3. **检查任务完成状态**

   读取 tasks 文件（通常是 `tasks.md`），检查是否存在未完成的任务。

   统计以 `- [ ]` 标记（未完成）和 `- [x]` 标记（已完成）的任务数量。

   **如果发现未完成的任务：**
   - 展示警告，列出未完成任务数量
   - 使用 **AskUserQuestion tool** 确认用户是否仍要继续归档
   - 用户确认后继续执行

   **如果没有 tasks 文件：** 跳过任务相关警告，直接继续。

4. **评估 delta spec 同步状态**

   检查 `openspec/changes/<name>/specs/` 目录下是否存在 delta specs。如果不存在，跳过同步提示。

   **如果存在 delta specs：**
   - 将每个 delta spec 与对应的主 spec（位于 `openspec/specs/<capability>/spec.md`）进行对比
   - 确定将要应用的变更（新增、修改、删除、重命名）
   - 在提示用户之前，先展示一个合并后的变更摘要

   **提示选项：**
   - 如果需要同步变更：`"立即同步（推荐）"`、`"不同步直接归档"`
   - 如果已经同步过：`"立即归档"`、`"重新同步"`、`"取消"`

   如果用户选择同步，使用 Task tool（subagent_type: "general-purpose"，prompt: "使用 Skill tool 调用 openspec-sync-specs 处理 change '<name>'。Delta spec 分析：<包含分析后的 delta spec 摘要>"）。无论用户最终选择什么，都继续执行归档操作。

5. **执行归档**

   如果归档目录不存在，先创建：
   ```bash
   mkdir -p openspec/changes/archive
   ```

   使用当前日期生成归档目标名称：`YYYY-MM-DD-<change-name>`。

   **检查目标是否已存在：**
   - 如果已存在：报错退出，提示用户重命名现有归档或使用其他日期
   - 如果不存在：将 change 目录移动到归档目录

   ```bash
   mv openspec/changes/<name> openspec/changes/archive/YYYY-MM-DD-<name>
   ```

6. **展示归档摘要**

   展示归档完成摘要，内容包括：
   - 变更名称
   - 使用的 schema
   - 归档路径
   - Spec 是否已同步（如适用）
   - 警告说明（如存在未完成的 artifacts 或任务）

**归档成功时的输出示例**

```
## 归档完成

**Change：** <change-name>
**Schema：** <schema-name>
**归档到：** openspec/changes/archive/YYYY-MM-DD-<name>/
**Specs：** ✓ 已同步到主 specs（或"无 delta specs"或"跳过同步"）

所有 artifacts 完成。所有任务完成。
```

**约束**
- 如果未提供 change 名称，始终提示用户选择
- 使用 artifact 图（`openspec status --json`）检查完成状态
- 遇到警告不要阻止归档，只做提示并确认
- 移动到归档目录时保留 `.openspec.yaml`（它随目录一起移动）
- 清晰地展示归档过程摘要
- 如果用户请求同步，使用 openspec-sync-specs 方式（由 agent 驱动）
- 如果存在 delta specs，始终执行同步评估，并在提示用户前先展示合并摘要

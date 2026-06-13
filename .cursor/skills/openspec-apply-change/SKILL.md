---
name: openspec-apply-change
description: 从 OpenSpec 变更中实现任务。当用户想开始实现、继续实现，或逐项推进任务时使用。
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.1"
---

从 OpenSpec 变更中实现任务。

**输入**：可选地指定 change 名称。如果未指定，则检查能否从当前对话上下文中推断。如果描述模糊或存在歧义，**必须**提示用户从可用变更中选择。

**步骤**

1. **设置独立 worktree 环境**

   **重要 — 第一步必须执行，任何代码修改前先做隔离。**

   读取 `isolated-worktree-setup` skill 了解隔离协议，然后执行：
   - 如果已在独立的 worktree 中（且有独立 Maven 仓库）：直接继续
   - 否则：执行 skill 创建隔离环境

   隔离的目标：
   - 每个 agent 在独立的 git worktree 中工作（代码隔离）
   - 每个 worktree 有独立的 Maven 本地仓库（编译隔离）
   - 多个 agent 可并发编译而不会产生文件锁冲突

2. **选择 change**

   如果已提供名称，则直接使用。否则：
   - 如果用户在上下文中提到过某个 change，则尝试推断
   - 如果当前只有一个激活中的 change，则自动选择
   - 如果存在歧义，运行 `openspec list --json` 获取可用 change，并使用 **AskUserQuestion tool** 让用户选择

   始终要说明：`Using change: <name>`，并告知如何覆盖，例如 `/opsx:apply <other>`。

3. **检查状态以理解 schema**
   ```bash
   openspec status --change "<name>" --json
   ```
   解析 JSON 以理解：
   - `schemaName`：当前使用的工作流（例如 `spec-driven`）
   - 哪个 artifact 包含任务（对于 `spec-driven` 通常是 `tasks`，其他 schema 以状态结果为准）

4. **获取 apply 指令**

   ```bash
   openspec instructions apply --change "<name>" --json
   ```

   该命令会返回：
   - `contextFiles`：artifact ID 到具体文件路径数组的映射（随 schema 不同而变化，可能是 proposal/specs/design/tasks 或 spec/tests/implementation/docs）
   - 进度信息（total、complete、remaining）
   - 带状态的任务列表
   - 基于当前状态生成的动态指令

   **状态处理：**
   - 如果 `state: "blocked"`（缺少 artifacts）：提示用户，并建议使用 `openspec-continue-change`
   - 如果 `state: "all_done"`：表示祝贺，并建议归档
   - 否则：继续进入实现流程

5. **读取上下文文件**

   读取 `contextFiles` 中列出的每一个文件路径。
   文件取决于当前使用的 schema：
   - **spec-driven**：proposal、specs、design、tasks
   - 其他 schema：以 CLI 输出中的 `contextFiles` 为准

   如果实现需要配套测试文档，也要读取 `test-doc-template`，并将生成的测试文档放到 `docs/superpowers/test/` 下。
   该测试文档应作为当前 change 的验证 / 提测 artifacts 的一部分看待。

6. **展示当前进度**

   展示内容包括：
   - 当前使用的 schema
   - 进度，例如：`N/M tasks complete`
   - 剩余任务概览
   - CLI 返回的动态指令

7. **实现任务（循环直到完成或阻塞）**

   对每个 pending 任务：
   - 说明当前正在处理哪个任务
   - 完成所需代码修改
   - 保持改动最小且聚焦
   - 如果该 change 需要测试文档 / 测试用例设计 / 验收验证材料，则使用 `test-doc-template`
   - 生成测试文档后，保持它与当前 change 范围一致，并将其作为验证 / 提测输出的一部分
   - 在 tasks 文件中将任务标记为完成：`- [ ]` → `- [x]`
   - 然后继续下一个任务

   **以下情况需要暂停：**
   - 任务不清晰 → 先向用户确认
   - 实现过程中暴露出设计问题 → 建议更新 artifacts
   - 遇到错误或阻塞 → 说明问题并等待指导
   - 用户中断

8. **完成或暂停时展示状态**

   展示内容包括：
   - 本次会话完成的任务
   - 总体进度，例如：`N/M tasks complete`
   - 如果已全部完成：建议归档
   - 如果已暂停：说明原因并等待进一步指导

**实现过程中的输出示例**

```
## 正在实现：<change-name>（schema: <schema-name>）

正在处理任务 3/7：<task description>
[...implementation happening...]
✓ 任务完成

正在处理任务 4/7：<task description>
[...implementation happening...]
✓ 任务完成
```

**完成时的输出示例**

```
## 实现完成

**Change：** <change-name>
**Schema：** <schema-name>
**进度：** 7/7 个任务已完成 ✓

### 本次会话完成
- [x] Task 1
- [x] Task 2
...

所有任务都已完成！可以归档这个 change 了。
```

**暂停时的输出示例（遇到问题）**

```
## 实现已暂停

**Change：** <change-name>
**Schema：** <schema-name>
**进度：** 4/7 个任务已完成

### 遇到的问题
<description of the issue>

**可选项：**
1. <option 1>
2. <option 2>
3. 其他处理方式

你希望怎么做？
```

**约束**
- 持续推进任务，直到全部完成或遇到阻塞
- 开始前始终读取上下文文件（来自 apply instructions 输出）
- 如果任务有歧义，先暂停并确认，再实现
- 如果实现暴露出问题，先暂停并建议更新 artifacts
- 代码改动应保持最小、范围明确，围绕当前任务展开
- 每完成一个任务后，立即更新对应的任务勾选状态
- 遇到错误、阻塞或需求不清晰时暂停，不要猜测
- 使用 CLI 输出中的 `contextFiles`，不要自行假设具体文件名

**与流式工作流的集成**

此 skill 支持“围绕 change 执行动作”的模型：

- **可在任意时机调用**：例如在所有 artifacts 尚未完成前（只要任务已存在）、部分实现后，或与其他动作交错进行
- **允许更新 artifacts**：如果实现过程中发现设计问题，可以建议更新 artifacts，而不是被固定在某个阶段中

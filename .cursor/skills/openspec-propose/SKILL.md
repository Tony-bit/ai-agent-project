---
name: openspec-propose
description: 一步生成新的变更提案及其全部 artifacts。当用户想快速描述要构建的内容，并直接得到可进入实现阶段的 proposal、design、specs、tasks 时使用。
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.3.1"
---

提出一个新的 change，一次性创建该 change 并生成所需 artifacts。

我将创建以下 artifacts：
- `proposal.md`（做什么、为什么做）
- `design.md`（怎么做）
- `tasks.md`（实现步骤）

准备开始实现时，运行 `/opsx:apply`。

---

**输入**：用户请求中应包含一个 change 名称（kebab-case），或者至少描述清楚想构建什么内容。

**步骤**

1. **如果输入不明确，先询问用户要构建什么**

   使用 **AskUserQuestion tool**（开放式提问，不提供预设选项）询问：
   > "你想处理哪个 change？请描述你想构建或修复的内容。"

   根据用户描述，推导出一个 kebab-case 名称（例如：`add user authentication` → `add-user-auth`）。

   **重要**：在未理解用户想构建什么之前，不要继续执行。

2. **创建 change 目录**
   ```bash
   openspec new change "<name>"
   ```
   这会在 `openspec/changes/<name>/` 下创建一个带 `.openspec.yaml` 的脚手架目录。

3. **获取 artifact 构建顺序**
   ```bash
   openspec status --change "<name>" --json
   ```
   解析 JSON 以获取：
   - `applyRequires`：实现前必须完成的 artifact ID 数组（例如 `[`tasks`]`）
   - `artifacts`：所有 artifacts 及其状态和依赖关系

4. **按顺序创建 artifacts，直到达到可 apply 状态**

   使用 **TodoWrite tool** 跟踪 artifact 创建进度。

   按依赖顺序循环处理 artifacts（优先处理没有未完成依赖的 artifact）：

   a. **对于每个状态为 `ready` 的 artifact（即依赖已满足）**：
      - 获取生成说明：
        ```bash
        openspec instructions <artifact-id> --change "<name>" --json
        ```
      - 指令 JSON 会包含：
        - `context`：项目背景（仅供你参考的约束，不要写入输出文件）
        - `rules`：artifact 专属规则（仅供你参考的约束，不要写入输出文件）
        - `template`：输出文件应采用的结构
        - `instruction`：该 artifact 类型对应的 schema 指引
        - `outputPath`：输出路径
        - `dependencies`：需要先读取的已完成 dependency artifacts
      - 先读取已完成的 dependency 文件作为上下文
      - 按 `template` 结构创建 artifact 文件
      - 将 `context` 和 `rules` 作为写作约束使用，但**不要**把它们原样拷贝进文件
      - 简要展示进度，例如：`Created <artifact-id>`

   b. **持续执行，直到所有 `applyRequires` artifacts 都完成**
      - 每创建完一个 artifact，都重新运行 `openspec status --change "<name>" --json`
      - 检查 `applyRequires` 中每个 artifact ID 是否都已在 artifacts 数组中呈现 `status: "done"`
      - 当所有 `applyRequires` artifacts 都完成后停止

   c. **如果某个 artifact 需要用户补充输入**（上下文不清晰）：
      - 使用 **AskUserQuestion tool** 进行澄清
      - 然后继续创建流程

5. **展示最终状态**
   ```bash
   openspec status --change "<name>"
   ```

**输出**

完成所有 artifacts 后，给出摘要：
- change 名称和所在位置
- 已创建 artifacts 列表及简要说明
- 当前可执行状态，例如：`All artifacts created! Ready for implementation.`
- 提示：`Run /opsx:apply or ask me to implement to start working on the tasks.`

**设计文档存放约束**
- 本项目所有使用 superpowers / opsx / story 流程产生的设计文档、实施计划、方案文档，默认统一存放在 `docs/superpowers/plans/`
- 除非用户明确指定其他路径，否则不得将新的设计文档写入 `docs/trading-agent/` 或其他业务目录
- `docs/trading-agent/` 用于存放业务资料、专题分析、架构说明、研究记录，不作为默认设计文档目录

**重要 — 开始实现前必须先做隔离**

当用户说“实现”、“开始执行”、“开始工作”时，必须先确保隔离：
1. 读取 `isolated-worktree-setup` skill
2. 在任何代码修改前执行隔离协议
3. 执行实现的 agent 必须在独立 worktree 内工作

这样可以防止多个 agent 并发时的编译冲突。

**Artifact 创建指南**

- 对每种 artifact，都遵循 `openspec instructions` 返回结果中的 `instruction` 字段
- artifact 的具体内容由 schema 定义，严格按 schema 要求生成
- 创建新 artifact 前，先读取 dependency artifacts 作为上下文
- 使用 `template` 作为输出文件结构，并补全各部分内容
- **重要**：`context` 和 `rules` 是给你的约束，不是要写进文件的内容
  - 不要把 `<context>`、`<rules>`、`<project_context>` 这类块原样拷贝到 artifact 中
  - 它们只用于指导你的写作，不应出现在最终输出里

**约束**
- 创建实现所需的**全部** artifacts（由 schema 的 `apply.requires` 定义）
- 创建每个新 artifact 前，始终先读取它依赖的 artifacts
- 如果上下文关键缺失，就询问用户；但在可接受范围内，优先做出合理决策以保持推进节奏
- 如果同名 change 已存在，先询问用户是继续已有 change 还是创建新的 change
- 每写完一个 artifact，都先确认对应文件已存在，再继续下一个

---
name: subagent-driven-development
description: 当有实现计划且任务相对独立时使用。通过为每个任务派遣新的 subagent 来执行计划，每个任务后进行两阶段审查：先规格合规审查，再代码质量审查。
---

# Subagent 驱动的开发

通过为每个任务派遣新的 subagent 来执行实现计划，每个任务后进行两阶段审查：先规格合规审查，再代码质量审查。

**为什么用 subagent：** 将任务委托给专门的 agent，获得隔离的上下文。通过精确构建指令和上下文，确保 agent 专注并成功完成任务。它们不应继承当前会话的上下文或历史——由你来构造它们所需的精确内容。这也保持了你自己的上下文用于协调工作。

**核心原则：** 每个任务新鲜 subagent + 两阶段审查（先规格后质量）= 高质量、快迭代。

## 使用场景

```
有实现计划？
  → 任务相对独立？
      → 在当前会话中执行？ → subagent-driven-development
      → 开启并行会话？ → executing-plans
  → 否 → 先 brainstorming 或手动执行
```

**与 executing-plans（并行会话）的区别：**
- 同一会话（无需切换上下文）
- 每个任务新鲜 subagent（无上下文污染）
- 每个任务后两阶段审查
- 更快迭代（任务间无需人工介入）

## 执行流程

```
每个任务：
  1. 读取 isolated-worktree-setup skill，确保隔离环境就绪
  2. 派遣 implementer subagent（携带完整任务文本 + 上下文）
  3. Implementer 提问？
      → 是：回答问题，继续
      → 否：Implementer 实现、编译、自测、提交
  4. 派遣 spec reviewer subagent
  5. Spec reviewer 确认符合规格？
      → 否：Implementer 修复，重新审查
      → 是：派遣代码质量 reviewer
  6. Code quality reviewer 通过？
      → 否：Implementer 修复，重新审查
      → 是：标记任务完成

所有任务完成后：
  派遣最终代码 reviewer 审查整体实现
  使用 finishing-a-development-branch skill 完成开发
```

## 模型选择

使用能处理任务的最弱模型以节省成本和提高速度：

| 任务类型 | 示例 | 推荐模型 |
|---------|------|---------|
| 机械实现 | 孤立函数、清晰规格、1-2 个文件 | 快且便宜的模型 |
| 集成与判断 | 多文件协调、模式匹配、调试 | 标准模型 |
| 架构与审查 | 需要设计判断或广泛代码理解 | 最强模型 |

## Subagent 指令模板

### Implementer subagent 指令

为每个 subagent 构建精确的指令，包含：

```
你是一个专业的 Java 开发专家，正在独立工作。

## 任务
<任务描述>

## 上下文
<项目背景和约束>

## 编译命令
在当前 worktree 中，使用独立 Maven 仓库：
mvn clean compile

验证编译通过后再提交。

## 规则
- 先读取 isolated-worktree-setup skill 确认隔离
- 不修改任务范围外的代码
- 测试通过后提交：git add . && git commit -m "描述"
- 完成后报告 DONE 或具体问题
```

### Spec reviewer subagent 指令

```
检查以下实现是否符合规格要求：

## 规格
<规格描述>

## 实现
<实现的 diff 或文件列表>

## 审查要点
- 是否完成了所有要求的特性？
- 是否有规格外的额外代码？
- 数据结构和接口是否正确？

报告：✅ 通过 或 ❌ 问题列表
```

### Code quality reviewer subagent 指令

```
审查以下代码的质量：

## 文件
<文件列表>

## 审查要点
- 代码可读性和风格
- 是否有潜在的 bug
- 是否有重复代码
- 方法行数是否超标
- 日志是否使用 log 而非 system.out

报告：Strengths / Issues / Approved
```

## Implementer 状态处理

Subagent 报告以下状态之一，按对应方式处理：

| 状态 | 处理方式 |
|------|---------|
| DONE | 进入规格合规审查 |
| DONE_WITH_CONCERNS | 先读concerns再决定，如关于正确性/范围则处理后再审 |
| NEEDS_CONTEXT | 提供缺失上下文，重新派遣 |
| BLOCKED | 评估阻塞原因：上下文问题换模型重试，太大拆小，计划错误上报 |

## 常见错误

**禁止：**
- 在 master/main 分支上开始实现（未经用户明确同意）
- 跳过审查环节
- 有未修复问题仍继续
- 并行派遣多个实现 subagent（冲突）
- 让 subagent 自己读计划文件（由你提供完整文本）
- 跳过场景设置上下文
- 忽略 subagent 的提问
- 规格审查未通过就进入代码质量审查
- 任何一个审查有未解决问题就进入下一任务

**如果 subagent 提问：**
- 清晰完整地回答
- 必要时提供额外上下文

**如果 reviewer 发现问题：**
- 由同一 subagent 修复
- Reviewer 重新审查
- 重复直到通过

## 集成

**必需的工作流 skill：**
- **isolated-worktree-setup** — 开始前必需：设置隔离工作区
- **writing-plans** — 创建本 skill 执行的计划
- **requesting-code-review** — Subagent 代码审查模板
- **finishing-a-development-branch** — 所有任务完成后完成开发

**Subagent 应使用：**
- **test-driven-development** — Subagent 每个任务遵循 TDD

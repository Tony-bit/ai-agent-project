---
name: executing-plans
description: 当有书面实现计划要在独立会话中执行时使用，包含审查检查点。
---

# 执行计划

## 概述

加载已确认计划、进行阻塞检查、执行所有任务、完成时报告。

**开始时声明：** "我正在使用 executing-plans skill 来实现此计划。"

**注意：** 建议使用支持 subagent 的平台运行，这样可以使用 subagent-driven-development skill 获得更高质量的工作。

## 执行阶段沟通约束

- `executing-plans` 用于执行已确认的书面计划，不用于重新进行需求澄清
- 若需求理解、方案确认、计划审阅已在前序阶段完成，则执行时不得再次完整复述任务背景、任务拆解和实施方案
- 用户明确说“执行”“开始执行”或“按该 story / plan 落地”时，默认表示已批准当前计划作为执行依据
- 除非存在阻塞性问题，否则应直接进入代码实现

## 执行流程

### 第一步：加载执行计划并做阻塞检查

1. 读取已由用户确认的计划文件
2. 仅检查是否存在阻塞执行的关键问题，包括：
   - 缺失必要输入
   - 任务顺序矛盾
   - 验收标准缺失到无法实现
   - 与当前代码结构明显冲突
3. 如果存在阻塞问题：开始前向用户提出
4. 如果不存在阻塞问题：不要重复复述完整需求、任务拆解或改动细节
5. 使用 1-2 句简短说明将按哪份计划执行
6. 创建 TodoWrite 并继续

### 设计文档目录约束

- 本项目所有使用 superpowers / opsx / story 流程产生的设计文档、实施计划、方案文档，默认统一存放在 `docs/superpowers/plans/`
- 除非用户明确指定其他路径，否则不得将新的设计文档写入 `docs/trading-agent/` 或其他业务目录
- 当 skill 需要读取、引用、执行计划文档时，应优先从 `docs/superpowers/plans/` 查找
- `docs/trading-agent/` 用于存放业务资料、专题分析、架构说明、研究记录，不作为默认设计文档目录

### 第二步：设置隔离环境（重要！）

在执行任何任务前，**必须**读取 `isolated-worktree-setup` skill 并执行隔离协议：

```bash
# 检查是否已隔离
git worktree list

# 如果不在 worktree 中，创建新的
# 读取 isolated-worktree-setup skill 并执行
```

### 第三步：执行任务

对于每个任务：
1. 标记为 in_progress
2. 严格按计划步骤执行
3. 运行计划中指定的验证
4. 标记为 completed

### 第四步：完成开发

所有任务完成并验证后：
- 声明："我正在使用 finishing-a-development-branch skill 完成此工作。"
- **必需子 skill：** 使用 `finishing-a-development-branch`
- 按该 skill 验证测试、呈现选项、执行选择

## 编译验证

本项目使用 Maven 编译。在验证步骤中使用：

```bash
mvn clean compile -q
```

如果编译失败，报告错误并停止，不要继续。

## 何时停止并寻求帮助

**立即停止执行当：**
- 遇到阻塞（缺少依赖、测试失败、指令不清晰）
- 计划有阻止开始的重大缺陷
- 不理解某个指令
- 验证反复失败

**不要猜测，要询问澄清。**

## 何时回退到更早的步骤

**回退到审查（第 1 步）当：**
- 用户根据反馈更新了计划
- 基本方法需要重新思考

**不要强行突破阻塞** — 停止并询问。

## 集成

**必需的工作流 skill：**
- **isolated-worktree-setup** — 开始前必需：设置隔离工作区
- **writing-plans** — 创建本 skill 执行的计划
- **finishing-a-development-branch** — 所有任务完成后完成开发
- **test-doc-template** — 当需要补充测试文档、测试方案、测试用例设计、提测材料时使用

**替代工作流：**
- 如果平台支持 subagent：使用 `subagent-driven-development` 代替本 skill

---
name: isolated-worktree-setup
description: 为并发 agent 执行设置独立的 git worktree 和 Maven 仓库。在开始任何实现工作前必须读取此 skill。
---

# 独立 Worktree 环境设置

每个 agent 必须在独立的 git worktree 中工作，并使用独立的 Maven 仓库。这可以防止并发编译时产生文件锁冲突。

## 适用场景

- 开始新的实现会话时
- 当前工作目录在 master/main 分支时
- 多个 agent 需要并发处理同一项目时

## 执行流程

### 第一步：检查当前状态

检查是否已在隔离环境中：

```bash
git worktree list
```

如果当前目录是一个 worktree（不是主仓库），检查是否有独立的 `.mvn/jvm.config`：

```bash
if (Test-Path ".mvn/jvm.config") { Get-Content ".mvn/jvm.config" }
```

**如果已隔离**（worktree 列表中有记录，且 `.mvn/jvm.config` 包含 `-Dmaven.repo.local`）：直接开始实现。

**如果没有隔离**：进入第二步。

### 第二步：创建独立 Worktree

创建一个新的 worktree，并配置独立的 Maven 仓库：

```bash
# 1. 根据任务名称生成 worktree 名称
$WORKTREE_NAME = "agent-" + [guid]::NewGuid().ToString("N").Substring(0, 6)

# 2. 创建 worktree
git worktree add ".worktrees/$WORKTREE_NAME" -b "feature/$WORKTREE_NAME"

# 3. 创建 .mvn/jvm.config，指向独立的 Maven 仓库
New-Item -ItemType Directory -Force ".worktrees/$WORKTREE_NAME/.mvn"
Set-Content ".worktrees/$WORKTREE_NAME/.mvn/jvm.config" "-Dmaven.repo.local=.m2-repo"

# 4. 确认 .gitignore 包含 .worktrees/ 和 .m2-repo/
# 如果没有，添加到 .gitignore 并提交

# 5. 输出 worktree 路径，告知 agent 切换到该目录
```

### 第三步：切换到 Worktree

Agent（或用户在 Cursor Agent Tab 中）应将工作目录设置为 worktree 路径。

在 Cursor 中：创建新的 Agent Tab 时，将工作目录设置为 worktree 路径。

### 第四步：验证隔离生效

在 worktree 内执行 Maven 命令后，确认 `.m2-repo` 目录被创建：

```bash
mvn help:system -q
Test-Path ".m2-repo"
```

如果 Maven 运行后出现了 `.m2-repo` 目录，说明隔离生效。

## Maven 编译命令

在独立 worktree 中，直接使用标准 Maven 命令即可：

```bash
mvn clean compile
mvn clean install -DskipTests
```

`.mvn/jvm.config` 会自动让 Maven 使用 `.m2-repo` 而不是共享的 `~/.m2`。

## 项目结构

```
ai-agent-study/
├── .worktrees/              # 所有独立 worktree（已 gitignore）
│   ├── agent-a1b2c3/        # 每个 worktree 包含：
│   │   ├── .mvn/
│   │   │   └── jvm.config   # 指向 .m2-repo
│   │   ├── .m2-repo/        # 独立的 Maven 仓库
│   │   └── （完整项目副本）
│   └── agent-d4e5f6/
├── .gitignore               # 包含 .worktrees/ 和 .m2-repo/
└── （master 分支文件）
```

## 集成要求

此 skill 是以下场景的**前置必读**：
- `openspec-apply-change`
- `subagent-driven-development`
- 任何多 agent 并发实现会话

## 常见错误

- **在 master/main 分支上运行并发 agent**：会导致 Maven 仓库冲突
- **忘记创建 .mvn/jvm.config**：Maven 会回退到共享的 `~/.m2`，隔离失效
- **没有设置 Cursor Agent Tab 的工作目录为 worktree**：agent 仍在 master 分支运行

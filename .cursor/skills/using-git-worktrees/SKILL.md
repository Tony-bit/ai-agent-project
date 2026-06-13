---
name: using-git-worktrees
description: 开始需要与当前工作区隔离的功能开发时使用，或在执行实现计划前使用 — 创建隔离的 git worktree，包含智能目录选择和安全验证。本项目已适配 Maven + 独立 Maven 仓库。
---

# 使用 Git Worktree

## 概述

Git worktree 创建隔离的工作空间，共享同一仓库，同时处理多个分支无需切换。

**核心原则：** 系统化目录选择 + 安全验证 = 可靠的隔离。

**开始时声明：** "我正在使用 using-git-worktrees skill 设置隔离工作区。"

## 目录选择流程

按优先级顺序：

### 1. 检查现有目录

```bash
# 按优先级检查
if (Test-Path ".worktrees") { ".worktrees 存在" }
elseif (Test-Path "worktrees") { "worktrees 存在" }
```

**如果找到：** 使用该目录。如果两者都存在，优先使用 `.worktrees`。

### 2. 检查 CLAUDE.md / 项目规则

```bash
Select-String -Path "CLAUDE.md" -Pattern "worktree.*director" 2>$null
```

**如果指定了偏好：** 直接使用，不询问。

### 3. 询问用户

如果不存在目录且没有 CLAUDE.md 偏好：

```
未找到 worktree 目录。应该在哪里创建 worktree？

1. .worktrees/（项目内，隐藏）
2. 其他位置

请选择。
```

## 安全验证

### 项目内目录（.worktrees 或 worktrees）

**创建 worktree 前必须验证目录是否已被忽略：**

```bash
git check-ignore -q .worktrees 2>$null
if ($LASTEXITCODE -eq 0) { "已忽略" } else { "未忽略，需添加到 .gitignore" }
```

**如果未被忽略：**

立即修复（遵循"立即修复坏掉的东西"原则）：
1. 将相应行添加到 `.gitignore`
2. 提交更改
3. 继续创建 worktree

**为什么关键：** 防止 worktree 内容被意外提交到仓库。

### 全局目录

无需 .gitignore 验证（完全在项目外部）。

## 创建步骤

### 1. 检测项目名称

```bash
$project = Split-Path (git rev-parse --show-toplevel) -Leaf
```

### 2. 创建 Worktree

```powershell
$BRANCH_NAME = "feature/" + [guid]::NewGuid().ToString("N").Substring(0, 6)
$path = ".worktrees/$BRANCH_NAME"

git worktree add $path -b $BRANCH_NAME
Set-Location $path
```

### 3. 设置独立 Maven 仓库（重要！）

```powershell
# 创建 .mvn/jvm.config，指向独立 Maven 仓库
if (-not (Test-Path ".mvn")) { New-Item -ItemType Directory -Force ".mvn" | Out-Null }
Set-Content ".mvn/jvm.config" "-Dmaven.repo.local=.m2-repo"

# 验证 .gitignore 包含 .worktrees/ 和 .m2-repo/
$gitignore = Get-Content ".gitignore" -Raw
if ($gitignore -notmatch "\.worktrees/") { Add-Content ".gitignore" ".worktrees/" }
if ($gitignore -notmatch "\.m2-repo/") { Add-Content ".gitignore" ".m2-repo/" }
```

### 4. 验证隔离生效

```bash
mvn help:system -q
if (Test-Path ".m2-repo") { "隔离生效" }
```

### 5. 验证编译基线

```bash
mvn clean compile -q
```

**如果编译失败：** 报告失败原因，询问是否继续或调查。

**如果编译通过：** 报告就绪。

### 6. 报告位置

```
Worktree 就绪：<完整路径>
编译通过
可以开始实现 <功能名称>
```

## Maven 项目的项目初始化

本项目是 Maven 多模块项目（Java Spring Boot），自动检测：

```bash
# Maven 多模块项目
if (Test-Path "pom.xml") {
    mvn clean compile -q
}
```

## 快速参考

| 情况 | 操作 |
|------|------|
| `.worktrees/` 存在 | 使用它（验证已忽略） |
| `worktrees/` 存在 | 使用它（验证已忽略） |
| 两者都存在 | 使用 `.worktrees/` |
| 都不存在 | 检查 CLAUDE.md → 询问用户 |
| 目录未被忽略 | 添加到 .gitignore + 提交 |
| 编译基线失败 | 报告失败并询问 |
| 无 pom.xml | 跳过依赖安装 |

## 常见错误

### 跳过忽略验证

- **问题：** Worktree 内容被跟踪，污染 git status
- **修复：** 创建项目内 worktree 前始终使用 `git check-ignore`

### 忘记设置独立 Maven 仓库

- **问题：** Maven 使用共享的 `~/.m2`，并发编译冲突
- **修复：** 创建 worktree 后立即创建 `.mvn/jvm.config`

### 假设目录位置

- **问题：** 创建不一致，违反项目约定
- **修复：** 按优先级：现有 > CLAUDE.md > 询问

## 集成

**被以下 skill 调用：**
- **subagent-driven-development** — 执行任务前必需
- **executing-plans** — 执行任务前必需
- **brainstorming**（第 4 阶段）— 设计批准后实现前必需

**与以下 skill 配对：**
- **finishing-a-development-branch** — 完成工作后必需清理

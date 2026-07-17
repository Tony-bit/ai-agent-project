# Intent Few-shot 全量文档存档与兼容改造设计

**日期：** 2026-07-17  
**状态：** 待评审

## 1. 目标

建立一个全量、持久化、可版本管理的 Few-shot JSON 文件。文件结构与 Spring AI 写入 PGVector 前使用的 `Document` 结构保持一致，使维护者能够直接判断每条数据进入向量库后的文本和 metadata，并可由现有集成测试直接读取后导入。

本设计同时完成：

1. 汇总当前 `test_intent_fewshot_pgvector_recall()` 中的旧 Few-shot。
2. 合并 `003-financial-general-intent-fewshot.sql` 中新增的 30 条金融 Few-shot。
3. 将旧 `exampleJson` 转换为当前结构化输出校验器接受的格式。
4. 修正旧金融样本的意图边界，避免旧标签继续污染向量召回。

## 2. 非目标

- 不实现远端 Embedding 调用。
- 不实现自动同步任务、定时任务或运行时文件监听。
- 不使用 MySQL 保存 Few-shot。
- 不把在线评测集直接作为 Few-shot，避免评测数据泄漏。
- 不修改业务侧 Top-K 召回算法。

向量生成和实际导入仍由维护者通过测试执行。

## 3. 文件位置

新增全量文件：

```text
ai-agent-study-app/src/test/resources/fewshot/intent-fewshot-documents.json
```

该文件是仓库内 Few-shot 文本的唯一持久化存档。后续新增、修改、禁用或删除 Few-shot，均先修改此文件。

以下位置不再作为 Few-shot 数据源：

- `OpenAiIntegrationTest.test_intent_fewshot_pgvector_recall()` 中的硬编码 `docs.add(...)`。
- `003-financial-general-intent-fewshot.sql` 中的临时种子数据。
- `intent-routing-cases.json` 和 `intent-routing-online-cases.json` 中的评测用例。

## 4. 持久化格式

顶层使用 JSON 数组，每个元素对应一个待写入 PGVector 的 Spring AI `Document`。

```json
[
  {
    "id": "e43c0988-56d7-3320-b227-c56b1cc6844a",
    "text": "什么是市盈率？",
    "metadata": {
      "id": "34",
      "intentCode": "FINANCIAL_GENERAL",
      "exampleJson": "{\"multiTask\":false,\"needsClarification\":false,\"missingInfo\":[],\"clarificationPrompt\":\"\",\"reasoning\":\"客观金融查询\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"什么是市盈率？\",\"intent\":\"FINANCIAL_GENERAL\",\"confidence\":\"HIGH\",\"dependsOn\":[],\"slots\":{}}]}",
      "status": 1
    }
  }
]
```

### 4.1 字段含义

| 字段 | 类型 | 规则 |
|---|---|---|
| `id` | UUID 字符串 | PGVector 文档主键，必须稳定且唯一 |
| `text` | 字符串 | 参与 Embedding 的用户查询文本 |
| `metadata.id` | 数字字符串 | Few-shot 逻辑 ID，必须稳定且唯一 |
| `metadata.intentCode` | 字符串 | 该样本的意图标签 |
| `metadata.exampleJson` | JSON 字符串 | 注入 Prompt 的完整标准输出，与 PGVector metadata 类型一致 |
| `metadata.status` | 整数 | `1` 表示启用，`0` 表示保留但不导入 |

`exampleJson` 在存档文件中直接保存为 JSON 字符串，与当前 PGVector metadata 及 `documentToSample()` 的读取类型一致。测试反序列化外层文档后可以直接构造 `Document`，不再转换业务字段或二次序列化 `exampleJson`。

### 4.2 稳定 ID

文档 `id` 使用与当前 `IntentFewshotService.vectorDocumentId()` 相同的确定性规则：

```text
UUID.nameUUIDFromBytes("intent-fewshot:" + metadata.id)
```

同一逻辑样本重复导入时生成相同文档 ID，避免产生随机 ID 和重复向量记录。新增样本只分配新的 `metadata.id`，已有样本不得因排序变化而重新编号。

## 5. 当前读取格式的对应关系

测试读取文件后创建：

```java
new Document(document.id, document.text, metadata)
```

其中 metadata 写入 PGVector 前应为：

```text
id          = metadata.id
intentCode  = metadata.intentCode
exampleJson = metadata.exampleJson
status      = metadata.status
```

业务召回后的 `documentToSample()` 映射保持不变：

```text
Document.text                 -> IntentFewshotSample.queryText
Document.metadata.id          -> IntentFewshotSample.id
Document.metadata.intentCode  -> IntentFewshotSample.intentCode
Document.metadata.exampleJson -> IntentFewshotSample.exampleJson
Document.metadata.status      -> IntentFewshotSample.status
```

## 6. 旧 Few-shot 结构兼容改造

### 6.1 普通样本

旧 `taskList` 中的以下字段不再被当前结构化校验器接受，迁移时删除：

```text
executorNode
taskType
```

统一补齐：

```text
root.missingInfo = []
root.clarificationPrompt = ""
task.dependsOn = []
```

普通样本必须满足：

```text
needsClarification = false
taskList 非空
metadata.intentCode = taskList 中每个任务的 intent
```

### 6.2 澄清样本

金融分析深度不明确的样本统一为：

```text
metadata.intentCode = AMBIGUOUS
needsClarification = true
missingInfo = ["analysisDepth"]
clarificationPrompt = "你需要快速了解，还是进行完整投资分析？"
taskList = []
```

现有 `stockCode`、`topic` 等非分析深度澄清样本不应伪装为 `analysisDepth`。若继续保留，应同步放宽业务校验，使 `AMBIGUOUS + 非空 missingInfo + 空 taskList` 成为通用澄清规则；若本次不修改校验器，则先将这些样本设为 `status=0`。

### 6.3 金融意图迁移

| 语义 | 新标签 |
|---|---|
| 股价、行情、K 线、财报、公告、新闻、估值指标、金融知识 | `FINANCIAL_GENERAL` |
| 买入、卖出、持有、仓位、目标价、止损、投资价值、完整投资分析 | `STOCK_ANALYSIS` |
| “看看”“怎么样”“分析一下”等分析深度不明确表达 | `AMBIGUOUS` |
| 无法可靠判断 | `status=0`，等待人工审核 |

## 7. 数据合并规则

初始全量文件由以下两部分合并：

1. 原测试方法中的 33 条旧 Few-shot。
2. 金融 SQL 中的 30 条新增 Few-shot。

合并时按规范化后的 `text` 去重：去除首尾空白并统一连续空格。完全相同文本只保留一条；相同文本标签冲突时按新金融边界处理，不允许同时保留多个启用版本。

评测 JSON 仅用于参考字段组织和业务覆盖，不复制在线评测 query 到 Few-shot 文件。

## 8. 测试导入方式

`test_intent_fewshot_pgvector_recall()` 不再硬编码样本，只负责：

1. 读取 `fewshot/intent-fewshot-documents.json`。
2. 校验文档 ID、逻辑 ID、文本和 metadata 必填字段。
3. 跳过 `status=0` 的样本。
4. 使用文件中的 `id`、`text`、`metadata` 直接构造 Spring AI `Document`。
5. 按远端 Embedding 单批上限分批调用 `intentFewshotVectorStore.accept()`。
6. 输出文件启用数量和本次导入数量。

测试不负责自动清理历史随机 ID 数据。首次使用新文件导入前，维护者应清空专用表 `intent_fewshot_vector_store`，避免旧记录与稳定 ID 记录并存。后续使用稳定 ID 重复导入时应覆盖同一文档。

## 9. 文件维护规则

- 新增：分配新的逻辑 ID 和稳定 UUID，补齐完整 metadata。
- 修改文本：保留逻辑 ID 和 UUID，重新导入以更新 Embedding。
- 修改标签或输出：保留逻辑 ID 和 UUID，重新导入以更新 metadata。
- 禁用：设置 `status=0`；文件保留该条记录，但导入测试跳过。
- 删除：仅用于误录数据；通常优先禁用以保留历史。
- 禁止手工写入 embedding、similarity score 或检索排名。

## 10. 校验要求

导入前必须验证：

1. `id` 是合法且不重复的 UUID。
2. `metadata.id` 是合法且不重复的数字字符串。
3. `text` 非空且启用样本间不重复。
4. `intentCode` 是当前合法枚举。
5. `exampleJson` 通过 `RoutingStructuredOutputValidator`。
6. 普通样本的 metadata intent 与输出 intent 一致。
7. 澄清样本使用 `AMBIGUOUS`，且 `taskList` 为空。
8. 文件启用数量等于测试构造并提交的 `Document` 数量。

## 11. 验收标准

- 仓库中存在一个包含全部 Few-shot 的 JSON 文件。
- 原测试方法中不再维护硬编码 Few-shot 列表。
- 旧样本全部通过新结构校验，或被明确标记为禁用。
- 新增 30 条金融样本全部进入全量文件。
- 同一文本不存在两个启用且标签冲突的样本。
- 测试可以直接从 JSON 构造 Spring AI `Document` 并分批提交。
- 首次清理后，PGVector 中导入记录数与 JSON 中 `status=1` 的记录数一致。

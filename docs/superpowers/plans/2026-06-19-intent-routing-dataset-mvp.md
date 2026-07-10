# 意图路由数据集 MVP 实施计划

> **供执行人员使用：** 必须使用 `superpowers:executing-plans` 技能逐项实施本计划。所有步骤均使用复选框（`- [ ]`）跟踪状态。

**目标：** 构建、校验、审核并版本化第一版合成意图路由数据集，其中包含 2,000 条训练数据、200 条验证数据和 200 条测试数据。

**架构：** 以便于人工阅读的源记录作为唯一事实来源，通过 JSON Schema 和路由业务规则进行校验，并确定性地派生 GLM SFT JSONL。训练集、验证集和测试集分别使用独立场景族生成；导出人工审核表并冻结数据清单前，执行精确重复检查和字符 n-gram 向量相似度检查。

**技术栈：** Python 3.10+、标准库、`jsonschema`、`scikit-learn`、JSONL、unittest

---

## 范围拆分

已确认的总体设计包含三个可独立测试的子系统：

1. 合成数据集构建与审核；
2. GLM-4-9B LoRA 训练及微调前后评测；
3. Langfuse Dataset、Run、Trace 和 Score 集成。

本计划只实现子系统 1。最终产物包括冻结后的源数据集和 SFT 数据集、校验报告、泄漏检查报告以及人工审核产物。只有数据集通过审核门禁后才允许开始训练。训练/评测和 Langfuse 将在本计划验收后分别编写实施计划。

## 文件结构

创建独立、完整的数据集工作区：

```text
fine-tune/intent-routing/
├── README.md
├── requirements.txt
├── config/
│   └── scenario-matrix-v1.json
├── schemas/
│   └── intent-routing-v1.schema.json
├── data/v1/
│   ├── source/
│   │   ├── train.jsonl
│   │   ├── validation.jsonl
│   │   └── test.jsonl
│   ├── sft/
│   │   ├── train.jsonl
│   │   └── validation.jsonl
│   ├── review/
│   │   ├── train-review.csv
│   │   ├── validation-review.csv
│   │   └── test-review.csv
│   └── reports/
│       ├── validation-report.json
│       ├── leakage-report.json
│       └── manifest.json
├── scripts/
│   ├── dataset_common.py
│   ├── validate_dataset.py
│   ├── check_leakage.py
│   ├── export_review.py
│   └── build_sft.py
└── tests/
    ├── fixtures/
    │   ├── valid-record.json
    │   └── invalid-record-cycle.json
    ├── test_validate_dataset.py
    ├── test_check_leakage.py
    ├── test_export_review.py
    └── test_build_sft.py
```

各文件职责如下：

- `scenario-matrix-v1.json`：精确规定各数据切分规模与场景桶配额；
- `intent-routing-v1.schema.json`：定义源记录的结构契约；
- `dataset_common.py`：提供共享的 JSONL 读写、规范化、常量和哈希能力；
- `validate_dataset.py`：执行 Schema 与路由领域规则校验；
- `check_leakage.py`：检测重复数据和跨切分相似数据；
- `export_review.py`：确定性生成分层人工审核表；
- `build_sft.py`：将源记录转换成 GLM 的 prompt/completion 格式；
- `manifest.json`：记录审核后不可变的数据数量与 SHA-256 哈希摘要。

### 任务 1：搭建数据集工作区和场景矩阵

**文件：**
- 新建：`fine-tune/intent-routing/README.md`
- 新建：`fine-tune/intent-routing/requirements.txt`
- 新建：`fine-tune/intent-routing/config/scenario-matrix-v1.json`

- [ ] **步骤 1：创建依赖文件**

```text
jsonschema>=4.23,<5
scikit-learn>=1.5,<2
```

- [ ] **步骤 2：创建精确的场景矩阵**

各数据切分使用以下完整场景桶配额：

```json
{
  "schemaVersion": "intent-routing-v1",
  "splits": {
    "train": {
      "single_intent": 1200,
      "intent_boundary": 240,
      "clarification": 160,
      "multi_task_independent": 160,
      "multi_task_dependent": 160,
      "multi_turn": 80
    },
    "validation": {
      "single_intent": 120,
      "intent_boundary": 24,
      "clarification": 16,
      "multi_task_independent": 16,
      "multi_task_dependent": 16,
      "multi_turn": 8
    },
    "test": {
      "single_intent": 120,
      "intent_boundary": 24,
      "clarification": 16,
      "multi_task_independent": 16,
      "multi_task_dependent": 16,
      "multi_turn": 8
    }
  },
  "singleIntentDistribution": {
    "STOCK_ANALYSIS": 0.1666666667,
    "PE_REASONING": 0.1666666667,
    "PE_CALCULATION": 0.1666666667,
    "PE_RETRIEVAL": 0.1666666667,
    "INSPECTION": 0.1666666667,
    "GENERAL_CHAT": 0.1666666665
  },
  "review": {
    "train": 50,
    "validation": 50,
    "test": 200,
    "seed": 42
  }
}
```

- [ ] **步骤 3：记录命令与审核状态语义**

在 `README.md` 中记录以下命令及其用途：

```powershell
python -m pip install -r fine-tune/intent-routing/requirements.txt
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
python fine-tune/intent-routing/scripts/check_leakage.py --root fine-tune/intent-routing/data/v1
python fine-tune/intent-routing/scripts/export_review.py --root fine-tune/intent-routing/data/v1 --config fine-tune/intent-routing/config/scenario-matrix-v1.json
python fine-tune/intent-routing/scripts/build_sft.py --root fine-tune/intent-routing/data/v1
```

审核状态严格限定为 `pending`、`approved` 和 `rejected`。明确规定：冻结数据清单前，每一条测试数据都必须处于 `approved` 状态。

- [ ] **步骤 4：核对场景数量合计**

运行：

```powershell
python -c "import json; p=json.load(open('fine-tune/intent-routing/config/scenario-matrix-v1.json',encoding='utf-8')); print({k:sum(v.values()) for k,v in p['splits'].items()})"
```

预期输出：

```text
{'train': 2000, 'validation': 200, 'test': 200}
```

- [ ] **步骤 5：提交工作区骨架**

```powershell
git add fine-tune/intent-routing/README.md fine-tune/intent-routing/requirements.txt fine-tune/intent-routing/config/scenario-matrix-v1.json
git commit -m "chore: scaffold intent routing dataset workspace"
```

### 任务 2：定义源记录契约

**文件：**
- 新建：`fine-tune/intent-routing/schemas/intent-routing-v1.schema.json`
- 新建：`fine-tune/intent-routing/tests/fixtures/valid-record.json`
- 新建：`fine-tune/intent-routing/tests/fixtures/invalid-record-cycle.json`

- [ ] **步骤 1：编写合法测试夹具**

创建一条完整的双任务记录：第一个任务为 `PE_RETRIEVAL`，第二个任务为 `PE_REASONING`，且第二个任务包含 `"dependsOn":["sub-1"]`。记录必须包含已确认设计中的全部顶层字段：`caseId`、`split`、`scenarioFamily`、`scenarioBucket`、`difficulty`、`input`、`expected` 和 `metadata`。

- [ ] **步骤 2：编写包含循环依赖的非法夹具**

复制合法夹具，将 ID 改为 `fixture-invalid-cycle`，让 `sub-1` 依赖 `sub-2`，同时保留 `sub-2` 对 `sub-1` 的依赖。该夹具必须通过 JSON Schema 校验，但必须在领域规则校验中失败。

- [ ] **步骤 3：实现 JSON Schema**

Schema 必须强制约束以下基础结构：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "intent-routing-v1",
  "type": "object",
  "additionalProperties": false,
  "required": ["caseId", "split", "scenarioFamily", "scenarioBucket", "difficulty", "input", "expected", "metadata"]
}
```

使用以下精确枚举补全 Schema：

- 数据切分：`train`、`validation`、`test`；
- 场景桶：`single_intent`、`intent_boundary`、`clarification`、`multi_task_independent`、`multi_task_dependent`、`multi_turn`；
- 难度：`easy`、`medium`、`hard`；
- 任务意图：`STOCK_ANALYSIS`、`PE_REASONING`、`PE_CALCULATION`、`PE_RETRIEVAL`、`INSPECTION`、`GENERAL_CHAT`；
- 置信度：`HIGH`、`MEDIUM`、`LOW`；
- 审核状态：`generated`、`pending`、`approved`、`rejected`。

要求 `input.query` 为非空字符串，`input.historyMessages` 为字符串数组。要求 `expected` 包含完整路由结构，包括每个任务的 ID、序号、内容、意图、执行节点、置信度、类型、槽位和 `dependsOn` 数组。

- [ ] **步骤 4：确认两个夹具都满足结构契约**

运行：

```powershell
python -c "import json; from jsonschema import Draft202012Validator; s=json.load(open('fine-tune/intent-routing/schemas/intent-routing-v1.schema.json',encoding='utf-8')); [Draft202012Validator(s).validate(json.load(open(f'fine-tune/intent-routing/tests/fixtures/{n}',encoding='utf-8'))) for n in ['valid-record.json','invalid-record-cycle.json']]; print('schema fixtures valid')"
```

预期输出：`schema fixtures valid`。

- [ ] **步骤 5：提交源记录契约**

```powershell
git add fine-tune/intent-routing/schemas fine-tune/intent-routing/tests/fixtures
git commit -m "feat: define intent routing dataset schema"
```

### 任务 3：实现 Schema 与领域规则校验

**文件：**
- 新建：`fine-tune/intent-routing/scripts/dataset_common.py`
- 新建：`fine-tune/intent-routing/scripts/validate_dataset.py`
- 新建：`fine-tune/intent-routing/tests/test_validate_dataset.py`

- [ ] **步骤 1：编写预期失败的校验器测试**

创建包含以下断言的 unittest 测试：

```python
class ValidateDatasetTest(unittest.TestCase):
    def test_valid_record_has_no_errors(self):
        self.assertEqual([], validate_record(load_fixture("valid-record.json")))

    def test_cycle_is_rejected(self):
        errors = validate_record(load_fixture("invalid-record-cycle.json"))
        self.assertTrue(any("dependency cycle" in error for error in errors))

    def test_clarification_fields_are_consistent(self):
        record = load_fixture("valid-record.json")
        record["expected"]["needsClarification"] = True
        self.assertTrue(any("missingInfo" in error for error in validate_record(record)))

    def test_task_indices_must_be_contiguous(self):
        record = load_fixture("valid-record.json")
        record["expected"]["taskList"][1]["taskIndex"] = 3
        self.assertTrue(any("taskIndex" in error for error in validate_record(record)))
```

- [ ] **步骤 2：运行测试并确认其按预期失败**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_validate_dataset.py" -v
```

预期结果：测试失败，原因是 `dataset_common` 和 `validate_dataset` 尚不存在。

- [ ] **步骤 3：实现共享工具函数**

在 `dataset_common.py` 中实现：

```python
import hashlib
import json
import re
import unicodedata
from pathlib import Path


def read_jsonl(path: Path) -> list[dict]:
    records = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: {exc.msg}") from exc
    return records


def write_jsonl(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(canonical_json(record) + "\n")


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalized_query(record: dict) -> str:
    input_value = record["input"]
    text = "\n".join([*input_value.get("historyMessages", []), input_value["query"]])
    text = unicodedata.normalize("NFKC", text).lower()
    return re.sub(r"\s+", " ", text).strip()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
```

`canonical_json` 必须按键排序、使用紧凑分隔符，并以 UTF-8 中文输出而不做 ASCII 转义。`normalized_query` 必须拼接历史消息和当前查询，将拉丁字符转换为小写，使用 NFKC 规范化 Unicode，并合并多余空白。

- [ ] **步骤 4：实现领域规则校验**

在 `validate_dataset.py` 中公开 `validate_record(record: dict) -> list[str]`，并强制执行：

1. JSON Schema 合法；
2. 任务 ID 唯一；
3. `taskIndex` 从 1 到任务总数连续递增；
4. 每个任务的 `totalTasks` 等于实际任务数；
5. `multiTask` 等价于“任务数大于 1”；
6. 每个依赖项都引用已存在且顺序更早的任务；
7. 依赖图无环；
8. 需要澄清时，`missingInfo` 和澄清问题非空，任务列表为空；
9. 无需澄清时，`missingInfo` 和澄清问题为空，任务列表非空；
10. 执行节点映射正确：股票对应 `tradingStarter`，巡检对应 `intelligentInspection`，通用对话对应 `generalChatNode`，全部 PE 意图对应 `step1AnalyzerNode`；
11. 记录中的 split 与被校验文件一致；
12. 全部 case ID 在数据集中全局唯一。

命令行程序必须校验三个源文件，写出 `reports/validation-report.json`，按数据切分和场景桶打印数量；发现任意错误时返回退出码 1。

- [ ] **步骤 5：运行校验器测试**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_validate_dataset.py" -v
```

预期结果：4 项测试全部通过。

- [ ] **步骤 6：提交校验器**

```powershell
git add fine-tune/intent-routing/scripts/dataset_common.py fine-tune/intent-routing/scripts/validate_dataset.py fine-tune/intent-routing/tests/test_validate_dataset.py
git commit -m "feat: validate intent routing source records"
```

### 任务 4：实现重复数据与跨切分泄漏检查

**文件：**
- 新建：`fine-tune/intent-routing/scripts/check_leakage.py`
- 新建：`fine-tune/intent-routing/tests/test_check_leakage.py`

- [ ] **步骤 1：编写预期失败的泄漏检查测试**

```python
class LeakageTest(unittest.TestCase):
    def test_exact_duplicate_across_splits_is_reported(self):
        findings = find_leakage({"train": [record("帮我分析茅台")], "test": [record("帮我分析茅台")]})
        self.assertEqual(1, len(findings["exactCrossSplit"]))

    def test_near_duplicate_across_splits_is_reported(self):
        findings = find_leakage({"train": [record("请分析贵州茅台近期走势")], "test": [record("分析一下贵州茅台最近的走势")]} , threshold=0.72)
        self.assertEqual(1, len(findings["nearCrossSplit"]))

    def test_different_scenarios_are_not_reported(self):
        findings = find_leakage({"train": [record("计算年化收益")], "test": [record("你好，今天怎么样")]} , threshold=0.72)
        self.assertEqual([], findings["nearCrossSplit"])
```

- [ ] **步骤 2：运行测试并确认其按预期失败**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_check_leakage.py" -v
```

预期结果：测试失败，原因是 `check_leakage` 尚不存在。

- [ ] **步骤 3：实现泄漏检测**

使用 `TfidfVectorizer(analyzer="char", ngram_range=(2, 4), min_df=1)` 对规范化后的历史消息和当前查询计算余弦相似度，并报告：

- 同一切分内部的精确重复；
- 不同切分之间的精确重复；
- 跨切分且相似度不低于 `0.72` 的样本对；
- 被不同切分重复使用的 `scenarioFamily`。

命令行程序必须写出 `reports/leakage-report.json`。如果存在跨切分精确重复、跨切分场景族复用，或达到阈值的近似样本对，则返回退出码 1。

- [ ] **步骤 4：运行泄漏检查测试**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_check_leakage.py" -v
```

预期结果：3 项测试全部通过。

- [ ] **步骤 5：提交泄漏检查功能**

```powershell
git add fine-tune/intent-routing/scripts/check_leakage.py fine-tune/intent-routing/tests/test_check_leakage.py
git commit -m "feat: detect intent dataset split leakage"
```

### 任务 5：实现确定性的人工审核表导出

**文件：**
- 新建：`fine-tune/intent-routing/scripts/export_review.py`
- 新建：`fine-tune/intent-routing/tests/test_export_review.py`

- [ ] **步骤 1：编写预期失败的审核表导出测试**

```python
class ExportReviewTest(unittest.TestCase):
    def test_test_split_exports_every_record(self):
        rows = select_for_review(make_records(12), requested=12, seed=42)
        self.assertEqual(12, len(rows))

    def test_sample_is_deterministic(self):
        records = make_records(100)
        first = [r["caseId"] for r in select_for_review(records, 20, 42)]
        second = [r["caseId"] for r in select_for_review(records, 20, 42)]
        self.assertEqual(first, second)

    def test_each_bucket_is_represented(self):
        selected = select_for_review(make_bucketed_records(), 12, 42)
        self.assertEqual(6, len({r["scenarioBucket"] for r in selected}))
```

- [ ] **步骤 2：运行测试并确认其按预期失败**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_export_review.py" -v
```

预期结果：测试失败，原因是 `export_review` 尚不存在。

- [ ] **步骤 3：实现分层抽样和 CSV 输出**

CSV 必须包含以下列：

```text
case_id,split,scenario_bucket,scenario_family,difficulty,query,history_json,expected_json,review_status,review_comment
```

训练集和验证集抽样必须保证每个场景桶至少有一条记录，其余名额按比例分配。测试集必须导出全部 200 条记录。`review_status` 初始值为 `pending`；审核人员只能将其改为 `approved` 或 `rejected`，并可填写审核备注。

- [ ] **步骤 4：运行审核表导出测试**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_export_review.py" -v
```

预期结果：3 项测试全部通过。

- [ ] **步骤 5：提交审核工具**

```powershell
git add fine-tune/intent-routing/scripts/export_review.py fine-tune/intent-routing/tests/test_export_review.py
git commit -m "feat: export stratified intent dataset reviews"
```

### 任务 6：分十个批次生成并校验训练集源数据

**文件：**
- 新建：`fine-tune/intent-routing/data/v1/source/train.jsonl`

- [ ] **步骤 1：生成训练批次 01～06**

生成 1,200 条 `single_intent` 记录，每个可执行意图 200 条。每个意图至少包含 40 条简单样本、100 条中等样本和 60 条困难样本。使用以 `tr-` 开头且仅供训练集使用的场景族。禁止只替换实体、数字或标点来批量衍生样本。

- [ ] **步骤 2：校验前 1,200 条记录**

运行校验器；只有结构错误和领域错误均为零时才继续。预期部分输出包含 `train: 1200` 和 `single_intent: 1200`。

- [ ] **步骤 3：生成训练批次 07**

生成 240 条 `intent_boundary` 记录。检索与通用对话、推理与通用对话、计算与通用对话、巡检与推理四类边界各分配 60 条。至少一半必须是表面关键词容易诱导到错误意图的困难负样本。

- [ ] **步骤 4：生成训练批次 08**

生成 160 条澄清记录，覆盖缺少检索主题、确实无法解析的股票标的、缺少巡检目标、计算输入含糊和指代不明等情况。当现有路由规则规定下游能够解析股票中文名或文档引用时，不得将其误标为信息缺失。

- [ ] **步骤 5：生成训练批次 09**

生成 160 条独立多任务记录和 160 条依赖型多任务记录。独立任务的依赖必须为空；依赖型任务必须形成无环图，并覆盖“先检索再推理”“先计算再推理”和“先巡检再推理”等任务链。

- [ ] **步骤 6：生成训练批次 10**

生成 80 条多轮记录，每条包含 1～6 条历史消息。覆盖简短追答、代词消解、修正上一轮任务和补充缺失槽位等情况。

- [ ] **步骤 7：校验数量和业务规则**

运行：

```powershell
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
```

训练集预期合计：

```text
train: 2000
single_intent: 1200
intent_boundary: 240
clarification: 160
multi_task_independent: 160
multi_task_dependent: 160
multi_turn: 80
errors: 0
```

- [ ] **步骤 8：提交训练集源数据**

```powershell
git add fine-tune/intent-routing/data/v1/source/train.jsonl fine-tune/intent-routing/data/v1/reports/validation-report.json
git commit -m "data: add synthetic intent routing train split"
```

### 任务 7：生成相互隔离的验证集和测试集源数据

**文件：**
- 新建：`fine-tune/intent-routing/data/v1/source/validation.jsonl`
- 新建：`fine-tune/intent-routing/data/v1/source/test.jsonl`

- [ ] **步骤 1：使用验证集专属场景族生成验证数据**

严格生成 200 条记录，使用以 `va-` 开头的验证集专属场景族，并遵守验证集场景桶配额。任何记录都不得是训练记录的直接改写。单意图记录中，每个可执行意图必须包含 20 条样本。

- [ ] **步骤 2：使用测试集专属场景族生成测试数据**

严格生成 200 条记录，使用以 `te-` 开头的测试集专属场景族，并遵守测试集场景桶配额。单意图记录中，每个可执行意图必须包含 20 条样本。重点覆盖组合泛化、边界困难负样本、中文口语、错别字和训练集中未出现的实体组合。

- [ ] **步骤 3：校验全部源记录**

运行：

```powershell
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
```

预期结果：训练集 2,000 条、验证集 200 条、测试集 200 条，错误数为 0。

- [ ] **步骤 4：运行泄漏检查**

运行：

```powershell
python fine-tune/intent-routing/scripts/check_leakage.py --root fine-tune/intent-routing/data/v1
```

预期结果：不存在跨切分精确重复、不存在跨切分场景族复用、不存在相似度达到或超过 0.72 的跨切分样本对。

- [ ] **步骤 5：处理全部泄漏问题**

对每一项问题，删除或重写整个场景实例，同时保持场景桶数量不变。重复运行数据校验和泄漏检查，直到两者退出码均为 0。

- [ ] **步骤 6：提交验证集和测试集数据**

```powershell
git add fine-tune/intent-routing/data/v1/source/validation.jsonl fine-tune/intent-routing/data/v1/source/test.jsonl fine-tune/intent-routing/data/v1/reports/validation-report.json fine-tune/intent-routing/data/v1/reports/leakage-report.json
git commit -m "data: add isolated intent validation and test splits"
```

### 任务 8：导出并完成人工审核门禁

**文件：**
- 新建：`fine-tune/intent-routing/data/v1/review/train-review.csv`
- 新建：`fine-tune/intent-routing/data/v1/review/validation-review.csv`
- 新建：`fine-tune/intent-routing/data/v1/review/test-review.csv`
- 修改：`fine-tune/intent-routing/data/v1/source/*.jsonl` 中审核不通过的记录

- [ ] **步骤 1：导出审核表**

运行：

```powershell
python fine-tune/intent-routing/scripts/export_review.py --root fine-tune/intent-routing/data/v1 --config fine-tune/intent-routing/config/scenario-matrix-v1.json
```

预期结果：训练集 50 条、验证集 50 条、测试集全部 200 条。

- [ ] **步骤 2：审核训练集和验证集抽样数据**

逐条检查输入是否自然、意图是否正确、澄清判断是否合理、任务拆分和依赖方向是否正确、执行节点映射和槽位是否准确。正确记录标记为 `approved`；错误记录标记为 `rejected`，并填写具体原因。

- [ ] **步骤 3：审核全部测试样本**

使用相同标准审核全部 200 条测试数据。审核测试数据时不得参考任何模型预测，只检查输入、上下文和期望输出。

- [ ] **步骤 4：按场景族修正审核不通过的源记录**

如果某条不通过记录暴露出系统性问题，则检查并修正同一场景族的全部记录。修正后重新生成审核 CSV，确保其与源数据哈希一致。

- [ ] **步骤 5：重新运行两项门禁检查**

运行数据校验器和泄漏检查器。预期两者退出码均为 0，且数据切分与场景桶数量保持不变。

- [ ] **步骤 6：提交审核后的源数据**

```powershell
git add fine-tune/intent-routing/data/v1/source fine-tune/intent-routing/data/v1/review fine-tune/intent-routing/data/v1/reports
git commit -m "data: complete intent routing dataset review"
```

### 任务 9：构建确定性的 GLM SFT 文件

**文件：**
- 新建：`fine-tune/intent-routing/scripts/build_sft.py`
- 新建：`fine-tune/intent-routing/tests/test_build_sft.py`
- 新建：`fine-tune/intent-routing/data/v1/sft/train.jsonl`
- 新建：`fine-tune/intent-routing/data/v1/sft/validation.jsonl`

- [ ] **步骤 1：编写预期失败的 SFT 转换测试**

```python
class BuildSftTest(unittest.TestCase):
    def test_builds_prompt_and_completion_messages(self):
        item = to_sft(load_fixture("valid-record.json"))
        self.assertEqual(["system", "user"], [m["role"] for m in item["prompt"]])
        self.assertEqual("assistant", item["completion"][0]["role"])

    def test_completion_is_compact_valid_json(self):
        item = to_sft(load_fixture("valid-record.json"))
        content = item["completion"][0]["content"]
        self.assertEqual(json.loads(content), load_fixture("valid-record.json")["expected"])
        self.assertNotIn("```", content)

    def test_test_split_is_not_exported_for_training(self):
        with self.assertRaises(ValueError):
            build_split("test", [load_fixture("valid-record.json")])
```

- [ ] **步骤 2：运行测试并确认其按预期失败**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_build_sft.py" -v
```

预期结果：测试失败，原因是 `build_sft` 尚不存在。

- [ ] **步骤 3：实现确定性转换**

创建精简的系统消息，其中包含六类意图定义、仅输出 JSON 的要求、澄清字段不变量、执行节点映射和任务依赖规则。将历史消息放入固定的 `Recent conversation:` 段落，将当前查询放入 `Current request:` 段落。completion 使用规范化紧凑 JSON 序列化。

只导出训练集和验证集。保留 `caseId`、`scenarioBucket` 和 `difficulty` 作为不参与训练的诊断元数据字段。

- [ ] **步骤 4：运行 SFT 测试**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_build_sft.py" -v
```

预期结果：3 项测试全部通过。

- [ ] **步骤 5：构建 SFT 文件**

运行：

```powershell
python fine-tune/intent-routing/scripts/build_sft.py --root fine-tune/intent-routing/data/v1
```

预期结果：生成 2,000 条训练 SFT 记录和 200 条验证 SFT 记录，不生成测试集 SFT 文件。

- [ ] **步骤 6：提交 SFT 产物**

```powershell
git add fine-tune/intent-routing/scripts/build_sft.py fine-tune/intent-routing/tests/test_build_sft.py fine-tune/intent-routing/data/v1/sft
git commit -m "feat: build GLM intent routing SFT dataset"
```

### 任务 10：冻结数据集清单并执行最终门禁

**文件：**
- 新建：`fine-tune/intent-routing/data/v1/reports/manifest.json`
- 修改：`fine-tune/intent-routing/README.md`

- [ ] **步骤 1：运行完整测试套件**

运行：

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_*.py" -v
```

预期结果：数据校验、泄漏检查、人工审核导出和 SFT 转换测试全部通过。

- [ ] **步骤 2：运行正式数据校验和泄漏门禁**

运行：

```powershell
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
python fine-tune/intent-routing/scripts/check_leakage.py --root fine-tune/intent-routing/data/v1
```

预期结果：两个命令退出码均为 0，数据切分数量严格保持为 2,000/200/200。

- [ ] **步骤 3：冻结数据清单**

按以下结构写入 `manifest.json`：

```json
{
  "datasetVersion": "v1",
  "schemaVersion": "intent-routing-v1",
  "counts": {"train": 2000, "validation": 200, "test": 200},
  "reviewCounts": {"train": 50, "validation": 50, "test": 200},
  "files": {},
  "validationPassed": true,
  "leakageCheckPassed": true
}
```

在 `files` 中记录除 `manifest.json` 自身以外的全部源数据、SFT 数据、审核文件和报告文件的 SHA-256。只要任一必审记录不是 `approved`，或者任一门禁未通过，就必须拒绝冻结。

- [ ] **步骤 4：记录不可变数据集版本规则**

更新 `README.md`：数据冻结后，任何源数据变更都必须创建新的数据版本目录和新清单。明确规定测试集绝不能复制到 SFT 文件、Prompt 示例或 Few-shot 向量库中。

- [ ] **步骤 5：提交冻结后的 MVP 数据集**

```powershell
git add fine-tune/intent-routing/README.md fine-tune/intent-routing/data/v1/reports/manifest.json
git commit -m "data: freeze intent routing dataset v1"
```

## 完成标准

只有同时满足以下条件，本计划才算完成：

- 源数据数量严格为 2,000/200/200；
- 场景桶配额与 `scenario-matrix-v1.json` 完全一致；
- 全部自动化测试通过；
- Schema 和领域规则校验错误数为零；
- 泄漏检查不存在阻断项；
- 训练集 50 条、验证集 50 条和测试集全部 200 条均审核通过；
- 训练集和验证集 SFT 文件存在，测试集 SFT 文件不存在；
- `manifest.json` 包含全部版本化产物的哈希；
- 所有任务提交均可在 Git 历史中追溯。

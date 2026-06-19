# Intent Routing Dataset MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, validate, review, and version the first synthetic intent-routing dataset containing 2,000 training, 200 validation, and 200 test records.

**Architecture:** Keep human-readable source records as the single source of truth, validate them against a JSON Schema plus routing business rules, and derive GLM SFT JSONL deterministically. Generate each split from separate scenario families, then run exact and character-ngram vector similarity checks before exporting review sheets and freezing the dataset manifest.

**Tech Stack:** Python 3.10+, standard library, `jsonschema`, `scikit-learn`, JSONL, unittest

---

## Scope Decomposition

The approved design contains three independently testable subsystems:

1. synthetic dataset construction and review;
2. GLM-4-9B LoRA training and before/after evaluation;
3. Langfuse Dataset, Run, Trace, and Score integration.

This plan implements subsystem 1 only. It ends with frozen source and SFT datasets, validation reports, leakage reports, and human-review artifacts. Training does not begin until the dataset review gate passes. Separate implementation plans will cover training/evaluation and Langfuse after this plan is accepted.

## File Structure

Create a self-contained dataset workspace:

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

Responsibilities:

- `scenario-matrix-v1.json`: exact split sizes and scenario-bucket quotas;
- `intent-routing-v1.schema.json`: structural contract for source records;
- `dataset_common.py`: shared JSONL reading, canonicalization, constants, and hashing;
- `validate_dataset.py`: schema and routing-domain validation;
- `check_leakage.py`: duplicate and cross-split similarity detection;
- `export_review.py`: deterministic stratified human-review sheets;
- `build_sft.py`: source-record to GLM prompt/completion conversion;
- `manifest.json`: immutable summary of counts and SHA-256 hashes after review.

### Task 1: Scaffold the Dataset Workspace and Scenario Matrix

**Files:**
- Create: `fine-tune/intent-routing/README.md`
- Create: `fine-tune/intent-routing/requirements.txt`
- Create: `fine-tune/intent-routing/config/scenario-matrix-v1.json`

- [ ] **Step 1: Create the dependency file**

```text
jsonschema>=4.23,<5
scikit-learn>=1.5,<2
```

- [ ] **Step 2: Create the exact scenario matrix**

Use this complete bucket allocation for each split:

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

- [ ] **Step 3: Document commands and review semantics**

Write `README.md` with these commands and meanings:

```powershell
python -m pip install -r fine-tune/intent-routing/requirements.txt
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
python fine-tune/intent-routing/scripts/check_leakage.py --root fine-tune/intent-routing/data/v1
python fine-tune/intent-routing/scripts/export_review.py --root fine-tune/intent-routing/data/v1 --config fine-tune/intent-routing/config/scenario-matrix-v1.json
python fine-tune/intent-routing/scripts/build_sft.py --root fine-tune/intent-routing/data/v1
```

Define review values exactly as `pending`, `approved`, and `rejected`. State that every test row must be `approved` before the manifest can be frozen.

- [ ] **Step 4: Verify the scenario totals**

Run:

```powershell
python -c "import json; p=json.load(open('fine-tune/intent-routing/config/scenario-matrix-v1.json',encoding='utf-8')); print({k:sum(v.values()) for k,v in p['splits'].items()})"
```

Expected:

```text
{'train': 2000, 'validation': 200, 'test': 200}
```

- [ ] **Step 5: Commit the scaffold**

```powershell
git add fine-tune/intent-routing/README.md fine-tune/intent-routing/requirements.txt fine-tune/intent-routing/config/scenario-matrix-v1.json
git commit -m "chore: scaffold intent routing dataset workspace"
```

### Task 2: Define the Source Record Contract

**Files:**
- Create: `fine-tune/intent-routing/schemas/intent-routing-v1.schema.json`
- Create: `fine-tune/intent-routing/tests/fixtures/valid-record.json`
- Create: `fine-tune/intent-routing/tests/fixtures/invalid-record-cycle.json`

- [ ] **Step 1: Write a valid fixture**

Create a complete two-task record whose first task is `PE_RETRIEVAL`, whose second task is `PE_REASONING`, and whose second task contains `"dependsOn":["sub-1"]`. Include all top-level fields from the approved design: `caseId`, `split`, `scenarioFamily`, `scenarioBucket`, `difficulty`, `input`, `expected`, and `metadata`.

- [ ] **Step 2: Write an invalid cyclic fixture**

Copy the valid fixture, change its ID to `fixture-invalid-cycle`, make `sub-1` depend on `sub-2`, and keep `sub-2` depending on `sub-1`. This fixture must pass JSON Schema validation but fail domain validation.

- [ ] **Step 3: Implement the JSON Schema**

The schema must enforce:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "intent-routing-v1",
  "type": "object",
  "additionalProperties": false,
  "required": ["caseId", "split", "scenarioFamily", "scenarioBucket", "difficulty", "input", "expected", "metadata"]
}
```

Complete the schema with these exact enums:

- split: `train`, `validation`, `test`;
- scenario bucket: `single_intent`, `intent_boundary`, `clarification`, `multi_task_independent`, `multi_task_dependent`, `multi_turn`;
- difficulty: `easy`, `medium`, `hard`;
- task intent: `STOCK_ANALYSIS`, `PE_REASONING`, `PE_CALCULATION`, `PE_RETRIEVAL`, `INSPECTION`, `GENERAL_CHAT`;
- confidence: `HIGH`, `MEDIUM`, `LOW`;
- review status: `generated`, `pending`, `approved`, `rejected`.

Require `input.query` as a non-empty string and `input.historyMessages` as an array of strings. Require the full expected routing shape, including every task's IDs, indices, content, intent, executor node, confidence, type, slots, and `dependsOn` array.

- [ ] **Step 4: Confirm both fixtures satisfy the structural contract**

Run:

```powershell
python -c "import json; from jsonschema import Draft202012Validator; s=json.load(open('fine-tune/intent-routing/schemas/intent-routing-v1.schema.json',encoding='utf-8')); [Draft202012Validator(s).validate(json.load(open(f'fine-tune/intent-routing/tests/fixtures/{n}',encoding='utf-8'))) for n in ['valid-record.json','invalid-record-cycle.json']]; print('schema fixtures valid')"
```

Expected: `schema fixtures valid`.

- [ ] **Step 5: Commit the contract**

```powershell
git add fine-tune/intent-routing/schemas fine-tune/intent-routing/tests/fixtures
git commit -m "feat: define intent routing dataset schema"
```

### Task 3: Implement Schema and Domain Validation

**Files:**
- Create: `fine-tune/intent-routing/scripts/dataset_common.py`
- Create: `fine-tune/intent-routing/scripts/validate_dataset.py`
- Create: `fine-tune/intent-routing/tests/test_validate_dataset.py`

- [ ] **Step 1: Write failing validator tests**

Create unittest cases with these assertions:

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

- [ ] **Step 2: Run the tests to verify failure**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_validate_dataset.py" -v
```

Expected: FAIL because `dataset_common` and `validate_dataset` do not exist.

- [ ] **Step 3: Implement shared utilities**

In `dataset_common.py`, implement:

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

`canonical_json` must use sorted keys, compact separators, and UTF-8 Chinese without ASCII escaping. `normalized_query` must join history and query, lowercase Latin characters, normalize Unicode with NFKC, and collapse whitespace.

- [ ] **Step 4: Implement domain validation**

In `validate_dataset.py`, expose `validate_record(record: dict) -> list[str]` and enforce:

1. JSON Schema validity;
2. unique task IDs;
3. contiguous task indices from 1 to task count;
4. every `totalTasks` equals task count;
5. `multiTask` equals `task count > 1`;
6. every dependency references an existing earlier task;
7. dependency graph is acyclic;
8. clarification true requires non-empty `missingInfo`, non-empty prompt, and empty task list;
9. clarification false requires empty `missingInfo`, empty prompt, and non-empty task list;
10. executor node mapping: stock to `tradingStarter`, inspection to `intelligentInspection`, general chat to `generalChatNode`, and all PE intents to `step1AnalyzerNode`;
11. record split matches the file being validated;
12. all case IDs are globally unique.

The CLI must validate all three source files, write `reports/validation-report.json`, print counts by split and bucket, and return exit code 1 if any error exists.

- [ ] **Step 5: Run validator tests**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_validate_dataset.py" -v
```

Expected: 4 tests PASS.

- [ ] **Step 6: Commit the validator**

```powershell
git add fine-tune/intent-routing/scripts/dataset_common.py fine-tune/intent-routing/scripts/validate_dataset.py fine-tune/intent-routing/tests/test_validate_dataset.py
git commit -m "feat: validate intent routing source records"
```

### Task 4: Implement Duplicate and Cross-Split Leakage Checks

**Files:**
- Create: `fine-tune/intent-routing/scripts/check_leakage.py`
- Create: `fine-tune/intent-routing/tests/test_check_leakage.py`

- [ ] **Step 1: Write failing leakage tests**

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

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_check_leakage.py" -v
```

Expected: FAIL because `check_leakage` does not exist.

- [ ] **Step 3: Implement leakage detection**

Use `TfidfVectorizer(analyzer="char", ngram_range=(2, 4), min_df=1)` and cosine similarity over normalized history plus query. Report:

- exact duplicates inside a split;
- exact duplicates across splits;
- cross-split pairs at or above threshold `0.72`;
- reused `scenarioFamily` values across splits.

The CLI must write `reports/leakage-report.json` and exit 1 when any exact cross-split duplicate, reused scenario family, or near pair at/above threshold exists.

- [ ] **Step 4: Run leakage tests**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_check_leakage.py" -v
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit leakage checks**

```powershell
git add fine-tune/intent-routing/scripts/check_leakage.py fine-tune/intent-routing/tests/test_check_leakage.py
git commit -m "feat: detect intent dataset split leakage"
```

### Task 5: Implement Deterministic Human-Review Exports

**Files:**
- Create: `fine-tune/intent-routing/scripts/export_review.py`
- Create: `fine-tune/intent-routing/tests/test_export_review.py`

- [ ] **Step 1: Write failing review-export tests**

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

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_export_review.py" -v
```

Expected: FAIL because `export_review` does not exist.

- [ ] **Step 3: Implement stratified selection and CSV output**

The CSV columns must be:

```text
case_id,split,scenario_bucket,scenario_family,difficulty,query,history_json,expected_json,review_status,review_comment
```

Train and Validation sampling must allocate at least one row to every scenario bucket and distribute remaining rows proportionally. Test must export all 200 rows. Initial `review_status` is `pending`; reviewers may change it only to `approved` or `rejected` and add a comment.

- [ ] **Step 4: Run review-export tests**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_export_review.py" -v
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit review tooling**

```powershell
git add fine-tune/intent-routing/scripts/export_review.py fine-tune/intent-routing/tests/test_export_review.py
git commit -m "feat: export stratified intent dataset reviews"
```

### Task 6: Generate the Train Source Dataset in Ten Validated Batches

**Files:**
- Create: `fine-tune/intent-routing/data/v1/source/train.jsonl`

- [ ] **Step 1: Generate train batches 01-06**

Generate 1,200 `single_intent` records, 200 for each executable intent. Give each intent at least 40 easy, 100 medium, and 60 hard examples. Use train-only scenario families prefixed `tr-`. Do not derive multiple records by changing only an entity, number, or punctuation.

- [ ] **Step 2: Validate the first 1,200 records**

Run the validator and require zero structural or domain errors before continuing. Expected partial output includes `train: 1200` and `single_intent: 1200`.

- [ ] **Step 3: Generate train batch 07**

Generate 240 `intent_boundary` records. Allocate 60 each to retrieval-vs-general, reasoning-vs-general, calculation-vs-general, and inspection-vs-reasoning boundaries. At least half must be hard negatives whose surface keywords suggest the wrong intent.

- [ ] **Step 4: Generate train batch 08**

Generate 160 clarification records. Cover missing retrieval topic, truly unresolvable stock target, missing inspection target, ambiguous calculation inputs, and ambiguous references. Do not mark a stock Chinese name or document reference as missing when current routing rules say downstream layers can resolve it.

- [ ] **Step 5: Generate train batch 09**

Generate 160 independent multi-task and 160 dependent multi-task records. Independent tasks must have empty dependencies. Dependent tasks must form an acyclic graph and include retrieval-then-reasoning, calculation-then-reasoning, and inspection-then-reasoning chains.

- [ ] **Step 6: Generate train batch 10**

Generate 80 multi-turn records with one to six history messages. Cover short follow-up answers, pronoun resolution, correction of a prior task, and completion of missing slots.

- [ ] **Step 7: Validate counts and rules**

Run:

```powershell
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
```

Expected train totals:

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

- [ ] **Step 8: Commit the train source data**

```powershell
git add fine-tune/intent-routing/data/v1/source/train.jsonl fine-tune/intent-routing/data/v1/reports/validation-report.json
git commit -m "data: add synthetic intent routing train split"
```

### Task 7: Generate Isolated Validation and Test Source Datasets

**Files:**
- Create: `fine-tune/intent-routing/data/v1/source/validation.jsonl`
- Create: `fine-tune/intent-routing/data/v1/source/test.jsonl`

- [ ] **Step 1: Generate validation records from validation-only families**

Generate exactly 200 records using scenario families prefixed `va-` and the validation bucket quotas. None may be a direct paraphrase of a train record. Single-intent records must contain 20 examples for each executable intent.

- [ ] **Step 2: Generate test records from test-only families**

Generate exactly 200 records using scenario families prefixed `te-` and the test bucket quotas. Single-intent records must contain 20 examples for each executable intent. Emphasize compositional generalization, boundary hard negatives, colloquial Chinese, typos, and unseen entity combinations.

- [ ] **Step 3: Validate all source records**

Run:

```powershell
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
```

Expected: train 2,000, validation 200, test 200, errors 0.

- [ ] **Step 4: Run leakage checks**

Run:

```powershell
python fine-tune/intent-routing/scripts/check_leakage.py --root fine-tune/intent-routing/data/v1
```

Expected: no exact cross-split duplicates, no cross-split scenario-family reuse, and no cross-split pair at or above 0.72.

- [ ] **Step 5: Resolve every leakage finding**

For each finding, remove or rewrite the entire scenario instance while preserving bucket counts. Re-run validation and leakage checks until both exit 0.

- [ ] **Step 6: Commit validation and test data**

```powershell
git add fine-tune/intent-routing/data/v1/source/validation.jsonl fine-tune/intent-routing/data/v1/source/test.jsonl fine-tune/intent-routing/data/v1/reports/validation-report.json fine-tune/intent-routing/data/v1/reports/leakage-report.json
git commit -m "data: add isolated intent validation and test splits"
```

### Task 8: Export and Complete the Human Review Gate

**Files:**
- Create: `fine-tune/intent-routing/data/v1/review/train-review.csv`
- Create: `fine-tune/intent-routing/data/v1/review/validation-review.csv`
- Create: `fine-tune/intent-routing/data/v1/review/test-review.csv`
- Modify: rejected records in `fine-tune/intent-routing/data/v1/source/*.jsonl`

- [ ] **Step 1: Export review sheets**

Run:

```powershell
python fine-tune/intent-routing/scripts/export_review.py --root fine-tune/intent-routing/data/v1 --config fine-tune/intent-routing/config/scenario-matrix-v1.json
```

Expected: 50 train rows, 50 validation rows, and all 200 test rows.

- [ ] **Step 2: Review train and validation samples**

For each selected row, verify input realism, intent, clarification decision, task split, dependency direction, executor mapping, and slots. Set every accepted row to `approved`. Set incorrect rows to `rejected` with a concrete reason.

- [ ] **Step 3: Review every test sample**

Review all 200 test rows using the same criteria. Test review must not reference model predictions; reviewers inspect only input, context, and expected output.

- [ ] **Step 4: Correct rejected source records by scenario family**

When a rejected row exposes a systematic issue, inspect and correct all records with the same scenario family. Regenerate review CSVs after corrections so they match source hashes.

- [ ] **Step 5: Re-run both gates**

Run validator and leakage checker. Expected: both exit 0, with unchanged split and bucket totals.

- [ ] **Step 6: Commit reviewed source data**

```powershell
git add fine-tune/intent-routing/data/v1/source fine-tune/intent-routing/data/v1/review fine-tune/intent-routing/data/v1/reports
git commit -m "data: complete intent routing dataset review"
```

### Task 9: Build Deterministic GLM SFT Files

**Files:**
- Create: `fine-tune/intent-routing/scripts/build_sft.py`
- Create: `fine-tune/intent-routing/tests/test_build_sft.py`
- Create: `fine-tune/intent-routing/data/v1/sft/train.jsonl`
- Create: `fine-tune/intent-routing/data/v1/sft/validation.jsonl`

- [ ] **Step 1: Write failing SFT conversion tests**

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

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_build_sft.py" -v
```

Expected: FAIL because `build_sft` does not exist.

- [ ] **Step 3: Implement deterministic conversion**

Create a concise system message that contains the six intent definitions, JSON-only requirement, clarification invariants, executor mapping, and dependency rule. Add history to the user content under a stable `Recent conversation:` section and current query under `Current request:`. Serialize completion using canonical compact JSON.

Export Train and Validation only. Preserve `caseId`, `scenarioBucket`, and `difficulty` as non-training metadata fields for diagnosis.

- [ ] **Step 4: Run SFT tests**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_build_sft.py" -v
```

Expected: 3 tests PASS.

- [ ] **Step 5: Build SFT files**

Run:

```powershell
python fine-tune/intent-routing/scripts/build_sft.py --root fine-tune/intent-routing/data/v1
```

Expected: 2,000 train SFT records and 200 validation SFT records; no test SFT file.

- [ ] **Step 6: Commit SFT artifacts**

```powershell
git add fine-tune/intent-routing/scripts/build_sft.py fine-tune/intent-routing/tests/test_build_sft.py fine-tune/intent-routing/data/v1/sft
git commit -m "feat: build GLM intent routing SFT dataset"
```

### Task 10: Freeze the Dataset Manifest and Run the Final Gate

**Files:**
- Create: `fine-tune/intent-routing/data/v1/reports/manifest.json`
- Modify: `fine-tune/intent-routing/README.md`

- [ ] **Step 1: Run the complete test suite**

Run:

```powershell
python -m unittest discover -s fine-tune/intent-routing/tests -p "test_*.py" -v
```

Expected: all validator, leakage, review, and SFT tests PASS.

- [ ] **Step 2: Run production-data validation and leakage gates**

Run:

```powershell
python fine-tune/intent-routing/scripts/validate_dataset.py --root fine-tune/intent-routing/data/v1
python fine-tune/intent-routing/scripts/check_leakage.py --root fine-tune/intent-routing/data/v1
```

Expected: both exit 0; exact split counts remain 2,000/200/200.

- [ ] **Step 3: Freeze the manifest**

Write `manifest.json` with:

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

Populate `files` with the SHA-256 of every source, SFT, review, and report file except `manifest.json` itself. Refuse to freeze if any required review row is not `approved` or if either gate fails.

- [ ] **Step 4: Document the immutable dataset version**

Update `README.md` to state that any post-freeze source change requires a new dataset version directory and a new manifest. Document that Test must never be copied into SFT files, Prompt examples, or the Few-shot vector store.

- [ ] **Step 5: Commit the frozen MVP dataset**

```powershell
git add fine-tune/intent-routing/README.md fine-tune/intent-routing/data/v1/reports/manifest.json
git commit -m "data: freeze intent routing dataset v1"
```

## Completion Criteria

This plan is complete only when:

- source counts are exactly 2,000/200/200;
- bucket quotas match `scenario-matrix-v1.json`;
- all automated tests pass;
- schema and domain validation report zero errors;
- leakage checks report zero blocking findings;
- 50 Train, 50 Validation, and all 200 Test rows are approved;
- Train and Validation SFT files exist and Test SFT does not;
- `manifest.json` contains hashes for all versioned artifacts;
- all task commits are present in Git history.

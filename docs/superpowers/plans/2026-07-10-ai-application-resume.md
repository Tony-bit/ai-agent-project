# AI Application Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Subagent execution is not used because the user did not request delegation.

**Goal:** Generate a two-page, editable Chinese AI application engineer resume in DOCX and PDF without overwriting the source PDF or inventing experience data.

**Architecture:** Use `python-docx` to generate a standards-compliant OpenXML document that WPS can edit, and ReportLab to generate a matching two-page PDF without Microsoft Office. Use PyMuPDF to verify page count, text extraction, key phrases, and rendered layout.

**Tech Stack:** Python 3, python-docx, ReportLab, PyMuPDF

---

## File map

- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/生成AI应用简历.py` — WPS-compatible DOCX construction and independent PDF export.
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/向晓彬_AI应用开发工程师_优化版.docx` — editable deliverable.
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/向晓彬_AI应用开发工程师_优化版.pdf` — shareable deliverable.
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/待补充信息清单.md` — only the facts and metric definitions that need user confirmation.
- Preserve: `D:/个人资料/向晓彬+ai应用开发工程师+4年经验.pdf` — original source, read-only.

### Task 1: Build the WPS-compatible document generator

| Task | status |
|------|------|
| Task 1: Build the WPS-compatible document generator | pass |

**Files:**
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/生成AI应用简历.py`

- [ ] **Step 1: Create the output directory and generator**

Use `apply_patch` to create a Python script that:

```python
from docx import Document
from reportlab.platypus import SimpleDocTemplate

output_dir = r'D:\个人资料\简历优化_AI应用开发工程师_20260710'
docx_path = output_dir + r'\向晓彬_AI应用开发工程师_优化版.docx'
pdf_path = output_dir + r'\向晓彬_AI应用开发工程师_优化版.pdf'
```

The script must set A4 paper, 1.45 cm left/right margins, approximately 1.2 cm top/bottom margins, Chinese font `Microsoft YaHei`, and a dark-blue single-column style in both formats. It must use an explicit page break before the second-page project section.

- [ ] **Step 2: Add verified resume content**

The script must add these sections in this order:

```text
Page 1: Name and target role; contact line; career summary; education; work experience; Agent platform project.
Page 2: Logistics project; game-ranking project; intelligent-driving project; grouped technical skills.
```

Use only facts from the source PDF. Mark these unresolved facts with light-yellow highlighting rather than supplying values:

```text
Agent project nature, team size, and ownership boundary
Agent routing accuracy / structured-output compliance / task success rate
Logistics disorder-rate reduction / recovery time / affected parcel scale
Meaning of “Redis memory reduced by 30% (12G)”
Alert coverage / discovery-time or diagnosis-time improvement for intelligent driving
```

- [ ] **Step 3: Save DOCX and build PDF**

```python
document.save(docx_path)
pdf_document.build(pdf_story, onFirstPage=draw_footer, onLaterPages=draw_footer)
```

Register an installed Chinese TrueType font for ReportLab so Chinese remains selectable text rather than rasterized content.

- [ ] **Step 4: Syntax-check the generator**

Run:

```powershell
python -m py_compile 'D:\个人资料\简历优化_AI应用开发工程师_20260710\生成AI应用简历.py'
```

Expected: exit code 0.

### Task 2: Generate the deliverables

| Task | status |
|------|------|
| Task 2: Generate the deliverables | pass |

**Files:**
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/向晓彬_AI应用开发工程师_优化版.docx`
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/向晓彬_AI应用开发工程师_优化版.pdf`

- [ ] **Step 1: Run the generator**

```powershell
python 'D:\个人资料\简历优化_AI应用开发工程师_20260710\生成AI应用简历.py'
```

Expected: exit code 0 and both output files exist with non-zero length.

- [ ] **Step 2: Confirm both files are valid containers**

```powershell
python -c "from docx import Document; import fitz; Document(r'D:\个人资料\简历优化_AI应用开发工程师_20260710\向晓彬_AI应用开发工程师_优化版.docx'); fitz.open(r'D:\个人资料\简历优化_AI应用开发工程师_20260710\向晓彬_AI应用开发工程师_优化版.pdf'); print('OPEN_OK')"
```

Expected: `OPEN_OK`.

### Task 3: Verify page count, ATS text, and visual layout

| Task | status |
|------|------|
| Task 3: Verify page count, ATS text, and visual layout | pass |

**Files:**
- Verify: `D:/个人资料/简历优化_AI应用开发工程师_20260710/向晓彬_AI应用开发工程师_优化版.pdf`

- [ ] **Step 1: Verify structure with PyMuPDF**

Run Python with `PYTHONIOENCODING=utf-8` and assert:

```python
import fitz
p = r'D:\个人资料\简历优化_AI应用开发工程师_20260710\向晓彬_AI应用开发工程师_优化版.pdf'
doc = fitz.open(p)
assert doc.page_count == 2
text = ''.join(page.get_text() for page in doc)
for phrase in ['AI 应用开发工程师', '日调用量 15 亿+', 'TP99', 'MCP', 'LoRA/SFT', '10 万辆车']:
    assert phrase in text, phrase
assert '意向岗位：后端开发工程师' not in text
print(len(text), doc.page_count)
```

Expected: assertions pass and page count is `2`.

- [ ] **Step 2: Render both pages**

Render each page at 1.6x scale to `%TEMP%/codex_resume_review_v2/page_1.png` and `page_2.png`, then inspect them with the image viewer.

Expected: no third page, clipped text, orphan heading, overlapping content, unreadably small type, or excessive blank space.

- [ ] **Step 3: Correct and re-export if needed**

If a page overflows, adjust only paragraph spacing, table cell margins, or section spacing in the generator. Preserve minimum 9 pt body type and all verified content, rerun Task 2, then repeat Task 3 Steps 1–2.

### Task 4: Create the confirmation checklist

| Task | status |
|------|------|
| Task 4: Create the confirmation checklist | pass |

**Files:**
- Create: `D:/个人资料/简历优化_AI应用开发工程师_20260710/待补充信息清单.md`

- [ ] **Step 1: Record only unresolved facts**

The checklist must ask for:

```text
1. Agent project type and individual responsibility boundary.
2. Agent data scale, evaluation method, and before/after metrics.
3. Logistics disorder-governance effect and measurement window.
4. Whether 12G is memory saved or memory remaining after optimization.
5. Intelligent-driving monitoring stack and measurable alerting effect.
6. Whether the user wants location and a GitHub/project link shown.
```

- [ ] **Step 2: Final file check**

```powershell
Get-ChildItem -LiteralPath 'D:\个人资料\简历优化_AI应用开发工程师_20260710' |
  Select-Object Name, Length, LastWriteTime
```

Expected: generator, DOCX, PDF, and checklist all exist; the source PDF remains unchanged.

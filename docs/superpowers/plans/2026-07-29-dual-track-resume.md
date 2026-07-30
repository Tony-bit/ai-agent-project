# AI 应用后端与 Java 后端双版本简历实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 基于已确认的事实口径，生成 AI 应用后端版和 Java 后端版两套两页中文简历，每套包含可编辑 DOCX 和投递用 PDF，并通过结构、ATS 文本和逐页视觉验收。

**架构：** 将事实内容集中在一个只读数据模块中，由单一 DOCX 构建器根据版本配置重排内容和强调重点；PDF 统一由 `render_docx.py --emit_pdf` 从最终 DOCX 导出，避免 DOCX/PDF 双实现产生内容漂移。独立验证脚本检查页数、关键事实、日期、链接和禁用词，渲染目录仅保存内部 QA 图片，最终只交付四个文件。

**技术栈：** Python 3、python-docx、pypdf、LibreOffice、Poppler、Documents skill `render_docx.py`

---

## 文件结构

- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/resume_content.py` — 两个版本共享的事实数据和版本编排配置。
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/test_resume_content.py` — 事实口径与版本差异的自动化测试。
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/build_resumes.py` — DOCX 样式系统、超链接、分页和双版本生成器。
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/verify_resumes.py` — DOCX/PDF 结构、页数、文本和链接校验。
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-ai/` — AI 版内部渲染与 QA 文件。
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-java/` — Java 版内部渲染与 QA 文件。
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/向晓彬_AI应用开发工程师_3.5年.docx`
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/向晓彬_AI应用开发工程师_3.5年.pdf`
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/向晓彬_Java后端开发工程师_3.5年.docx`
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/向晓彬_Java后端开发工程师_3.5年.pdf`

## 样式令牌

采用 `compact_reference_guide`，并定义命名覆盖 `resume_a4_compact`：

```python
TOKENS = {
    "page_width_mm": 210,
    "page_height_mm": 297,
    "margin_top_mm": 12,
    "margin_right_mm": 15,
    "margin_bottom_mm": 12,
    "margin_left_mm": 15,
    "font_cn": "Microsoft YaHei",
    "font_en": "Arial",
    "body_size_pt": 9.6,
    "body_line_spacing": 1.08,
    "body_after_pt": 1.5,
    "name_size_pt": 19,
    "target_size_pt": 11,
    "metadata_size_pt": 9.2,
    "section_size_pt": 11.5,
    "entry_title_size_pt": 10.2,
    "accent": "243B6B",
    "text": "262A31",
    "muted": "5D6570",
    "rule": "D9DEE8",
    "bullet_marker_in": 0.14,
    "bullet_text_in": 0.29,
    "bullet_hanging_in": 0.15,
}
```

文档使用真实 Word 样式和真实项目符号编号定义；职位/项目名称与日期通过段落右对齐制表位排版，不用表格模拟普通文本布局。姓名标题块采用简洁左对齐样式，不使用运行页眉、装饰图标、大面积灰色条或页脚页码。

### 任务 1：建立事实数据与口径测试

| 任务 | status |
|------|------|
| 任务 1：建立事实数据与口径测试 | pass |

**文件：**
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/test_resume_content.py`
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/resume_content.py`

- [ ] **步骤 1：创建工作目录和输出目录**

运行：

```powershell
New-Item -ItemType Directory -Force -Path 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume'
New-Item -ItemType Directory -Force -Path 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs'
```

预期：两个目录存在，不修改原始 PDF。

- [ ] **步骤 2：先编写事实口径测试**

测试必须覆盖：

```python
import unittest
from resume_content import COMMON, VARIANTS


class ResumeContentTest(unittest.TestCase):
    def test_shopee_end_month_is_september(self):
        self.assertEqual(COMMON["employment"][0]["period"], "2025.06-2025.09")

    def test_redis_metric_has_unambiguous_saved_memory(self):
        text = " ".join(COMMON["projects"]["ranking"]["bullets"])
        self.assertIn("节省约 12GB", text)
        self.assertNotIn("30%（12GB）", text)

    def test_agent_metrics_are_preserved(self):
        text = " ".join(COMMON["projects"]["agent"]["bullets"])
        for value in ("2,400", "76.5%", "94%", "68%", "98%", "43.5%", "20.20", "13.62"):
            self.assertIn(value, text)

    def test_variants_have_distinct_targets(self):
        self.assertEqual(VARIANTS["ai"]["target"], "AI 应用开发工程师（Java）")
        self.assertEqual(VARIANTS["java"]["target"], "Java 后端开发工程师")

    def test_repository_link_is_not_present(self):
        self.assertNotIn("github", repr(COMMON).lower())
        self.assertNotIn("gitcode", repr(COMMON).lower())


if __name__ == "__main__":
    unittest.main()
```

- [ ] **步骤 3：运行测试确认失败**

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -m unittest 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/test_resume_content.py' -v
```

预期：FAIL，原因为 `resume_content` 尚不存在。

- [ ] **步骤 4：创建共享事实数据**

`resume_content.py` 必须定义：

```python
COMMON = {
    "name": "向晓彬",
    "phone": "18826135421",
    "email": "583915955@qq.com",
    "degree": "硕士",
    "experience": "3.5 年互联网后端经验",
    "demo_url": "http://1.12.53.53:8080/",
    "education": [
        {"school": "哈尔滨工业大学（深圳）", "degree": "硕士", "period": "2019.09-2022.03"},
        {"school": "广东工业大学", "degree": "本科", "period": "2014.06-2018.06"},
    ],
    "employment": [
        {
            "company": "SHOPEE 物流网络有限公司",
            "role": "后端开发工程师",
            "period": "2025.06-2025.09",
            "summary": "参与物流容器微服务建设，负责包裹状态流转与消息乱序治理，支撑驿站、站内分拣、Linehaul（干线）和骑手派送等履约场景。",
        },
        {
            "company": "华为技术有限公司",
            "role": "后端开发工程师",
            "period": "2022.05-2025.06",
            "summary": "负责游戏榜单与推广内容配置服务、智能驾驶云车辆管理及接入服务的迭代、性能与稳定性建设，榜单链路日调用量 15 亿+。",
        },
    ],
    "projects": {
        "agent": {
            "name": "多智能体投研与任务执行平台（个人项目）",
            "period": "2026.01-至今",
            "tech": "Spring Boot 3、Spring AI Alibaba、Java 17、MCP/Skills、SSE、Mem0、PgVector、Langfuse、LoRA/SFT",
            "bullets": [
                "搭建统一 Agent 入口，通过结构化输出完成普通问答、复杂任务和股票分析的意图路由、任务拆解与槽位抽取；设计阶段化 Trading Pipeline 编排分析、辩论、风险评审和报告节点，通过 SSE 输出进度，并以 Provider 与 MCP/Skills 接入外部数据源。",
                "建设错误分类、指数退避、最大重试、上下文压缩、流式降级、会话记忆与 Langfuse Trace；默认测试链路覆盖路由、重试、状态流和会话边界。",
                "构建 2,400 条 SFT 数据集并完成 GLM-4-9B-Chat 两阶段 LoRA/SFT；在固定 200 条测试集上将意图序列准确率从 76.5% 提升至 94%、Schema 合法率从 68% 提升至 98%、严格结构完全匹配率从 0 提升至 43.5%，P95 推理耗时从 20.20 秒降至 13.62 秒。",
            ],
        },
        "ranking": {
            "name": "用户增长榜单微服务",
            "period": "2023.06-2025.06",
            "tech": "Spring Boot、Redis、Kafka、MySQL、Elasticsearch、RPC、AI Agent、MCP",
            "bullets": [
                "主导配置管理模块的 DDD 与版本化重构，将配置耗时降低 40%、代码量减少 30%。",
                "通过线程池并行、异步处理和链路优化，将用户接口 TP99 从 100ms 降至 60ms；优化缓存热加载，将 Redis 内存占用降低 30%，节省约 12GB。",
                "建设智能巡检 Agent 与 MCP 动态配置工具，空榜单问题识别召回率达到 90%、成功率达到 95%，配置问题定位时间降低 80%。",
            ],
        },
        "logistics": {
            "name": "物流供应链容器微服务",
            "period": "2025.06-2025.09",
            "tech": "Go、MySQL、Elasticsearch、Redis、Kafka、RPC",
            "bullets": [
                "负责消息乱序场景治理，设计并实现“失败消息落库 + 有序重消费”机制，按包裹维度串行重放事件，保障状态机顺序推进。",
            ],
        },
        "driving": {
            "name": "智能驾驶云基础服务",
            "period": "2022.05-2023.06",
            "tech": "Spring Boot、Redis、Kafka、MySQL",
            "bullets": [
                "面向智驾连云通道提供车辆信息、配置、接入、打点数据上传和地图文件下载能力，支撑 10 万辆车实时在线。",
                "主导搭建智能驾驶云告警监控体系，覆盖车辆管理、接入与文件传输等基础服务。",
            ],
        },
    },
}

VARIANTS = {
    "ai": {
        "target": "AI 应用开发工程师（Java）",
        "summary": "3.5 年头部互联网后端经验，具备 Java/Go 服务开发、性能与稳定性建设能力；持续实践 Spring AI 多 Agent 编排、工具调用、模型容错、记忆、可观测与 LoRA/SFT 评测。",
        "page1_projects": ["agent"],
        "page2_projects": ["ranking", "logistics", "driving"],
        "agent_bullets": 3,
    },
    "java": {
        "target": "Java 后端开发工程师",
        "summary": "3.5 年头部互联网后端经验，具备 Java/Go 服务开发、大流量链路优化、缓存与消息治理、线上稳定性建设能力，并有完整 AI 应用后端项目实践。",
        "page1_projects": ["ranking"],
        "page2_projects": ["logistics", "driving", "agent"],
        "agent_bullets": 2,
    },
}
```

- [ ] **步骤 5：运行事实口径测试确认通过**

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' -m unittest 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/test_resume_content.py' -v
```

预期：5 个测试全部 PASS。

### 任务 2：实现可复用 DOCX 构建器

| 任务 | status |
|------|------|
| 任务 2：实现可复用 DOCX 构建器 | pass |

**文件：**
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/build_resumes.py`

- [ ] **步骤 1：建立样式和页面几何**

构建器必须：

```python
from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_TAB_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Mm, Pt, RGBColor, Inches


def configure_page(doc):
    section = doc.sections[0]
    section.page_width = Mm(210)
    section.page_height = Mm(297)
    section.top_margin = Mm(12)
    section.right_margin = Mm(15)
    section.bottom_margin = Mm(12)
    section.left_margin = Mm(15)
    section.header_distance = Mm(5)
    section.footer_distance = Mm(5)
```

为 `Normal`、`ResumeSection`、`ResumeEntry`、`ResumeMeta`、`ResumeSummary`、`ResumeBullet` 建立显式字体、字号、颜色、段前段后和行距。所有中文 run 同时写入 `w:eastAsia="Microsoft YaHei"`，英文写入 Arial。

- [ ] **步骤 2：实现真实超链接和页首块**

实现 `add_hyperlink(paragraph, text, url)`，通过 `document.part.relate_to(url, RT.HYPERLINK, is_external=True)` 创建真实关系；页首显示姓名、目标岗位、电话、邮箱、学历、经验和“在线演示”。不使用图片、文本框或标题表格。

- [ ] **步骤 3：实现章节、条目和项目符号**

实现 `add_section_heading(doc, text)`、`add_entry_heading(doc, left_text, right_text)`、`add_summary(doc, text)`、`add_bullet(doc, text, bold_prefix=None)`、`add_project(doc, project, bullet_limit=None)`、`add_employment(doc, item)`、`add_education(doc, items)` 和 `add_skills(doc, variant)`。每个函数只负责一种内容单元，并统一调用字体和段落样式辅助函数。

`add_entry_heading` 使用右对齐制表位显示日期。`ResumeBullet` 使用真实编号定义，文本缩进 `0.29in`，悬挂 `0.15in`，段后 `1.5pt`，行距 `1.08`。项目标题、技术栈与要点启用 `keep_with_next` 或 `keep_together`，避免标题孤立和项目跨页。

- [ ] **步骤 4：实现双版本固定分页**

AI 版第一页顺序：页首、职业摘要、工作经历、Agent 项目；显式分页后依次为榜单、物流、智驾、技能、教育。

Java 版第一页顺序：页首、职业摘要、工作经历、榜单项目；显式分页后依次为物流、智驾、Agent 精简版、技能、教育。

Agent 精简版只取前两个工程要点；LoRA/SFT 仍在 AI 版完整保留。

- [ ] **步骤 5：生成两个 DOCX**

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/build_resumes.py'
```

预期：`outputs/` 下生成两个非空 DOCX，原始 PDF 未变化。

### 任务 3：执行 DOCX 结构和内容验证

| 任务 | status |
|------|------|
| 任务 3：执行 DOCX 结构和内容验证 | pass |

**文件：**
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/verify_resumes.py`

- [ ] **步骤 1：编写 DOCX 验证逻辑**

验证脚本必须使用 `python-docx` 和 `zipfile`：

```python
from docx import Document
from zipfile import ZipFile


def docx_text(path):
    doc = Document(path)
    return "\n".join(p.text for p in doc.paragraphs)


def assert_docx(path, target, must_have, must_not_have):
    text = docx_text(path)
    assert target in text
    assert "2025.06-2025.09" in text
    assert "2025.06-2025.10" not in text
    for value in must_have:
        assert value in text, value
    for value in must_not_have:
        assert value not in text, value
    with ZipFile(path) as zf:
        rels = zf.read("word/_rels/document.xml.rels").decode("utf-8")
        assert "http://1.12.53.53:8080/" in rels
```

AI 版必须包含 LoRA/SFT 全部指标；Java 版必须包含 `15 亿+`、TP99、`节省约 12GB`、消息乱序和告警监控。两个版本均不得出现 GitHub/GitCode 链接、占位符或 `2025.10`。

- [ ] **步骤 2：运行 DOCX 验证**

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/verify_resumes.py' --docx-only
```

预期：输出 `DOCX_OK ai` 和 `DOCX_OK java`。

### 任务 4：渲染 DOCX 并导出 PDF

| 任务 | status |
|------|------|
| 任务 4：渲染 DOCX 并导出 PDF | pass |

**文件：**
- 读取：`C:/Users/Denny/.codex/plugins/cache/openai-primary-runtime/documents/26.727.11326/skills/documents/render_docx.py`
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-ai/`
- 创建：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-java/`

- [ ] **步骤 1：渲染 AI 版并导出 PDF**

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'C:/Users/Denny/.codex/plugins/cache/openai-primary-runtime/documents/26.727.11326/skills/documents/render_docx.py' 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/向晓彬_AI应用开发工程师_3.5年.docx' --output_dir 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-ai' --emit_pdf
```

预期：生成两张 `page-*.png` 和一个 PDF。

- [ ] **步骤 2：渲染 Java 版并导出 PDF**

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'C:/Users/Denny/.codex/plugins/cache/openai-primary-runtime/documents/26.727.11326/skills/documents/render_docx.py' 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/向晓彬_Java后端开发工程师_3.5年.docx' --output_dir 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-java' --emit_pdf
```

预期：生成两张 `page-*.png` 和一个 PDF。

- [ ] **步骤 3：复制渲染 PDF 到输出目录**

从两个渲染目录复制同名 PDF 到 `outputs/`，覆盖任何旧生成结果。PDF 必须来自最终 DOCX 渲染，不允许使用另一套 ReportLab 内容实现。

### 任务 5：逐页视觉检查并迭代

| 任务 | status |
|------|------|
| 任务 5：逐页视觉检查并迭代 | pass |

**文件：**
- 检查：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-ai/page-1.png`
- 检查：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-ai/page-2.png`
- 检查：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-java/page-1.png`
- 检查：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/render-java/page-2.png`

- [ ] **步骤 1：检查四张页面图片**

逐页确认：无文字裁切、重叠、缺字、孤立标题、项目跨页、链接越界、过小字号、第一页过密或第二页大面积留白。

- [ ] **步骤 2：仅调整命名样式令牌或文案长度**

若布局失败，按以下顺序修复：

1. 缩短重复文案。
2. 调整项目间距和段后间距。
3. 调整页边距不超过 `1mm`。
4. 最后才将正文从 `9.6pt` 降至最低 `9.3pt`。

不得删除核心指标，不得新增第三页，不得把正文压到低于 `9.3pt`。

- [ ] **步骤 3：每次修改后重新生成、渲染并检查全部四页**

预期：最终一次渲染的四页全部通过视觉检查。

### 任务 6：执行最终 PDF、ATS 与交付审计

| 任务 | status |
|------|------|
| 任务 6：执行最终 PDF、ATS 与交付审计 | pass |

**文件：**
- 验证：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/*.docx`
- 验证：`C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs/*.pdf`

- [ ] **步骤 1：运行完整验证脚本**

`verify_resumes.py` 使用 `pypdf.PdfReader` 验证：

```python
from pypdf import PdfReader


def assert_pdf(path, must_have):
    reader = PdfReader(path)
    assert len(reader.pages) == 2
    text = "\n".join(page.extract_text() or "" for page in reader.pages)
    for value in must_have:
        assert value in text, value
    links = [
        annot.get_object().get("/A", {}).get("/URI")
        for page in reader.pages
        for annot in (page.get("/Annots") or [])
    ]
    assert "http://1.12.53.53:8080/" in links
```

运行：

```powershell
& 'C:/Users/Denny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/work/resume/verify_resumes.py'
```

预期：输出 `ALL_RESUMES_OK`。

- [ ] **步骤 2：检查最终目录只含四个交付文件**

运行：

```powershell
Get-ChildItem 'C:/Users/Denny/Documents/Codex/2026-07-29/new-chat-4/outputs' | Select-Object Name,Length,LastWriteTime
```

预期：四个文件均非空，不包含渲染 PNG、临时脚本或 QA 报告。

- [ ] **步骤 3：确认原始 PDF 未修改**

运行：

```powershell
Get-Item -LiteralPath 'D:/个人资料/向晓彬-ai应用开发-3.5年开发经验.pdf' | Select-Object Length,LastWriteTime
```

预期：长度仍为 `306422` 字节，修改时间仍为 `2026/7/16 15:35:29`。

- [ ] **步骤 4：交付**

最终响应只提供四个输出文件，不提供 `work/resume/` 下的脚本、渲染图片或中间 PDF；每个 DOCX 按 Documents skill 要求使用一次输出文件引用，PDF 使用普通输出链接。

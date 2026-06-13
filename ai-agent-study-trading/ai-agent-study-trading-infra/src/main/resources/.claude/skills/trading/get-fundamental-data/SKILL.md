---
name: get-fundamental-data
description: |
  获取 A 股股票的基本面数据，包括估值、盈利能力、增长、现金流和偿债能力。
  适用场景：需要做价值分析、财务健康度分析或股票对比时使用。
  适合中长期分析。
---

# 获取基本面数据

## 工具信息
- **Skill Name**: `get-fundamental-data`
- **Tool Name**: `get_fundamental_data`
- **Implementation**: ToolCallback (`TradingToolCallbacks`)

## 功能说明
返回股票的估值和财务指标，用于判断公司盈利质量、成长性和估值是否合理。

## 输入参数
| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| ticker | string | 是 | 6 位股票代码 | "600000" |

## 返回格式
Markdown 文本，包含 PE、PB、PS、PEG、ROE、毛利率、净利率、营收、净利润、EPS 等数据。

## 使用场景
1. 基本面分析师进行财务与估值判断。
2. 用户希望了解公司是否值得长期跟踪或持有。

## 注意事项
- 基本面指标更适合中长期视角。
- 建议结合行业、新闻和技术面一起综合判断。

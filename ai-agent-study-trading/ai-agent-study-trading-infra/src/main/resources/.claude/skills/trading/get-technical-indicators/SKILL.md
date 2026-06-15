---
name: get-technical-indicators
description: |
  获取 A 股股票的技术指标，包括均线、MACD、RSI、KDJ、布林带、ATR 和 ADX。
  适用场景：需要做技术面分析、判断趋势强弱或寻找买卖点时使用。
  技术指标解释要结合行情上下文。
  如果用户只提供股票中文名或简称，必须先使用 search-stock-by-name 获取 ticker。
---

# 获取技术指标

## 工具信息
- **Skill Name**: `get-technical-indicators`
- **Tool Name**: `get_technical_indicators`
- **Implementation**: ToolCallback (`TradingToolCallbacks`)

## 功能说明
返回指定股票在时间区间内的多类技术指标，适合判断趋势方向、超买超卖状态以及波动风险。

## 输入参数
| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| ticker | string | 是 | 6 位股票代码 | "000001" |
| startDate | string | 是 | 开始日期 | "2024-01-01" |
| endDate | string | 是 | 结束日期 | "2024-12-31" |

## 返回格式
Markdown 文本，包含 MA、MACD、RSI、KDJ、布林带、ATR、ADX 等指标。

## 使用场景
1. 技术分析师输出趋势判断。
2. 用户明确要求看均线、MACD、RSI 等指标。

## 注意事项
- 若用户只给公司名、简称或自然语言提到股票但没有 6 位代码，应先使用 `search-stock-by-name` 获取 `ticker`；唯一匹配时再调用本工具，多个候选时进入澄清或消歧。
- RSI、ADX 等阈值要结合具体市场环境解释。
- 指标是辅助判断，不应脱离价格趋势孤立使用。

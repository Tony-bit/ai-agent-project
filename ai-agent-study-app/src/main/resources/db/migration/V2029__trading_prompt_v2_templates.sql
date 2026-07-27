-- V2 templates use named placeholders and remain inactive until the complete
-- 6002~6013 schema set is validated and transactionally activated.
INSERT INTO ai_client_system_prompt
    (prompt_id, prompt_name, prompt_content, description, status,
     prompt_type, version, change_desc, create_time, update_time)
VALUES
('6002', 'Fundamental Analyst V2',
'{{targetContext}}

分析以下基本面原始数据，只能引用输入中存在的精确指标：
{{stockData}}

严格输出以下契约，不得输出契约外文本：
{{outputContract}}',
'Target-locked structured fundamental analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6003', 'Technical Analyst V2',
'{{targetContext}}

分析以下技术指标，不得修改或补充输入中的价格与指标数值：
{{stockData}}

严格输出以下契约，不得输出契约外文本：
{{outputContract}}',
'Target-locked structured technical analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6004', 'Sentiment Analyst V2',
'{{targetContext}}

分析以下情绪数据，不得引用其他股票的市场记忆：
{{stockData}}

严格输出以下契约，不得输出契约外文本：
{{outputContract}}',
'Target-locked structured sentiment analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6005', 'News Analyst V2',
'{{targetContext}}

仅根据以下新闻输入归并事件。相关公司只有在输入明确出现时才可引用：
{{stockData}}

严格输出以下契约，不得输出契约外文本：
{{outputContract}}',
'Target-locked structured news analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6006', 'Bull Researcher V2',
'{{targetContext}}

基于已校验分析报告和已有辩论历史提出多头论点，不得增加新公司事实或精确数值：
{{analystReports}}
{{debateHistory}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured bull research', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6007', 'Bear Researcher V2',
'{{targetContext}}

基于已校验分析报告和已有辩论历史提出空头论点，不得增加新公司事实或精确数值：
{{analystReports}}
{{debateHistory}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured bear research', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6008', 'Research Manager V2',
'{{targetContext}}

综合已校验分析报告、辩论历史和校验状态。重复引用同一输入不构成多源验证：
{{analystReports}}
{{debateHistory}}
{{validationStatus}}
当前轮次：{{currentRound}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured research management', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6009', 'Portfolio Manager V2',
'{{targetContext}}

仅消费以下已校验结果；存在无效上游状态时不得生成 BUY/SELL：
{{analystReports}}
{{debateHistory}}
{{riskReports}}
{{validationStatus}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured portfolio decision', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6010', 'Neutral Risk Analyst V2',
'{{targetContext}}

以中性风险偏好评估以下已校验投资计划和风险历史：
{{investmentPlan}}
{{riskReports}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured neutral risk analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6011', 'Conservative Risk Analyst V2',
'{{targetContext}}

以保守风险偏好评估以下已校验投资计划和风险历史：
{{investmentPlan}}
{{riskReports}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured conservative risk analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6012', 'Aggressive Risk Analyst V2',
'{{targetContext}}

以激进风险偏好评估以下已校验投资计划和风险历史：
{{investmentPlan}}
{{riskReports}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured aggressive risk analysis', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW()),
('6013', 'Recommendation V2',
'{{targetContext}}

基于以下已校验分析与辩论结果生成投资计划；无效上游不得生成仓位或价格：
{{analystReports}}
{{debateHistory}}
{{validationStatus}}

严格输出以下契约：
{{outputContract}}',
'Target-locked structured recommendation', 0, 2, 2,
'Named placeholders and structured output contract', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    prompt_name = VALUES(prompt_name),
    prompt_content = VALUES(prompt_content),
    description = VALUES(description),
    status = 0,
    change_desc = VALUES(change_desc),
    update_time = NOW();

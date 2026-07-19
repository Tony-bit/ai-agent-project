-- Financial intent Few-shot migration. Safe to rerun.
START TRANSACTION;

CREATE TABLE IF NOT EXISTS `intent_fewshot_sample_snapshot_20260716`
LIKE `intent_fewshot_sample`;

INSERT IGNORE INTO `intent_fewshot_sample_snapshot_20260716`
SELECT * FROM `intent_fewshot_sample` WHERE `status` = 1;

-- Depth-ambiguous legacy samples must stop teaching the old STOCK_ANALYSIS boundary.
UPDATE `intent_fewshot_sample`
SET `intent_code` = 'AMBIGUOUS',
    `example_json` = JSON_OBJECT(
        'multiTask', FALSE,
        'needsClarification', TRUE,
        'missingInfo', JSON_ARRAY('analysisDepth'),
        'clarificationPrompt', '你需要快速了解，还是进行完整投资分析？',
        'reasoning', '用户未明确查询深度',
        'taskList', JSON_ARRAY()),
    `update_time` = NOW()
WHERE `status` = 1
  AND `intent_code` = 'STOCK_ANALYSIS'
  AND `query_text` REGEXP '最近怎么样|帮我看看|分析一下|看看.{0,20}(股票|茅台|五粮液|宁德时代|比亚迪)'
  AND `query_text` NOT REGEXP '买入|卖出|持有|投资价值|值得投资|仓位|目标价|止损|完整投资分析|深度投资分析|抄底';

-- Objective financial queries move to FINANCIAL_GENERAL.
UPDATE `intent_fewshot_sample`
SET `intent_code` = 'FINANCIAL_GENERAL',
    `example_json` = JSON_OBJECT(
        'multiTask', FALSE,
        'needsClarification', FALSE,
        'missingInfo', JSON_ARRAY(),
        'clarificationPrompt', '',
        'reasoning', '客观金融查询',
        'taskList', JSON_ARRAY(JSON_OBJECT(
            'taskId', 'sub-1',
            'taskIndex', 1,
            'totalTasks', 1,
            'content', `query_text`,
            'intent', 'FINANCIAL_GENERAL',
            'confidence', 'HIGH',
            'dependsOn', JSON_ARRAY(),
            'slots', JSON_OBJECT()))),
    `update_time` = NOW()
WHERE `status` = 1
  AND `intent_code` = 'STOCK_ANALYSIS'
  AND `query_text` REGEXP '什么是|股价|行情|市盈率|市净率|财报|公告|新闻|估值指标|技术指标|K线|走势'
  AND `query_text` NOT REGEXP '买入|卖出|持有|投资价值|值得投资|仓位|目标价|止损|完整投资分析|深度投资分析|抄底';

-- Remaining legacy stock samples without an explicit decision target are disabled for manual review.
UPDATE `intent_fewshot_sample`
SET `status` = 0, `update_time` = NOW()
WHERE `status` = 1
  AND `intent_code` = 'STOCK_ANALYSIS'
  AND `query_text` NOT REGEXP '买入|卖出|持有|投资价值|值得投资|仓位|目标价|止损|完整投资分析|深度投资分析|抄底';

DROP TEMPORARY TABLE IF EXISTS `financial_intent_seed_20260716`;
CREATE TEMPORARY TABLE `financial_intent_seed_20260716` (
    `query_text` VARCHAR(1024) NOT NULL PRIMARY KEY,
    `intent_code` VARCHAR(64) NOT NULL,
    `example_json` TEXT NULL
);

INSERT INTO `financial_intent_seed_20260716` (`query_text`, `intent_code`) VALUES
-- FINANCIAL_GENERAL: 12
('什么是市盈率？', 'FINANCIAL_GENERAL'),
('查一下贵州茅台现在的股价', 'FINANCIAL_GENERAL'),
('贵州茅台当前市盈率和市净率是多少', 'FINANCIAL_GENERAL'),
('总结宁德时代最近一期财报', 'FINANCIAL_GENERAL'),
('宁德时代财报反映了哪些经营变化', 'FINANCIAL_GENERAL'),
('最近有哪些影响新能源板块的新闻', 'FINANCIAL_GENERAL'),
('帮我找一下比亚迪最新公告', 'FINANCIAL_GENERAL'),
('解释一下基金净值和累计净值的区别', 'FINANCIAL_GENERAL'),
('上证指数今天涨了多少', 'FINANCIAL_GENERAL'),
('不需要投资建议，只告诉我茅台的估值指标', 'FINANCIAL_GENERAL'),
('看看 600519 的 K 线和技术指标，不要买卖建议', 'FINANCIAL_GENERAL'),
('茅台市盈律多少', 'FINANCIAL_GENERAL'),
-- STOCK_ANALYSIS: 10
('贵州茅台现在是否值得买入', 'STOCK_ANALYSIS'),
('贵州茅台和五粮液哪个更值得投资', 'STOCK_ANALYSIS'),
('我持有宁德时代，应该继续持有还是卖出', 'STOCK_ANALYSIS'),
('给出比亚迪的仓位建议', 'STOCK_ANALYSIS'),
('贵州茅台的目标价和止损位怎么设', 'STOCK_ANALYSIS'),
('对腾讯控股做一次完整投资分析', 'STOCK_ANALYSIS'),
('结合财报判断宁德时代是否值得长期持有', 'STOCK_ANALYSIS'),
('现在是不是抄底新能源 ETF 的好时机', 'STOCK_ANALYSIS'),
('帮我评估五粮液的投资价值和主要风险', 'STOCK_ANALYSIS'),
('茅台现在能不能买，打算拿三年', 'STOCK_ANALYSIS'),
-- AMBIGUOUS analysisDepth: 8
('贵州茅台最近怎么样', 'AMBIGUOUS'),
('帮我看看宁德时代', 'AMBIGUOUS'),
('分析一下比亚迪', 'AMBIGUOUS'),
('腾讯控股最近咋样', 'AMBIGUOUS'),
('看看 600519', 'AMBIGUOUS'),
('这个股票最近怎么样', 'AMBIGUOUS'),
('那五粮液呢', 'AMBIGUOUS'),
('帮我分西一下茅台', 'AMBIGUOUS');

UPDATE `financial_intent_seed_20260716`
SET `example_json` = CASE
    WHEN `intent_code` = 'AMBIGUOUS' THEN JSON_OBJECT(
        'multiTask', FALSE,
        'needsClarification', TRUE,
        'missingInfo', JSON_ARRAY('analysisDepth'),
        'clarificationPrompt', '你需要快速了解，还是进行完整投资分析？',
        'reasoning', '用户未明确查询深度',
        'taskList', JSON_ARRAY())
    ELSE JSON_OBJECT(
        'multiTask', FALSE,
        'needsClarification', FALSE,
        'missingInfo', JSON_ARRAY(),
        'clarificationPrompt', '',
        'reasoning', IF(`intent_code` = 'FINANCIAL_GENERAL', '客观金融查询', '明确投资决策'),
        'taskList', JSON_ARRAY(JSON_OBJECT(
            'taskId', 'sub-1',
            'taskIndex', 1,
            'totalTasks', 1,
            'content', `query_text`,
            'intent', `intent_code`,
            'confidence', 'HIGH',
            'dependsOn', JSON_ARRAY(),
            'slots', JSON_OBJECT())))
END;

INSERT INTO `intent_fewshot_sample`
    (`query_text`, `intent_code`, `example_json`, `status`, `create_time`, `update_time`)
SELECT seed.`query_text`, seed.`intent_code`, seed.`example_json`, 1, NOW(), NOW()
FROM `financial_intent_seed_20260716` seed
WHERE NOT EXISTS (
    SELECT 1 FROM `intent_fewshot_sample` existing
    WHERE existing.`query_text` = seed.`query_text`
);

COMMIT;

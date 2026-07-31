-- Migrate model runtime config to the composite JSON structure and enable
-- full-query retry for structured SSE timeouts.
--
-- Manual execution only. This file is not a Flyway migration.
-- Expected target database: ai-agent-station (MySQL 8.x).
-- Expected affected model_ids: 2001, 2003, 2007, 2009.
-- Models with NULL/blank ext_param are intentionally not initialized here.

SET @expected_model_count = 4;

-- 1. Preflight snapshot. Save this result before executing the transaction.
SELECT model_id, model_name, status, ext_param
FROM ai_client_model
WHERE model_id IN ('2001', '2003', '2007', '2009')
ORDER BY model_id;

-- Continue only when source_model_count is exactly 4.
SELECT @source_model_count := COUNT(*) AS source_model_count
FROM ai_client_model
WHERE model_id IN ('2001', '2003', '2007', '2009')
  AND JSON_VALID(ext_param) = 1
  AND COALESCE(
        JSON_EXTRACT(ext_param, '$.retryConfig.enabled'),
        JSON_EXTRACT(ext_param, '$.enabled')
      ) IS NOT NULL
  AND COALESCE(
        JSON_EXTRACT(ext_param, '$.retryConfig.maxAttempts'),
        JSON_EXTRACT(ext_param, '$.maxAttempts')
      ) IS NOT NULL
  AND COALESCE(
        JSON_EXTRACT(ext_param, '$.retryConfig.initialIntervalMs'),
        JSON_EXTRACT(ext_param, '$.initialIntervalMs')
      ) IS NOT NULL
  AND COALESCE(
        JSON_EXTRACT(ext_param, '$.retryConfig.multiplier'),
        JSON_EXTRACT(ext_param, '$.multiplier')
      ) IS NOT NULL
  AND COALESCE(
        JSON_EXTRACT(ext_param, '$.retryConfig.maxIntervalMs'),
        JSON_EXTRACT(ext_param, '$.maxIntervalMs')
      ) IS NOT NULL;

START TRANSACTION;

-- 2. Build one canonical composite document for all configured models.
-- The expression is idempotent and supports either the current flat source
-- or an already nested retryConfig. Existing compression/timeout overrides
-- are preserved. retryOnStreamTimeout is enabled for every target model.
UPDATE ai_client_model
SET ext_param = JSON_PRETTY(
        JSON_OBJECT(
          'retryConfig', JSON_OBJECT(
            'enabled', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.enabled'),
              JSON_EXTRACT(ext_param, '$.enabled')
            ),
            'maxAttempts', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.maxAttempts'),
              JSON_EXTRACT(ext_param, '$.maxAttempts')
            ),
            'initialIntervalMs', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.initialIntervalMs'),
              JSON_EXTRACT(ext_param, '$.initialIntervalMs')
            ),
            'multiplier', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.multiplier'),
              JSON_EXTRACT(ext_param, '$.multiplier')
            ),
            'maxIntervalMs', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.maxIntervalMs'),
              JSON_EXTRACT(ext_param, '$.maxIntervalMs')
            ),
            'retryOnStreamTimeout', TRUE,
            'retryableErrorCodes', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.retryableErrorCodes'),
              JSON_EXTRACT(ext_param, '$.retryableErrorCodes'),
              JSON_ARRAY()
            ),
            'nonRetryableErrorCodes', COALESCE(
              JSON_EXTRACT(ext_param, '$.retryConfig.nonRetryableErrorCodes'),
              JSON_EXTRACT(ext_param, '$.nonRetryableErrorCodes'),
              JSON_ARRAY()
            )
          ),
          'compressionConfig', COALESCE(
            JSON_EXTRACT(ext_param, '$.compressionConfig'),
            JSON_OBJECT()
          ),
          'streamingTimeout', COALESCE(
            JSON_EXTRACT(ext_param, '$.streamingTimeout'),
            JSON_OBJECT()
          )
        )
      ),
    update_time = NOW()
WHERE @source_model_count = @expected_model_count
  AND model_id IN ('2001', '2003', '2007', '2009')
  AND JSON_VALID(ext_param) = 1;

SELECT ROW_COUNT() AS updated_model_count;

COMMIT;

-- 3. Acceptance. The first query must return configured_model_count=4,
-- enabled_model_count=4, and invalid_structure_count=0.
SELECT COUNT(*) AS configured_model_count,
       SUM(JSON_EXTRACT(ext_param, '$.retryConfig.enabled') = TRUE) AS retry_enabled_model_count,
       SUM(JSON_EXTRACT(ext_param, '$.retryConfig.retryOnStreamTimeout') = TRUE) AS enabled_model_count,
       SUM(
         JSON_EXTRACT(ext_param, '$.retryConfig') IS NULL
         OR JSON_EXTRACT(ext_param, '$.compressionConfig') IS NULL
         OR JSON_EXTRACT(ext_param, '$.streamingTimeout') IS NULL
         OR JSON_EXTRACT(ext_param, '$.enabled') IS NOT NULL
       ) AS invalid_structure_count
FROM ai_client_model
WHERE model_id IN ('2001', '2003', '2007', '2009');

-- Review the final documents. Models 2003 and 2007 must retain their
-- compressionConfig values; all four retryConfig objects must set the new
-- switch to true.
SELECT model_id,
       JSON_EXTRACT(ext_param, '$.retryConfig') AS retry_config,
       JSON_EXTRACT(ext_param, '$.compressionConfig') AS compression_config,
       JSON_EXTRACT(ext_param, '$.streamingTimeout') AS streaming_timeout
FROM ai_client_model
WHERE model_id IN ('2001', '2003', '2007', '2009')
ORDER BY model_id;

-- Explicitly confirm that models without source retry config remain untouched.
SELECT model_id, model_name, ext_param
FROM ai_client_model
WHERE model_id IN ('2002', '2004', '2005', '2006', '2008')
ORDER BY model_id;

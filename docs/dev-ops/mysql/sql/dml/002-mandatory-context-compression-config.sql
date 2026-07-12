-- Mandatory context compression configuration
-- MySQL 8.x
-- Review and replace these environment-specific IDs before execution.
SET @compression_agent_id = '3';
SET @compression_client_id = '3202';
SET @compression_model_id = '2003';

-- 1. Preflight: all existing compression flows must point to one client_id.
-- Stop if distinct_client_count > 1 or if an existing client_id differs from
-- @compression_client_id. ai_agent_flow_config has no status column.
SELECT COUNT(DISTINCT client_id) AS distinct_client_count,
       GROUP_CONCAT(DISTINCT client_id ORDER BY client_id) AS compression_client_ids
FROM ai_agent_flow_config
WHERE client_type = 'COMPRESSION_ASSISTANT';

SELECT agent_id, client_id, client_name, client_type, sequence
FROM ai_agent_flow_config
WHERE client_type = 'COMPRESSION_ASSISTANT'
ORDER BY agent_id, sequence, client_id;

-- 2. Preflight: the chosen model and its API must already be active.
-- Continue only when this query returns exactly one row.
SELECT m.model_id, m.model_name, m.api_id, m.status AS model_status,
       a.status AS api_status
FROM ai_client_model m
JOIN ai_client_api a ON a.api_id = m.api_id
WHERE m.model_id = @compression_model_id
  AND m.status = 1
  AND a.status = 1;

START TRANSACTION;

-- 3. Create or enable the system compression client.
INSERT INTO ai_client
    (client_id, client_name, description, status, create_time, update_time)
VALUES
    (@compression_client_id, '压缩助手', '系统级上下文压缩服务', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    client_name = VALUES(client_name),
    description = VALUES(description),
    status = 1,
    update_time = NOW();

-- 4. Add one global compression flow record if the same mapping is absent.
-- Multiple agents may reference the same client_id; different client_ids are
-- forbidden and must be resolved before this transaction.
INSERT INTO ai_agent_flow_config
    (agent_id, client_id, client_name, client_type, sequence, step_prompt, create_time)
SELECT @compression_agent_id, @compression_client_id, '压缩助手',
       'COMPRESSION_ASSISTANT', 1, NULL, NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM ai_agent_flow_config
    WHERE agent_id = @compression_agent_id
      AND client_id = @compression_client_id
      AND client_type = 'COMPRESSION_ASSISTANT'
);

-- 5. Ensure exactly one active task_type=1 model relation for the client.
-- Disable obsolete active model relations first, then upsert the selected one.
UPDATE ai_client_config
SET status = 0, update_time = NOW()
WHERE source_type = 'client'
  AND source_id = @compression_client_id
  AND target_type = 'model'
  AND task_type = 1
  AND target_id <> @compression_model_id
  AND status = 1;

UPDATE ai_client_config
SET status = 1, task_type = 1, update_time = NOW()
WHERE source_type = 'client'
  AND source_id = @compression_client_id
  AND target_type = 'model'
  AND target_id = @compression_model_id;

INSERT INTO ai_client_config
    (source_type, source_id, target_type, target_id, ext_param, status,
     input_type, task_type, create_time, update_time)
SELECT 'client', @compression_client_id, 'model', @compression_model_id,
       '{}', 1, NULL, 1, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM ai_client_config
    WHERE source_type = 'client'
      AND source_id = @compression_client_id
      AND target_type = 'model'
      AND target_id = @compression_model_id
);

COMMIT;

-- 6. Acceptance queries. Each count must be 1.
SELECT COUNT(DISTINCT client_id) AS compression_client_count
FROM ai_agent_flow_config
WHERE client_type = 'COMPRESSION_ASSISTANT';

SELECT COUNT(*) AS active_compression_client_count
FROM ai_client
WHERE client_id = @compression_client_id AND status = 1;

SELECT COUNT(*) AS task_type_one_model_relation_count
FROM ai_client_config
WHERE source_type = 'client'
  AND source_id = @compression_client_id
  AND target_type = 'model'
  AND task_type = 1
  AND status = 1;

SELECT COUNT(*) AS active_model_and_api_count
FROM ai_client_model m
JOIN ai_client_api a ON a.api_id = m.api_id
WHERE m.model_id = @compression_model_id
  AND m.status = 1
  AND a.status = 1;

-- Optional DB prompt override (not required):
-- Link an existing active prompt_id=7001 to the compression client. The code
-- default is used when this relation or prompt is absent.
--
-- INSERT INTO ai_client_config
--     (source_type, source_id, target_type, target_id, ext_param, status,
--      input_type, task_type, create_time, update_time)
-- SELECT 'client', @compression_client_id, 'prompt', '7001', '{}', 1,
--        NULL, NULL, NOW(), NOW()
-- WHERE EXISTS (
--     SELECT 1 FROM ai_client_system_prompt
--     WHERE prompt_id = '7001' AND status = 1
-- )
-- AND NOT EXISTS (
--     SELECT 1 FROM ai_client_config
--     WHERE source_type = 'client'
--       AND source_id = @compression_client_id
--       AND target_type = 'prompt'
--       AND target_id = '7001'
-- );

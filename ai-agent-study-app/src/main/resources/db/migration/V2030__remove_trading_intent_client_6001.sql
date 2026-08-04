DELETE FROM ai_agent_flow_config
WHERE client_id = '6001';

DELETE FROM ai_client_config
WHERE (source_type = 'client' AND source_id = '6001')
   OR (target_type = 'client' AND target_id = '6001');

DELETE FROM ai_client
WHERE client_id = '6001';

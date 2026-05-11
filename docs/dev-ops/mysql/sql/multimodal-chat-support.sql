-- ================================================================
-- 多模态对话支持 - 数据库配置
-- 创建时间: 2026-05-11
-- 功能: 支持图片输入的对话客户端
-- ================================================================

USE `ai-agent-station-study`;

-- ------------------------------------------------------------
-- Step 1: 新增多模态客户端记录
-- ------------------------------------------------------------
INSERT INTO `ai_client` (`id`, `client_id`, `client_name`, `description`, `status`, `create_time`, `update_time`)
SELECT 11, 'multimodal', '多模态对话客户端', '支持图片输入的对话客户端', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `ai_client` WHERE `client_id` = 'multimodal');

-- ------------------------------------------------------------
-- Step 2: 新增多模态模型记录（qwen-vl-plus，支持 URL 方式传图）
-- ------------------------------------------------------------
INSERT INTO `ai_client_model` (`id`, `model_id`, `api_id`, `model_name`, `model_type`, `status`, `create_time`, `update_time`)
SELECT 2, 'qwen_vl_plus', '1001', 'qwen-vl-plus', 'openai', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `ai_client_model` WHERE `model_id` = 'qwen_vl_plus');

-- ------------------------------------------------------------
-- Step 3: 建立 client-model 关联
-- ai_client_config 表结构: source_type, source_id, target_type, target_id
-- ------------------------------------------------------------
INSERT INTO `ai_client_config` (`source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`, `create_time`, `update_time`)
SELECT 'client', 'multimodal', 'model', 'qwen_vl_plus', '""', 1, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_client_config`
    WHERE `source_type` = 'client' AND `source_id` = 'multimodal' AND `target_type` = 'model'
);

-- ------------------------------------------------------------
-- Step 4: 验证配置
-- ------------------------------------------------------------
-- SELECT * FROM `ai_client` WHERE `client_id` = 'multimodal';
-- SELECT * FROM `ai_client_model` WHERE `model_id` = 'qwen_vl_plus';
-- SELECT * FROM `ai_client_config` WHERE `source_type` = 'client' AND `source_id` = 'multimodal';

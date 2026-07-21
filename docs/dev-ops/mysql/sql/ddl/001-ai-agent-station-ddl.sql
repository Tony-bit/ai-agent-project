-- ===============================================================
-- AI Agent Station database DDL
-- Database: ai-agent-station
-- Generated from the current database on: 2026-07-21
-- ===============================================================

CREATE DATABASE IF NOT EXISTS `ai-agent-station`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `ai-agent-station`;

SET FOREIGN_KEY_CHECKS = 0;

-- ===============================================================
-- Table: ai_agent
-- ===============================================================
DROP TABLE IF EXISTS `ai_agent`;

CREATE TABLE `ai_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `agent_name` varchar(50) NOT NULL COMMENT '智能体名称',
  `description` varchar(255) DEFAULT NULL COMMENT '描述',
  `channel` varchar(32) DEFAULT NULL COMMENT '渠道类型(agent，chat_stream)',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI智能体配置表';

-- ===============================================================
-- Table: ai_agent_flow_config
-- ===============================================================
DROP TABLE IF EXISTS `ai_agent_flow_config`;

CREATE TABLE `ai_agent_flow_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `client_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '客户端ID',
  `client_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '客户端名称',
  `client_type` varchar(64) DEFAULT NULL COMMENT '客户端类型',
  `sequence` int NOT NULL COMMENT '序列号(执行顺序)',
  `step_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '步骤提示词',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_client_seq` (`agent_id`,`client_id`,`sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体-客户端关联表';

-- ===============================================================
-- Table: ai_agent_task_schedule
-- ===============================================================
DROP TABLE IF EXISTS `ai_agent_task_schedule`;

CREATE TABLE `ai_agent_task_schedule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `task_name` varchar(64) DEFAULT NULL COMMENT '任务名称',
  `description` varchar(255) DEFAULT NULL COMMENT '任务描述',
  `cron_expression` varchar(50) NOT NULL COMMENT '时间表达式(如: 0/3 * * * * *)',
  `task_param` text COMMENT '任务入参配置(JSON格式)',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:无效,1:有效)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体任务调度配置表';

-- ===============================================================
-- Table: ai_auth_user
-- ===============================================================
DROP TABLE IF EXISTS `ai_auth_user`;

CREATE TABLE `ai_auth_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL,
  `user_type` varchar(16) NOT NULL,
  `account` varchar(128) DEFAULT NULL,
  `password_hash` varchar(255) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_user_id` (`user_id`),
  UNIQUE KEY `uk_auth_account` (`account`),
  CONSTRAINT `chk_auth_user_credentials` CHECK ((((`user_type` = _utf8mb4'ACCOUNT') and (`account` is not null) and (`password_hash` is not null)) or ((`user_type` = _utf8mb4'GUEST') and (`account` is null) and (`password_hash` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认证用户表';

-- ===============================================================
-- Table: ai_chat_message
-- ===============================================================
DROP TABLE IF EXISTS `ai_chat_message`;

CREATE TABLE `ai_chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL,
  `message_index` int NOT NULL,
  `role` varchar(16) NOT NULL,
  `content` text NOT NULL,
  `model` varchar(64) DEFAULT NULL,
  `latency_ms` bigint DEFAULT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ===============================================================
-- Table: ai_chat_session
-- ===============================================================
DROP TABLE IF EXISTS `ai_chat_session`;

CREATE TABLE `ai_chat_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL,
  `user_id` varchar(64) DEFAULT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `client_id` varchar(64) DEFAULT NULL,
  `message_count` int DEFAULT '0',
  `first_query` text,
  `last_response` text,
  `status` tinyint DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `add_memory` tinyint NOT NULL DEFAULT '0' COMMENT '是否已同步到记忆：0-未同步，1-已同步',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ===============================================================
-- Table: ai_client
-- ===============================================================
DROP TABLE IF EXISTS `ai_client`;

CREATE TABLE `ai_client` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `client_id` varchar(64) NOT NULL COMMENT '客户端ID',
  `client_name` varchar(50) NOT NULL COMMENT '客户端名称',
  `description` varchar(2096) DEFAULT NULL COMMENT '描述',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客户端配置表';

-- ===============================================================
-- Table: ai_client_advisor
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_advisor`;

CREATE TABLE `ai_client_advisor` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `advisor_id` varchar(64) NOT NULL COMMENT '顾问ID',
  `advisor_name` varchar(50) NOT NULL COMMENT '顾问名称',
  `advisor_type` varchar(50) NOT NULL COMMENT '顾问类型(PromptChatMemory/RagAnswer/SimpleLoggerAdvisor等)',
  `order_num` int DEFAULT '0' COMMENT '顺序号',
  `ext_param` varchar(2048) DEFAULT NULL COMMENT '扩展参数配置，json 记录',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_advisor_id` (`advisor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='顾问配置表';

-- ===============================================================
-- Table: ai_client_api
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_api`;

CREATE TABLE `ai_client_api` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
  `api_id` varchar(64) NOT NULL COMMENT '全局唯一配置ID',
  `base_url` varchar(255) NOT NULL COMMENT 'API基础URL',
  `api_key` varchar(255) NOT NULL COMMENT 'API密钥',
  `completions_path` varchar(255) NOT NULL COMMENT '补全API路径',
  `embeddings_path` varchar(255) NOT NULL COMMENT '嵌入API路径',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_id` (`api_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OpenAI API配置表';

-- ===============================================================
-- Table: ai_client_config
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_config`;

CREATE TABLE `ai_client_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `source_type` varchar(32) NOT NULL COMMENT '源类型（model、client）',
  `source_id` varchar(64) NOT NULL COMMENT '源ID（如 chatModelId、chatClientId 等）',
  `target_type` varchar(32) NOT NULL COMMENT '目标类型（model、client）',
  `target_id` varchar(64) NOT NULL COMMENT '目标ID（如 openAiApiId、chatModelId、systemPromptId、advisorId 等）',
  `ext_param` varchar(1024) DEFAULT NULL COMMENT '扩展参数（JSON格式）',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `input_type` tinyint(1) DEFAULT '0' COMMENT '输入内容类型(0:文本, 1:图片, 2音频)',
  `task_type` tinyint(1) DEFAULT '0' COMMENT '任务类型(0:通用, 1:推理, 2计算，3文案转化)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_task_type` (`source_type`,`source_id`,`target_type`,`target_id`,`task_type`),
  KEY `idx_source_id` (`source_id`),
  KEY `idx_target_id` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客户端统一关联配置表';

-- ===============================================================
-- Table: ai_client_model
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_model`;

CREATE TABLE `ai_client_model` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
  `model_id` varchar(64) NOT NULL COMMENT '全局唯一模型ID',
  `api_id` varchar(64) NOT NULL COMMENT '关联的API配置ID',
  `model_name` varchar(64) NOT NULL COMMENT '模型名称',
  `model_type` varchar(32) NOT NULL COMMENT '模型类型：openai、deepseek、claude',
  `ext_param` text COMMENT '扩展参数，JSON格式，存储retry等配置',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_id` (`model_id`),
  KEY `idx_api_config_id` (`api_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天模型配置表';

-- ===============================================================
-- Table: ai_client_rag_order
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_rag_order`;

CREATE TABLE `ai_client_rag_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `rag_id` varchar(50) NOT NULL COMMENT '知识库ID',
  `rag_name` varchar(50) NOT NULL COMMENT '知识库名称',
  `knowledge_tag` varchar(50) NOT NULL COMMENT '知识标签',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rag_id` (`rag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库配置表';

-- ===============================================================
-- Table: ai_client_system_prompt
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_system_prompt`;

CREATE TABLE `ai_client_system_prompt` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `prompt_id` varchar(64) NOT NULL COMMENT '提示词ID',
  `prompt_name` varchar(50) NOT NULL COMMENT '提示词名称',
  `prompt_content` text NOT NULL COMMENT '提示词内容',
  `description` varchar(1024) DEFAULT NULL COMMENT '描述',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `prompt_type` int NOT NULL,
  `version` int NOT NULL DEFAULT '1' COMMENT '版本号',
  `change_desc` varchar(255) DEFAULT NULL COMMENT '改动说明',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prompt_id_status` (`prompt_id`,`status`),
  KEY `idx_prompt_type_status` (`prompt_type`,`status`),
  KEY `idx_prompt_id_type_version` (`prompt_id`,`prompt_type`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统提示词配置表';

-- ===============================================================
-- Table: ai_client_tool_mcp
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_tool_mcp`;

CREATE TABLE `ai_client_tool_mcp` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `mcp_id` varchar(64) NOT NULL COMMENT 'MCP名称',
  `mcp_name` varchar(50) NOT NULL COMMENT 'MCP名称',
  `transport_type` varchar(20) NOT NULL COMMENT '传输类型(sse/stdio)',
  `transport_config` varchar(1024) DEFAULT NULL COMMENT '传输配置(sse/stdio)',
  `request_timeout` int DEFAULT '180' COMMENT '请求超时时间(分钟)',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态(0:禁用,1:启用)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_id` (`mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP客户端配置表';

-- ===============================================================
-- Table: orders_demo
-- ===============================================================
DROP TABLE IF EXISTS `orders_demo`;

CREATE TABLE `orders_demo` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `status` varchar(20) NOT NULL,
  `created_at` datetime NOT NULL,
  `amount` decimal(10,2) NOT NULL,
  `note` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`,`created_at`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ===============================================================
-- Table: rag_eval_case
-- ===============================================================
DROP TABLE IF EXISTS `rag_eval_case`;

CREATE TABLE `rag_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户/租户维度',
  `query` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评测问题',
  `gold_doc_ids` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标准答案文档ID列表(JSON数组字符串)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0:无效,1:有效)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG离线评测样本表';

-- ===============================================================
-- Table: rag_eval_run
-- ===============================================================
DROP TABLE IF EXISTS `rag_eval_run`;

CREATE TABLE `rag_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `sample_size` int NOT NULL COMMENT '评测样本数',
  `hit_at_5` double NOT NULL COMMENT 'Hit@5',
  `hit_at_10` double NOT NULL COMMENT 'Hit@10',
  `mrr_at_10` double NOT NULL COMMENT 'MRR@10',
  `params_json` text COLLATE utf8mb4_unicode_ci COMMENT '评测参数快照(JSON)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG离线评测运行结果表';

-- ===============================================================
-- Table: working_memory
-- ===============================================================
DROP TABLE IF EXISTS `working_memory`;

CREATE TABLE `working_memory` (
  `id` varchar(64) NOT NULL COMMENT '记忆ID',
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `user_id` varchar(64) DEFAULT NULL COMMENT '用户ID',
  `content` text NOT NULL COMMENT '记忆内容',
  `importance` double DEFAULT '0.5' COMMENT '重要性 0.0~1.0',
  `token_count` int DEFAULT '0' COMMENT 'Token估算数',
  `metadata` json DEFAULT NULL COMMENT '元数据JSON',
  `forgotten` tinyint(1) DEFAULT '0' COMMENT '是否已遗忘',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_session_user` (`session_id`,`user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作记忆表';

SET FOREIGN_KEY_CHECKS = 1;

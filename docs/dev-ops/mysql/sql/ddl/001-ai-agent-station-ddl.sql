-- ===============================================================
-- AI Agent Station 数据库 DDL 存档
-- 数据库名: ai-agent-station
-- 生成时间: 2026-05-18
-- 说明: 基于代码 PO 类定义生成
-- ===============================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS `ai-agent-station`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `ai-agent-station`;

-- ===============================================================
-- 表1: ai_agent - AI智能体配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_agent`;

CREATE TABLE `ai_agent` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `agent_id`          VARCHAR(64)     NOT NULL                          COMMENT '智能体ID',
    `agent_name`        VARCHAR(50)     NOT NULL                          COMMENT '智能体名称',
    `description`       VARCHAR(255)    DEFAULT NULL                       COMMENT '描述',
    `channel`           VARCHAR(32)     DEFAULT NULL                       COMMENT '渠道类型(agent，chat_stream)',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI智能体配置表';


-- ===============================================================
-- 表2: ai_agent_flow_config - 智能体-客户端关联表
-- ===============================================================
DROP TABLE IF EXISTS `ai_agent_flow_config`;

CREATE TABLE `ai_agent_flow_config` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `agent_id`          VARCHAR(64)     NOT NULL                          COMMENT '智能体ID',
    `client_id`         VARCHAR(64)     NOT NULL                          COMMENT '客户端ID',
    `client_name`       VARCHAR(64)     DEFAULT NULL                       COMMENT '客户端名称',
    `client_type`       VARCHAR(32)     DEFAULT NULL                       COMMENT '客户端枚举',
    `sequence`          INT             NOT NULL                          COMMENT '序列号(执行顺序)',
    `step_prompt`       TEXT            DEFAULT NULL                       COMMENT '执行步骤提示词',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_client_seq` (`agent_id`, `client_id`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体-客户端关联表';


-- ===============================================================
-- 表3: ai_agent_task_schedule - 智能体任务调度配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_agent_task_schedule`;

CREATE TABLE `ai_agent_task_schedule` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `agent_id`          BIGINT          NOT NULL                          COMMENT '智能体ID',
    `task_name`         VARCHAR(64)     DEFAULT NULL                       COMMENT '任务名称',
    `description`       VARCHAR(255)    DEFAULT NULL                       COMMENT '任务描述',
    `cron_expression`   VARCHAR(50)     NOT NULL                          COMMENT '时间表达式(如: 0/3 * * * * *)',
    `task_param`        TEXT            DEFAULT NULL                       COMMENT '任务入参配置(JSON格式)',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:无效,1:有效)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体任务调度配置表';


-- ===============================================================
-- 表4: ai_client - AI客户端配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client`;

CREATE TABLE `ai_client` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `client_id`         VARCHAR(64)     NOT NULL                          COMMENT '客户端ID',
    `client_name`       VARCHAR(50)     NOT NULL                          COMMENT '客户端名称',
    `description`       VARCHAR(1024)   DEFAULT NULL                       COMMENT '描述',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客户端配置表';


-- ===============================================================
-- 表5: ai_client_advisor - 顾问配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_advisor`;

CREATE TABLE `ai_client_advisor` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `advisor_id`        VARCHAR(64)     NOT NULL                          COMMENT '顾问ID',
    `advisor_name`      VARCHAR(50)     NOT NULL                          COMMENT '顾问名称',
    `advisor_type`      VARCHAR(50)     NOT NULL                          COMMENT '顾问类型(PromptChatMemory/RagAnswer/SimpleLoggerAdvisor等)',
    `order_num`         INT             DEFAULT '0'                        COMMENT '顺序号',
    `ext_param`         VARCHAR(2048)   DEFAULT NULL                       COMMENT '扩展参数配置，json 记录',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_advisor_id` (`advisor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='顾问配置表';


-- ===============================================================
-- 表6: ai_client_api - OpenAI API配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_api`;

CREATE TABLE `ai_client_api` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '自增主键ID',
    `api_id`            VARCHAR(64)     NOT NULL                          COMMENT '全局唯一配置ID',
    `base_url`          VARCHAR(255)    NOT NULL                          COMMENT 'API基础URL',
    `api_key`           VARCHAR(255)    NOT NULL                          COMMENT 'API密钥',
    `completions_path`  VARCHAR(255)    NOT NULL                          COMMENT '补全API路径',
    `embeddings_path`   VARCHAR(255)    NOT NULL                          COMMENT '嵌入API路径',
    `status`            TINYINT         NOT NULL        DEFAULT '1'        COMMENT '状态：0-禁用，1-启用',
    `create_time`       DATETIME        NOT NULL        DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
    `update_time`       DATETIME        NOT NULL        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_id` (`api_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OpenAI API配置表';


-- ===============================================================
-- 表7: ai_client_config - AI客户端统一关联配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_config`;

CREATE TABLE `ai_client_config` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `source_type`       VARCHAR(32)     NOT NULL                          COMMENT '源类型（model、client）',
    `source_id`         VARCHAR(64)     NOT NULL                          COMMENT '源ID（如 chatModelId、chatClientId 等）',
    `target_type`       VARCHAR(32)     NOT NULL                          COMMENT '目标类型（model、client）',
    `target_id`         VARCHAR(64)     NOT NULL                          COMMENT '目标ID（如 openAiApiId、chatModelId、systemPromptId、advisorId 等）',
    `ext_param`         VARCHAR(1024)   DEFAULT NULL                       COMMENT '扩展参数（JSON格式）',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `input_type`        INT             DEFAULT NULL                       COMMENT '输入类型',
    `task_type`         INT             DEFAULT NULL                       COMMENT '匹配的任务类型',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_source_id` (`source_id`),
    KEY `idx_target_id` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客户端统一关联配置表';


-- ===============================================================
-- 表8: ai_client_model - 聊天模型配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_model`;

CREATE TABLE `ai_client_model` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '自增主键ID',
    `model_id`          VARCHAR(64)     NOT NULL                          COMMENT '全局唯一模型ID',
    `api_id`            VARCHAR(64)     NOT NULL                          COMMENT '关联的API配置ID',
    `model_name`        VARCHAR(64)     NOT NULL                          COMMENT '模型名称',
    `model_type`        VARCHAR(32)     NOT NULL                          COMMENT '模型类型：openai、deepseek、claude',
    `ext_param`         VARCHAR(1024)   DEFAULT NULL                       COMMENT '扩展参数，JSON格式',
    `status`            TINYINT         NOT NULL        DEFAULT '1'        COMMENT '状态：0-禁用，1-启用',
    `create_time`       DATETIME        NOT NULL        DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
    `update_time`       DATETIME        NOT NULL        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_id` (`model_id`),
    KEY `idx_api_config_id` (`api_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天模型配置表';


-- ===============================================================
-- 表9: ai_client_rag_order - 知识库配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_rag_order`;

CREATE TABLE `ai_client_rag_order` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `rag_id`            VARCHAR(50)     NOT NULL                          COMMENT '知识库ID',
    `rag_name`          VARCHAR(50)     NOT NULL                          COMMENT '知识库名称',
    `knowledge_tag`     VARCHAR(50)     NOT NULL                          COMMENT '知识标签',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_id` (`rag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库配置表';


-- ===============================================================
-- 表10: ai_client_system_prompt - 系统提示词配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_system_prompt`;

CREATE TABLE `ai_client_system_prompt` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `prompt_id`         VARCHAR(64)     NOT NULL                          COMMENT '提示词ID',
    `prompt_name`       VARCHAR(50)     NOT NULL                          COMMENT '提示词名称',
    `prompt_content`   TEXT            NOT NULL                          COMMENT '提示词内容',
    `description`       VARCHAR(1024)   DEFAULT NULL                       COMMENT '描述',
    `prompt_type`       INT             DEFAULT NULL                       COMMENT '提示词类型(1=SYSTEM, 2=STEP)',
    `version`           INT             DEFAULT NULL                       COMMENT '版本号',
    `change_desc`       VARCHAR(1024)   DEFAULT NULL                       COMMENT '改动说明',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_id_status` (`prompt_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统提示词配置表';


-- ===============================================================
-- 表11: ai_client_tool_mcp - MCP客户端配置表
-- ===============================================================
DROP TABLE IF EXISTS `ai_client_tool_mcp`;

CREATE TABLE `ai_client_tool_mcp` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `mcp_id`            VARCHAR(64)     NOT NULL                          COMMENT 'MCP ID',
    `mcp_name`          VARCHAR(50)     NOT NULL                          COMMENT 'MCP名称',
    `transport_type`    VARCHAR(20)     NOT NULL                          COMMENT '传输类型(sse/stdio)',
    `transport_config` VARCHAR(1024)   DEFAULT NULL                       COMMENT '传输配置(sse/stdio)',
    `request_timeout`   INT             DEFAULT '180'                      COMMENT '请求超时时间(分钟)',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_id` (`mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP客户端配置表';


-- ===============================================================
-- 表12: chat_message - 聊天消息表
-- ===============================================================
DROP TABLE IF EXISTS `chat_message`;

CREATE TABLE `chat_message` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `session_id`        VARCHAR(64)     NOT NULL                          COMMENT '会话ID',
    `message_index`     INT             DEFAULT NULL                       COMMENT '消息序号',
    `role`              VARCHAR(32)     DEFAULT NULL                       COMMENT '角色(user/assistant/system)',
    `content`           TEXT            DEFAULT NULL                       COMMENT '消息内容',
    `model`             VARCHAR(64)     DEFAULT NULL                       COMMENT '使用的模型',
    `latency_ms`        BIGINT          DEFAULT NULL                       COMMENT '响应延迟(毫秒)',
    `trace_id`          VARCHAR(64)     DEFAULT NULL                       COMMENT '链路追踪ID',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天消息表';


-- ===============================================================
-- 表13: chat_session - 聊天会话表
-- ===============================================================
DROP TABLE IF EXISTS `chat_session`;

CREATE TABLE `chat_session` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `session_id`        VARCHAR(64)     NOT NULL                          COMMENT '会话ID',
    `user_id`           VARCHAR(64)     DEFAULT NULL                       COMMENT '用户ID',
    `agent_id`          VARCHAR(64)     DEFAULT NULL                       COMMENT '智能体ID',
    `client_id`         VARCHAR(64)     DEFAULT NULL                       COMMENT '客户端ID',
    `message_count`     INT             DEFAULT '0'                        COMMENT '消息数量',
    `first_query`       VARCHAR(1024)   DEFAULT NULL                       COMMENT '首条用户查询',
    `last_response`     TEXT            DEFAULT NULL                       COMMENT '最后响应内容',
    `status`            INT             DEFAULT '1'                        COMMENT '状态(1=活跃,0=关闭)',
    `add_memory`        INT             DEFAULT '1'                        COMMENT '是否添加记忆(1=是,0=否)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天会话表';


-- ===============================================================
-- ai_auth_user - 登录账号与游客身份表
-- ===============================================================
DROP TABLE IF EXISTS `ai_auth_user`;

CREATE TABLE `ai_auth_user` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`           VARCHAR(64)     NOT NULL,
    `user_type`         VARCHAR(16)     NOT NULL,
    `account`           VARCHAR(128)    DEFAULT NULL,
    `password_hash`     VARCHAR(255)    DEFAULT NULL,
    `status`            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auth_user_id` (`user_id`),
    UNIQUE KEY `uk_auth_account` (`account`),
    CONSTRAINT `chk_auth_user_credentials` CHECK (
        (`user_type` = 'ACCOUNT' AND `account` IS NOT NULL AND `password_hash` IS NOT NULL)
        OR (`user_type` = 'GUEST' AND `account` IS NULL AND `password_hash` IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认证用户表';


-- ===============================================================
-- 表14: intent_fewshot_sample - 意图识别 Few-Shot 样本表
-- ===============================================================
DROP TABLE IF EXISTS `intent_fewshot_sample`;

CREATE TABLE `intent_fewshot_sample` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `query_text`        VARCHAR(1024)   NOT NULL                          COMMENT '用户 query 原文',
    `intent_code`       VARCHAR(64)     NOT NULL                          COMMENT '意图编码',
    `example_json`      TEXT            NOT NULL                          COMMENT 'LLM 应返回的完整 JSON 示例',
    `dimension`         INT             DEFAULT NULL                       COMMENT 'embedding 向量维度',
    `embedding`         TEXT            DEFAULT NULL                       COMMENT '向量数据（字符串形式）',
    `status`            INT             DEFAULT '1'                        COMMENT '状态：1=启用 0=禁用',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_intent_code` (`intent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='意图识别 Few-Shot 样本表';


-- ===============================================================
-- 表15: rag_eval_case - RAG评估用例表
-- ===============================================================
DROP TABLE IF EXISTS `rag_eval_case`;

CREATE TABLE `rag_eval_case` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `case_name`         VARCHAR(128)    NOT NULL                          COMMENT '用例名称',
    `query_text`        VARCHAR(1024)  NOT NULL                          COMMENT '查询文本',
    `expected_answer`   TEXT            NOT NULL                          COMMENT '期望答案',
    `knowledge_tag`     VARCHAR(64)     DEFAULT NULL                       COMMENT '知识标签',
    `dimension`         INT             DEFAULT NULL                       COMMENT '向量维度',
    `embedding`         TEXT            DEFAULT NULL                       COMMENT '查询向量',
    `status`            TINYINT(1)      DEFAULT '1'                        COMMENT '状态(0:禁用,1:启用)',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    `update_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_name` (`case_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG评估用例表';


-- ===============================================================
-- 表16: rag_eval_run - RAG评估运行记录表
-- ===============================================================
DROP TABLE IF EXISTS `rag_eval_run`;

CREATE TABLE `rag_eval_run` (
    `id`                BIGINT          NOT NULL        AUTO_INCREMENT     COMMENT '主键ID',
    `case_id`           BIGINT          NOT NULL                          COMMENT '关联的用例ID',
    `actual_answer`     TEXT            DEFAULT NULL                       COMMENT '实际返回答案',
    `retrieved_context` TEXT            DEFAULT NULL                       COMMENT '检索到的上下文',
    `similarity_score`  DECIMAL(5,4)    DEFAULT NULL                       COMMENT '相似度得分',
    `precision_score`  DECIMAL(5,4)    DEFAULT NULL                       COMMENT '精确率得分',
    `recall_score`      DECIMAL(5,4)    DEFAULT NULL                       COMMENT '召回率得分',
    `f1_score`          DECIMAL(5,4)    DEFAULT NULL                       COMMENT 'F1得分',
    `status`            VARCHAR(32)     DEFAULT NULL                       COMMENT '运行状态',
    `error_message`     VARCHAR(512)   DEFAULT NULL                       COMMENT '错误信息',
    `create_time`       DATETIME        DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_case_id` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG评估运行记录表';


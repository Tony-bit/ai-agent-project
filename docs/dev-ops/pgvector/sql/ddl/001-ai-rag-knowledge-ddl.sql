-- ===============================================================
-- AI RAG Knowledge PostgreSQL 数据库 DDL 存档
-- 数据库名: ai-rag-knowledge
-- 生成时间: 2026-05-18
-- 说明: 基于代码 PO 类定义和现有数据生成
-- ===============================================================

-- ===============================================================
-- 扩展: 向量数据库支持
-- ===============================================================

CREATE EXTENSION IF NOT EXISTS vector;


-- ===============================================================
-- 表1: store_openai - 通用向量存储表
-- ===============================================================

DROP TABLE IF EXISTS public.store_openai;

CREATE TABLE public.store_openai (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT        NOT NULL,
    metadata    JSONB,
    embedding   VECTOR(1536)
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_store_openai_embedding ON public.store_openai USING ivfflat (embedding vector_cosine_ops);


-- ===============================================================
-- 表2: vector_store_openai - 向量存储表（兼容旧版本）
-- ===============================================================

DROP TABLE IF EXISTS public.vector_store_openai;

CREATE TABLE public.vector_store_openai (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT        NOT NULL,
    metadata    JSONB,
    embedding   VECTOR(1536)
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_vector_store_openai_embedding ON public.vector_store_openai USING ivfflat (embedding vector_cosine_ops);


-- ===============================================================
-- 表3: intent_fewshot_sample - 意图识别 Few-Shot 样本表 (PG存储)
-- ===============================================================

DROP TABLE IF EXISTS public.intent_fewshot_sample;

CREATE TABLE public.intent_fewshot_sample (
    id              BIGSERIAL   PRIMARY KEY,
    query_text      VARCHAR(1024)    NOT NULL,
    intent_code     VARCHAR(64)       NOT NULL,
    example_json    TEXT             NOT NULL,
    dimension       INTEGER,
    embedding       VECTOR(1536),
    status          INTEGER          DEFAULT 1,
    create_time     TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_intent_code ON public.intent_fewshot_sample USING ivfflat (embedding vector_cosine_ops);


-- ===============================================================
-- 表4: memories - Mem0 记忆存储表 (基于配置文件)
-- ===============================================================

DROP TABLE IF EXISTS public.memories;

CREATE TABLE public.memories (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(255),
    user_id         VARCHAR(255),
    agent_id        VARCHAR(255),
    content         TEXT,
    memory_type     VARCHAR(50),
    metadata        JSONB,
    embedding       VECTOR(1536),
    created_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_memories_session_id ON public.memories (session_id);
CREATE INDEX IF NOT EXISTS idx_memories_user_id ON public.memories (user_id);
CREATE INDEX IF NOT EXISTS idx_memories_embedding ON public.memories USING ivfflat (embedding vector_cosine_ops);


-- ===============================================================
-- 函数: 更新记录时间戳
-- ===============================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- 创建触发器
DROP TRIGGER IF EXISTS update_memories_updated_at ON public.memories;
CREATE TRIGGER update_memories_updated_at
    BEFORE UPDATE ON public.memories
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_intent_fewshot_sample_updated_at ON public.intent_fewshot_sample;
CREATE TRIGGER update_intent_fewshot_sample_updated_at
    BEFORE UPDATE ON public.intent_fewshot_sample
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();


-- ===============================================================
-- 注释说明
-- ===============================================================

COMMENT ON TABLE public.store_openai IS '通用向量存储表';
COMMENT ON COLUMN public.store_openai.embedding IS '向量维度: 1536';
COMMENT ON TABLE public.vector_store_openai IS '向量存储表（兼容旧版本）';
COMMENT ON TABLE public.intent_fewshot_sample IS '意图识别 Few-Shot 样本表';
COMMENT ON TABLE public.memories IS 'Mem0 记忆存储表';


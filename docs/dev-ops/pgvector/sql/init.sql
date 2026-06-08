CREATE EXTENSION IF NOT EXISTS vector;

-- 查询表；SELECT * FROM information_schema.tables

-- 删除旧的表（如果存在）
DROP TABLE IF EXISTS public.store_openai;

-- 创建新的表，使用UUID作为主键
CREATE TABLE public.store_openai (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1536)
);

-- 删除旧的表（如果存在）
DROP TABLE IF EXISTS public.vector_store_openai;

-- 创建新的表，使用UUID作为主键
CREATE TABLE public.vector_store_openai (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(1536)
);

-- 删除旧的 Few-Shot 向量索引表（如果存在）
DROP TABLE IF EXISTS public.intent_fewshot_vector_store;

-- 创建 Few-Shot 向量索引表，兼容 PgVectorStore 默认契约
CREATE TABLE public.intent_fewshot_vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(768)
);

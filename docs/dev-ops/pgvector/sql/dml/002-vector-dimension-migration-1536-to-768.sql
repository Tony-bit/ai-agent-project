-- ===============================================================
-- intent_fewshot_vector_store 表向量结构迁移（统一为 PgVectorStore 默认契约）
-- 执行时间: 2026-05-26
-- ===============================================================

-- 重建表
DROP TABLE IF EXISTS public.intent_fewshot_vector_store CASCADE;

CREATE TABLE public.intent_fewshot_vector_store (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content     TEXT NOT NULL,
    metadata    JSONB,
    embedding   VECTOR(768)
);

-- 重建索引
CREATE INDEX IF NOT EXISTS idx_intent_fewshot_vector_store_embedding ON public.intent_fewshot_vector_store USING ivfflat (embedding vector_cosine_ops);

-- 验证
SELECT '迁移完成' as status;
SELECT count(*) as row_count FROM public.intent_fewshot_vector_store;

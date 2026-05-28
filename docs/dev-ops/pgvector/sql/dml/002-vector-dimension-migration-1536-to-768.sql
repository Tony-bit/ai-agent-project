-- ===============================================================
-- intent_fewshot_sample 表向量维度迁移 (1536 -> 768)
-- 执行时间: 2026-05-26
-- ===============================================================

-- 1. 创建触发器函数
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- 2. 重建表
DROP TABLE IF EXISTS public.intent_fewshot_sample CASCADE;

CREATE TABLE public.intent_fewshot_sample (
    id              BIGSERIAL   PRIMARY KEY,
    query_text      VARCHAR(1024)    NOT NULL,
    intent_code     VARCHAR(64)       NOT NULL,
    example_json    TEXT             NOT NULL,
    dimension       INTEGER,
    embedding       VECTOR(768),
    status          INTEGER          DEFAULT 1,
    create_time     TIMESTAMP        DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP        DEFAULT CURRENT_TIMESTAMP
);

-- 3. 重建索引
CREATE INDEX IF NOT EXISTS idx_intent_fewshot_sample_embedding ON public.intent_fewshot_sample USING ivfflat (embedding vector_cosine_ops);

-- 4. 重建触发器
DROP TRIGGER IF EXISTS update_intent_fewshot_sample_updated_at ON public.intent_fewshot_sample;
CREATE TRIGGER update_intent_fewshot_sample_updated_at
    BEFORE UPDATE ON public.intent_fewshot_sample
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 5. 验证
SELECT '迁移完成' as status;
SELECT count(*) as row_count FROM public.intent_fewshot_sample;

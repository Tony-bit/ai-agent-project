-- ===============================================================
-- AI RAG Knowledge PostgreSQL 数据库 DML 数据存档
-- 数据库名: ai-rag-knowledge
-- 生成时间: 2026-05-18
-- 说明: 向量数据通常动态生成，此文件主要用于记录配置信息
-- ===============================================================

-- ===============================================================
-- 说明: PostgreSQL 向量数据库主要用于存储向量嵌入数据
-- 以下是一些可选的示例数据，实际使用时向量数据通常由应用程序动态生成
-- ===============================================================

-- 示例1: 向量存储表示例数据（需要先生成实际的embedding向量）
-- INSERT INTO public.store_openai (content, metadata, embedding)
-- VALUES
--     ('这是一段示例文档内容', '{"source": "manual", "category": "example"}', '[0.1, 0.2, 0.3, ...]::vector'),
--     ('另一段示例文档', '{"source": "manual", "category": "test"}', '[0.4, 0.5, 0.6, ...]::vector');

-- 示例2: Mem0 记忆存储表示例数据
-- INSERT INTO public.memories (session_id, user_id, agent_id, content, memory_type, metadata, embedding)
-- VALUES
--     ('session_001', 'user_001', 'agent_001', '用户偏好喝咖啡', 'user_preference', '{"preference_type": "drink"}', '[0.1, 0.2, 0.3, ...]::vector'),
--     ('session_002', 'user_001', 'agent_001', '用户喜欢编程', 'interest', '{"interest_type": "coding"}', '[0.4, 0.5, 0.6, ...]::vector');

-- 示例3: 意图识别 Few-Shot 样本数据
-- INSERT INTO public.intent_fewshot_sample (query_text, intent_code, example_json, dimension, embedding, status)
-- VALUES
--     ('帮我优化这段代码', 'CODE_OPTIMIZE', '{"intent": "CODE_OPTIMIZE", "confidence": 0.95}', 1536, '[0.1, 0.2, ...]::vector', 1),
--     ('今天天气怎么样', 'WEATHER_QUERY', '{"intent": "WEATHER_QUERY", "confidence": 0.98}', 1536, '[0.3, 0.4, ...]::vector', 1);


-- ===============================================================
-- 向量相似度查询示例
-- ===============================================================

-- 1. 基于向量相似度检索（余弦相似度）
-- SELECT id, content, 1 - (embedding <=> '[query_vector]::vector') AS similarity
-- FROM public.store_openai
-- ORDER BY embedding <=> '[query_vector]::vector'
-- LIMIT 5;

-- 2. 基于元数据过滤的向量检索
-- SELECT id, content, metadata, 1 - (embedding <=> '[query_vector]::vector') AS similarity
-- FROM public.store_openai
-- WHERE metadata->>'category' = 'example'
-- ORDER BY embedding <=> '[query_vector]::vector'
-- LIMIT 5;

-- 3. 混合查询（向量 + 关键词）
-- SELECT id, content, metadata, 1 - (embedding <=> '[query_vector]::vector') AS similarity
-- FROM public.store_openai
-- WHERE content LIKE '%关键词%'
-- ORDER BY embedding <=> '[query_vector]::vector'
-- LIMIT 5;


-- ===============================================================
-- Mem0 记忆查询示例
-- ===============================================================

-- 1. 获取用户的所有记忆
-- SELECT * FROM public.memories
-- WHERE user_id = 'user_001'
-- ORDER BY created_at DESC;

-- 2. 获取特定会话的记忆
-- SELECT * FROM public.memories
-- WHERE session_id = 'session_001'
-- ORDER BY created_at ASC;

-- 3. 基于向量搜索相关记忆
-- SELECT id, content, memory_type, 1 - (embedding <=> '[query_vector]::vector') AS similarity
-- FROM public.memories
-- WHERE user_id = 'user_001'
-- ORDER BY embedding <=> '[query_vector]::vector'
-- LIMIT 10;


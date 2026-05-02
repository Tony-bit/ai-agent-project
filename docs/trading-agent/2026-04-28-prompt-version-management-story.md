# Story: StepPrompt 版本化管理

## 背景

当前 `ai_agent_flow_config.step_prompt` 字段直接存储硬编码文本，每次修改 prompt 需手动 UPDATE 或改代码重新部署，无版本历史，无法对比不同版本的运行效果。

**本 Story 将 `step_prompt` 抽离到 `ai_client_system_prompt` 表，引入版本化机制。改造原则：只改 Repository 层三个方法，Service 层及下游完全无感知。**

---

## 任务清单

| T1 | SQL 建表/改表 + 迁移脚本 | ~~pending~~ → **done(用户执行)** |
| T2 | PO 层新增 3 字段 + 2 常量 | ~~pending~~ → **pass** |
| T3 | VO 层新增 promptType 字段 | ~~pending~~ → **pass** |
| T4 | DAO 层新增 4 个方法 | ~~pending~~ → **pass** |
| T5 | Mapper XML 改造（ResultMap + INSERT/UPDATE + 4 条新 SQL） | ~~pending~~ → **pass** |
| T6 | Repository 层改造 3 个方法 | ~~pending~~ → **pass** |
| T7 | 初始数据导入 | ~~pending~~ → **pass** |
| T8 | 单元测试（确保功能无损） | ~~pending~~ → **pass** |

---

## T1. SQL 建表/改表 + 迁移脚本

```sql
-- 1. 表结构变更
ALTER TABLE ai_client_system_prompt
    ADD COLUMN prompt_type  INT          NOT NULL DEFAULT 1 COMMENT '1=SYSTEM, 2=STEP',
    ADD COLUMN version       INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    ADD COLUMN change_desc   VARCHAR(255)  DEFAULT NULL COMMENT '改动说明',
    ADD INDEX idx_prompt_type_status (prompt_type, status),
    ADD INDEX idx_prompt_id_type_version (prompt_id, prompt_type, version);

-- 2. 旧数据 migration（为 NOT NULL 约束铺垫）
UPDATE ai_client_system_prompt
    SET prompt_type = 1, version = 1, change_desc = '历史记录迁移'
    WHERE prompt_type IS NULL;

-- 3. 约束（按需执行，防止脏数据）
ALTER TABLE ai_client_system_prompt
    MODIFY COLUMN prompt_type INT NOT NULL;
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `prompt_type` | INT | `1`=系统级；`2`=分析师节点级 |
| `version` | INT | 版本号，从 1 递增 |
| `change_desc` | VARCHAR | 本次改动说明 |
| `status` | INT | `0`=禁用；`1`=启用（**复用现有字段**） |

### 约束规则

- 同一 `prompt_id + prompt_type` 下，**同一时刻只有一条 `status=1`**
- 版本唯一性通过业务逻辑（`activateVersion`）保证，不强加 DB 唯一约束（允许同 version 不同 id 的脏数据场景）

---

## T2. PO 层

`AiClientSystemPromptPO.java` 新增字段：

```java
private Integer promptType;   // 1=SYSTEM, 2=STEP
private Integer version;
private String changeDesc;

public static final int TYPE_SYSTEM = 1;
public static final int TYPE_STEP   = 2;
```

---

## T3. VO 层

`AiClientSystemPromptVO.java` 新增字段：

```java
private Integer promptType;
```

---

## T4. DAO 层

`IAiClientSystemPromptDao.java` 新增 4 个方法：

```java
/** 按 promptId + promptType + status=1 查当前生效版本（精确匹配，不会混返 SYSTEM/STEP） */
AiClientSystemPromptPO queryActiveByPromptIdAndType(
        @Param("promptId") String promptId,
        @Param("promptType") Integer promptType);

/** 查所有历史版本（按 version 倒序） */
List<AiClientSystemPromptPO> queryVersionHistory(
        @Param("promptId") String promptId,
        @Param("promptType") Integer promptType);

/** 原子激活版本（CASE WHEN 单条 SQL，无并发风险） */
void activateVersion(@Param("id") Long id,
                     @Param("promptId") String promptId,
                     @Param("promptType") Integer promptType);

/** 批量查询指定 promptType 的所有生效记录（解决 N+1 问题） */
List<AiClientSystemPromptPO> queryActivePromptsByPromptType(@Param("promptType") Integer promptType);
```

---

## T5. Mapper XML

`ai_client_system_prompt_mapper.xml` 改造点：

### 5.1 ResultMap 补全 3 个字段

```xml
<resultMap id="AiClientSystemPromptMap" type="...AiClientSystemPromptPO">
    <id column="id" property="id"/>
    <result column="prompt_id" property="promptId"/>
    <result column="prompt_name" property="promptName"/>
    <result column="prompt_content" property="promptContent"/>
    <result column="description" property="description"/>
    <result column="status" property="status"/>
    <result column="prompt_type" property="promptType"/>     <!-- 新增 -->
    <result column="version" property="version"/>             <!-- 新增 -->
    <result column="change_desc" property="changeDesc"/>     <!-- 新增 -->
    <result column="create_time" property="createTime"/>
    <result column="update_time" property="updateTime"/>
</resultMap>
```

### 5.2 INSERT 补全 3 个字段

```xml
<insert id="insert" ...>
    INSERT INTO ai_client_system_prompt (
        prompt_id, prompt_name, prompt_content, description, status,
        prompt_type, version, change_desc, create_time, update_time
    ) VALUES (
        #{promptId}, #{promptName}, #{promptContent}, #{description}, #{status},
        #{promptType}, #{version}, #{changeDesc}, now(), now()
    )
</insert>
```

### 5.3 updateById / updateByPromptId 补全 3 个字段

两个 UPDATE 的 `<set>` 块中各新增：

```xml
<if test="promptType != null">prompt_type = #{promptType},</if>
<if test="version != null">version = #{version},</if>
<if test="changeDesc != null">change_desc = #{changeDesc},</if>
```

### 5.4 新增 4 条 SQL

```xml
<!-- 精确查生效版本（核心方法） -->
<select id="queryActiveByPromptIdAndType" resultMap="AiClientSystemPromptMap">
    SELECT id, prompt_id, prompt_name, prompt_content, description,
           status, prompt_type, version, change_desc, create_time, update_time
    FROM ai_client_system_prompt
    WHERE prompt_id = #{promptId}
      AND prompt_type = #{promptType}
      AND status = 1
    LIMIT 1
</select>

<!-- 查所有历史版本 -->
<select id="queryVersionHistory" resultMap="AiClientSystemPromptMap">
    SELECT id, prompt_id, prompt_name, prompt_content, description,
           status, prompt_type, version, change_desc, create_time, update_time
    FROM ai_client_system_prompt
    WHERE prompt_id = #{promptId}
      AND prompt_type = #{promptType}
    ORDER BY version DESC
</select>

<!-- 原子激活版本（单条 CASE WHEN，并发安全） -->
<update id="activateVersion">
    UPDATE ai_client_system_prompt
    SET status = CASE WHEN id = #{id} THEN 1 ELSE 0 END,
        update_time = NOW()
    WHERE prompt_id = #{promptId}
      AND prompt_type = #{promptType}
</update>

<!-- 批量查指定 promptType 的所有生效记录 -->
<select id="queryActivePromptsByPromptType" resultMap="AiClientSystemPromptMap">
    SELECT id, prompt_id, prompt_name, prompt_content, description,
           status, prompt_type, version, change_desc, create_time, update_time
    FROM ai_client_system_prompt
    WHERE prompt_type = #{promptType}
      AND status = 1
</select>
```

---

## T6. Repository 层改造（三个方法）

### 6.1 `AiClientSystemPromptVOByClientIds` — 精确查 TYPE_SYSTEM

**改动**：将 `queryByPromptId` 替换为 `queryActiveByPromptIdAndType(promptId, TYPE_SYSTEM)`，避免同 ID 混返 SYSTEM/STEP 两种记录。

```java
@Override
public List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList) {
    if (clientIdList == null || clientIdList.isEmpty()) {
        return List.of();
    }

    List<AiClientSystemPromptVO> result = new ArrayList<>();
    Set<String> processedPromptIds = new HashSet<>();

    for (String clientId : clientIdList) {
        List<AiClientConfigPO> configs = aiClientConfigDao.queryBySourceTypeAndId(
                AiAgentEnumVO.AI_CLIENT.getCode(), clientId);

        for (AiClientConfigPO config : configs) {
            if ("prompt".equals(config.getTargetType()) && config.getStatus() == 1) {
                String promptId = config.getTargetId();
                if (processedPromptIds.contains(promptId)) {
                    continue;
                }
                processedPromptIds.add(promptId);

                // 精确查 TYPE_SYSTEM，避免混返
                AiClientSystemPromptPO systemPrompt =
                        aiClientSystemPromptDao.queryActiveByPromptIdAndType(promptId, AiClientSystemPromptPO.TYPE_SYSTEM);

                if (systemPrompt != null && systemPrompt.getStatus() == 1) {
                    AiClientSystemPromptVO promptVO = AiClientSystemPromptVO.builder()
                            .promptId(systemPrompt.getPromptId())
                            .promptName(systemPrompt.getPromptName())
                            .promptContent(systemPrompt.getPromptContent())
                            .description(systemPrompt.getDescription())
                            .promptType(systemPrompt.getPromptType())
                            .build();
                    result.add(promptVO);
                }
            }
        }
    }
    return result;
}
```

### 6.2 `queryAiAgentClientFlowConfig` — 批量查消除 N+1

**改动**：循环外一次性批量查出所有 TYPE_STEP 生效记录，内存中按 `clientId` 匹配，不再逐条查询。

```java
@Override
public Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId) {
    if (aiAgentId == null || aiAgentId.trim().isEmpty()) {
        return Map.of();
    }

    try {
        List<AiAgentFlowConfigPO> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(aiAgentId);
        if (flowConfigs == null || flowConfigs.isEmpty()) {
            return Map.of();
        }

        // 批量查出所有 TYPE_STEP 生效记录，一次 DB 查询解决 N+1
        List<AiClientSystemPromptPO> activeStepPrompts =
                aiClientSystemPromptDao.queryActivePromptsByPromptType(AiClientSystemPromptPO.TYPE_STEP);
        Map<String, String> stepPromptMap = activeStepPrompts.stream()
                .collect(Collectors.toMap(
                        AiClientSystemPromptPO::getPromptId,
                        AiClientSystemPromptPO::getPromptContent,
                        (v1, v2) -> v1
                ));

        Map<String, AiAgentClientFlowConfigVO> result = new HashMap<>();
        for (AiAgentFlowConfigPO flowConfig : flowConfigs) {
            // 命中则覆盖，无则 fallback
            String stepPrompt = stepPromptMap.getOrDefault(flowConfig.getClientId(), flowConfig.getStepPrompt());

            AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                    .clientId(flowConfig.getClientId())
                    .clientName(flowConfig.getClientName())
                    .clientType(flowConfig.getClientType())
                    .sequence(flowConfig.getSequence())
                    .stepPrompt(stepPrompt)
                    .build();
            result.put(flowConfig.getClientType(), configVO);
        }
        return result;

    } catch (NumberFormatException e) {
        log.error("Invalid aiAgentId format: {}", aiAgentId, e);
        return Map.of();
    } catch (Exception e) {
        log.error("Query ai agent client flow config failed, aiAgentId: {}", aiAgentId, e);
        return Map.of();
    }
}
```

### 6.3 `queryAiClientSystemPromptMapByClientIds` — 直接复用 List

**改动**：移除冗余的 `.map(...)` 转换，直接从上游 List 构建 Map。

```java
@Override
public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList) {
    List<AiClientSystemPromptVO> aiClientSystemPrompts = AiClientSystemPromptVOByClientIds(clientIdList);
    if (null == aiClientSystemPrompts || aiClientSystemPrompts.isEmpty()) {
        return Collections.emptyMap();
    }
    return aiClientSystemPrompts.stream()
            .collect(Collectors.toMap(
                    AiClientSystemPromptVO::getPromptId,
                    Function.identity(),
                    (v1, v2) -> v1
            ));
}
```

---

## T7. 初始数据导入

```sql
-- TYPE_STEP 初始数据（version=1）
INSERT INTO ai_client_system_prompt
    (prompt_id, prompt_name, prompt_content, description, status, prompt_type, version, change_desc, create_time, update_time)
VALUES
    ('fundamental_analyst',  '基本面分析师',   '<AnalystPromptTemplate.FUNDAMENTAL_ANALYST_PROMPT>',  '基本面分析节点 Prompt',   1, 2, 1, '初始版本', NOW(), NOW()),
    ('technical_analyst',    '技术面分析师',   '<AnalystPromptTemplate.TECHNICAL_ANALYST_PROMPT>',  '技术分析节点 Prompt',    1, 2, 1, '初始版本', NOW(), NOW()),
    ('sentiment_analyst',     '情绪分析师',     '<AnalystPromptTemplate.SENTIMENT_ANALYST_PROMPT>', '情绪分析节点 Prompt',    1, 2, 1, '初始版本', NOW(), NOW()),
    ('news_analyst',          '新闻分析师',     '<AnalystPromptTemplate.NEWS_ANALYST_PROMPT>',       '新闻分析节点 Prompt',    1, 2, 1, '初始版本', NOW(), NOW()),
    ('portfolio_manager',     '组合管理分析师', '<AnalystPromptTemplate.PORTFOLIO_MANAGER_PROMPT>', '组合管理节点 Prompt',    1, 2, 1, '初始版本', NOW(), NOW()),
    ('recommendation',        '投资推荐',       '<RecommendationPromptTemplate.RECOMMENDATION_PROMPT>', '推荐节点 Prompt', 1, 2, 1, '初始版本', NOW(), NOW());

-- TYPE_SYSTEM 初始数据（按需，如果原来没有的话）
INSERT INTO ai_client_system_prompt
    (prompt_id, prompt_name, prompt_content, description, status, prompt_type, version, change_desc, create_time, update_time)
VALUES
    ('default_system', '系统默认提示词', '<your_default_system_prompt_content>', '系统默认提示词', 1, 1, 1, '初始版本', NOW(), NOW());
```

---

## 验收标准

1. **版本历史可查**：同一 `promptId` 可查询所有历史版本记录
2. **向后兼容**：`ai_agent_flow_config.step_prompt` 字段保留，DB 无记录时 fallback 正常
3. **下游无感知**：Service 层及分析师节点代码无需改动
4. **绑定关系正确**：`clientId = promptId`，关联查询路径清晰
5. **并发安全**：`activateVersion` 并发执行不会产生两条 `status=1`
6. **N+1 消除**：任何循环查 DB 的场景均优化为批量查询

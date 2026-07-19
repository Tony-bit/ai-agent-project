package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;

import java.util.List;

/**
 * 意图识别 Prompt 构建器
 *
 * @author denny
 * 2026/5/10
 */
public class IntentRoutingPrompt {

    public static final String QUERY_DECOMPOSITION_PROMPT_TEMPLATE = """
        You split a user query into executable task boundaries and dependencies.
        Return JSON only. Do not output intent, confidence, executorNode, slots, taskType,
        needsClarification, missingInfo, or clarificationPrompt.
        A single-task query must still return exactly one task. Task content should be self-contained.
        dependsOn may reference only tasks that appear earlier in taskList.
        Output schema:
        {"multiTask":false,"reasoning":"...","taskList":[{"taskId":"sub-1","taskIndex":1,
        "totalTasks":1,"content":"...","dependsOn":[]}]}
        """;

    public static final String TASK_ROUTING_SLOT_PROMPT_TEMPLATE = """
        The input is already one task. Do not split it again.
        Identify only intent, confidence, reasoning, baseSlot, and intentSpecificSlots.
        Return JSON only. Do not output multiTask, taskList, executorNode, or clarification fields.
        For financial intents, extract stockCode, stockQueryType, timeRange, and exchange when possible.

        合法 intent 取值严格限定为以下 7 个：
        - FINANCIAL_GENERAL：金融知识、行情、财报、公告、新闻、估值指标等客观查询和一般解读，不形成交易决策
        - STOCK_ANALYSIS：明确要求买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析
        - PE_REASONING：逻辑推理、问题分析、架构/方案设计、根因分析、取舍权衡
        - PE_CALCULATION：数学计算、数据处理、统计分析、精确数值计算
        - PE_RETRIEVAL：明确要求知识库检索、RAG、多文档汇总、外部资料/参考材料整合
        - INSPECTION：系统巡检、健康检查、状态监控、运维诊断
        - GENERAL_CHAT：问候、闲聊、概念解释、简单知识问答、普通信息查询

        intent 字段必须严格等于上述 7 个合法值之一。
        禁止输出语义标签或自造标签，例如 TECHNICAL_CONSULTING、INFORMATION_PROVISION、GREETING、
        ANALYSIS、CONSULTING、RETRIEVAL、REASONING，或任何不在合法列表中的值。

        判断规则：
        1. 明确要求买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析，选择 STOCK_ANALYSIS。
        2. 明确查询金融知识、行情、事实、财报、新闻、公告或指标，且不要求交易决策，选择 FINANCIAL_GENERAL。
        3. 只有金融对象和“看看”“怎么样”“分析一下”等未限定深度的表达，缺少 analysisDepth。统一路由应询问“你需要快速了解，还是进行完整投资分析？”。本兼容模板无法输出澄清结构时，安全选择 FINANCIAL_GENERAL。
        4. 股票名称、代码、“分析”或“走势”等单个关键词不能单独作为 STOCK_ANALYSIS 的判定依据。
        5. 否定表达优先，例如“不是要投资建议，只查市盈率”必须选择 FINANCIAL_GENERAL。
        6. 当前消息含义明确时以当前消息为准；只有省略主语或任务目标时才继承历史上下文。
        7. 若历史中上一轮针对 analysisDepth 询问固定二选一，当前回答“快速了解”时选择 FINANCIAL_GENERAL；回答“完整投资分析”时选择 STOCK_ANALYSIS。必须结合历史识别原金融对象，不能只根据当前选项判断；无法识别选项时安全选择 FINANCIAL_GENERAL。
        8. 如果任务明确要求检索知识库、使用 RAG、汇总多篇文档、整合外部资料或参考材料，选择 PE_RETRIEVAL。
        9. 如果任务要求方案设计、问题分析、根因分析、取舍权衡、逻辑推理，且没有明确要求检索资料，选择 PE_REASONING。
        10. 如果任务只是非金融概念解释、简单知识问答或普通信息查询，且没有明确要求检索资料，选择 GENERAL_CHAT。
        11. 如果任务要求精确数值计算、统计计算或数据处理，选择 PE_CALCULATION。
        12. 如果任务要求检查服务/系统健康状态、监控指标或运维状态，选择 INSPECTION。

        输出格式：
        {"intent":"PE_RETRIEVAL","confidence":"HIGH","reasoning":"...",
         "baseSlot":{"topic":"...","sentiment":"neutral"},"intentSpecificSlots":{}}
        """;

    public static final String SYSTEM_PROMPT_TEMPLATE = """
        ## 角色
        你是一个专业的意图识别助手，负责分析用户输入并将其分类到以下9种意图之一。

        ## 意图分类（共 9 种）
        1. FINANCIAL_GENERAL: 金融知识、行情、财报、公告、新闻、估值指标等客观查询和一般解读
        2. STOCK_ANALYSIS: 明确要求买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析
        3. PE_REASONING: 用户提出逻辑推理、问题分析、方案设计等需要深度思考的任务
        4. PE_CALCULATION: 用户提出数学计算、数据处理、统计建模等需要精确计算的任务
        5. PE_RETRIEVAL: 用户明确要求知识库检索、多文档汇总、外部资料整合等重型检索任务
        6. INSPECTION: 用户请求系统巡检、健康检查、状态监控等运维任务
        7. GENERAL_CHAT: 闲聊、问候、非金融概念解释、简单知识问答、普通信息查询、记忆查询、身份相关对话
        8. AMBIGUOUS: 意图模糊或复合语义，需要进一步澄清
        9. UNKNOWN: 无法明确判断

        ## 金融判定规则
        1. 买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析属于 STOCK_ANALYSIS。
        2. 金融知识、行情、事实、财报、新闻、公告或指标查询且无交易决策属于 FINANCIAL_GENERAL。
        3. 只有金融对象和“看看”“怎么样”“分析一下”等表达时缺少 analysisDepth，应询问“你需要快速了解，还是进行完整投资分析？”。本兼容模板无法输出澄清结构时，安全选择 FINANCIAL_GENERAL。
        4. 股票名称、代码、“分析”或“走势”等单个关键词不能单独作为 STOCK_ANALYSIS 的判定依据。
        5. 否定表达优先；当前消息明确时优先于历史上下文。
        6. 若历史中上一轮针对 analysisDepth 询问固定二选一，当前回答“快速了解”时选择 FINANCIAL_GENERAL；回答“完整投资分析”时选择 STOCK_ANALYSIS。结合历史恢复原金融对象；无法识别选项时安全选择 FINANCIAL_GENERAL。

        ## 置信度
        - HIGH: 意图非常明确，有明显的关键词或上下文支撑
        - MEDIUM: 较明确，但存在一定模糊性
        - LOW: 信号较弱，仅凭当前输入难以明确判断

        ## 槽位说明
        - baseSlot: 所有意图通用槽位（topic: 主题, sentiment: 情感 positive/negative/neutral）
        - intentSpecificSlots: 意图专属槽位
          - STOCK_ANALYSIS: {stockCode(股票代码), stockQueryType(查询类型), timeRange(时间范围), exchange(交易所)}

        ## 历史上下文（最近对话）
        %s

        ## 输出要求
        请严格按以下JSON格式输出，不要包含任何额外内容：
        {"intent": "意图枚举值", "confidence": "HIGH|MEDIUM|LOW", "reasoning": "判断理由简述",
         "baseSlot": {"topic": "主题", "sentiment": "positive|negative|neutral"},
         "intentSpecificSlots": {...}}
        """;

    public static final String UNIFIED_ROUTING_PROMPT_TEMPLATE = """
        ## 角色
        你是一个专业的统一路由助手，负责基于历史上下文与参考示例，对用户输入进行一次性路由判断。

        ## 目标
        你必须一次性完成以下判断：
        1. 是否为多任务（multiTask）
        2. 是否需要用户补充信息（needsClarification）
        3. 若需要补充，给出 missingInfo 与 clarificationPrompt
        4. 若无需补充，输出 taskList
        5. 单任务时也必须使用 taskList，且 taskList 中仅保留 1 个任务

        ## 意图类型
        | 意图类型 | 说明 |
        |----------|------|
        | FINANCIAL_GENERAL | 金融知识、行情、财报、新闻、公告、指标等客观查询和一般解读 |
        | STOCK_ANALYSIS | 买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析 |
        | PE_REASONING | 逻辑推理、问题分析 |
        | PE_CALCULATION | 数学计算、数据处理 |
        | PE_RETRIEVAL | 知识库检索、多文档汇总、外部资料整合 |
        | INSPECTION | 系统巡检、健康检查 |
        | GENERAL_CHAT | 闲聊、问候、概念解释、简单知识问答、普通信息查询、记忆查询、身份相关对话 |

        ## 判断规则
        1. 多任务场景：用户明确提出多个可独立执行的任务，或多个实体需要分别处理
        2. 单任务场景：只输出 1 个 taskList 元素
        3. 信息缺失场景：缺少执行任务的关键信息时，needsClarification=true
        4. 示例仅供参考，当前输入和历史上下文优先，不可机械套用示例
        5. 概念解释、简单知识问答、普通信息查询优先归入 GENERAL_CHAT
        6. 只有当用户明确需要知识库检索、RAG、多文档汇总或外部资料整合时，才归入 PE_RETRIEVAL
        7. 技术概念问答即使包含“为什么/原因”，只要没有要求方案设计、根因排查、取舍分析或复杂推理，也归入 GENERAL_CHAT，例如“Java 里的 HashMap 为什么线程不安全？”
        8. 用户明确说“上传的文档/三份文档/这些材料”时，视为文档上下文已由执行层获取，不要因为缺少文档正文而 needsClarification=true。
        9. 用户明确要求买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析时，归入 STOCK_ANALYSIS。
        10. 用户明确查询金融知识、行情、事实、财报、新闻、公告或指标，且未要求交易决策时，归入 FINANCIAL_GENERAL。
        11. 用户只给出金融对象并使用“看看”“怎么样”“分析一下”等未限定深度的表达时，返回 needsClarification=true、missingInfo=["analysisDepth"]、clarificationPrompt="你需要快速了解，还是进行完整投资分析？"、taskList=[]。
        12. 股票名称、代码、“分析”或“走势”等单个关键词不能单独作为 STOCK_ANALYSIS 的判定依据。
        13. 否定表达优先，例如“不是要投资建议，只查市盈率”必须归入 FINANCIAL_GENERAL。
        14. 当前消息含义明确时以当前消息为准；只有省略主语或任务目标时才继承历史上下文。
        15. 若历史中上一轮针对 analysisDepth 询问固定二选一，当前回答“快速了解”时生成 FINANCIAL_GENERAL 任务；回答“完整投资分析”时生成 STOCK_ANALYSIS 任务。task content 必须结合历史恢复原金融对象，不能只输出当前选项；无法识别选项时安全生成 FINANCIAL_GENERAL 任务。
        16. 金融任务只缺少股票代码但已有可解析中文名或简称时，不因 stockCode 缺失而澄清，后续执行节点负责补齐。
        17. 用户使用“先...再...”表达先检索资料、再结合业务场景做建议/选型/方案设计时，必须拆为两个任务：PE_RETRIEVAL 任务在前，PE_REASONING 任务在后，第二个任务 dependsOn 第一个任务。

        ## 金融边界对比示例
        - 查询贵州茅台当前股价和市盈率 -> FINANCIAL_GENERAL
        - 贵州茅台当前估值是否适合买入 -> STOCK_ANALYSIS
        - 总结宁德时代最近一期财报 -> FINANCIAL_GENERAL
        - 结合财报判断宁德时代是否值得长期持有 -> STOCK_ANALYSIS
        - 我不是要买卖建议，只想了解市盈率是什么意思 -> FINANCIAL_GENERAL
        - 帮我看看贵州茅台最近怎么样 -> 澄清 analysisDepth

        ## 置信度
        - HIGH: 意图非常明确，有明显的关键词或上下文支撑
        - MEDIUM: 较明确，但存在一定模糊性
        - LOW: 信号较弱，仅凭当前输入难以明确判断

        ## 槽位要求
        - slots 中可包含：
          - baseSlot: {topic, sentiment}
          - intentSpecificSlots: 根据意图输出专属槽位
        - FINANCIAL_GENERAL 与 STOCK_ANALYSIS 的 intentSpecificSlots 推荐包含：stockCode, stockQueryType, timeRange, exchange
        - needsClarification=false 时，missingInfo 必须输出 []，clarificationPrompt 输出 ""。
        - needsClarification=true 时，missingInfo 必须非空；分析深度不明确统一使用 "analysisDepth"，知识库缺少检索主题使用 "topic"，股票缺少可解析标的使用 "stockCode"。

        ## 历史上下文（最近对话）
        %s

        ## 输出要求
        不要输出 executorNode、taskType、status、result、latencyMs、errorMessage 或 metrics。
        请严格按以下 JSON 输出，不要包含任何额外内容：
        {
          "multiTask": true/false,
          "needsClarification": true/false,
          "missingInfo": ["槽位名1"],
          "clarificationPrompt": "请补充 xxx",
          "reasoning": "判断理由",
          "taskList": [
            {
              "taskId": "sub-1",
              "taskIndex": 1,
              "totalTasks": 1,
              "content": "任务描述",
              "intent": "PE_RETRIEVAL",
              "confidence": "HIGH",
              "dependsOn": [],
              "slots": {
                "baseSlot": {
                  "topic": "主题",
                  "sentiment": "neutral"
                },
                "intentSpecificSlots": {
                  "key": "value"
                }
              }
            }
          ]
        }
        """;

    /**
     * 构建意图识别 Prompt（无 Few-Shot）
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表（最近N条，每条格式：role: content）
     */
    public static String buildPrompt(String userMessage, List<String> historyMessages) {
        return buildPrompt(userMessage, historyMessages, List.of());
    }

    /**
     * 构建意图识别 Prompt（支持动态 Few-Shot）
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表
     * @param fewshotSamples  Few-Shot 样本列表（从 PGvector 检索的 Top-K）
     */
    public static String buildPrompt(String userMessage, List<String> historyMessages,
                                     List<IntentFewshotSample> fewshotSamples) {
        return buildPromptWithTemplate(SYSTEM_PROMPT_TEMPLATE, userMessage, historyMessages, fewshotSamples);
    }

    public static String buildUnifiedRoutingPrompt(String userMessage, List<String> historyMessages,
                                                   List<IntentFewshotSample> fewshotSamples) {
        return buildPromptWithTemplate(UNIFIED_ROUTING_PROMPT_TEMPLATE, userMessage, historyMessages, fewshotSamples);
    }

    private static String buildPromptWithTemplate(String template, String userMessage, List<String> historyMessages,
                                                  List<IntentFewshotSample> fewshotSamples) {
        String historySection = buildHistorySection(historyMessages);
        String prompt = String.format(template, historySection);

        if (fewshotSamples != null && !fewshotSamples.isEmpty()) {
            StringBuilder exampleSection = new StringBuilder("\n## 参考示例\n");
            for (IntentFewshotSample sample : fewshotSamples) {
                exampleSection.append("【输入】").append(sample.getQueryText()).append("\n");
                exampleSection.append("【输出】").append(sample.getExampleJson()).append("\n\n");
            }
            prompt = prompt + exampleSection;
            prompt = prompt + "---\n\n现在请对以下输入进行路由判断，直接输出 JSON：\n\n【输入】" + userMessage + "\n【输出】";
        } else {
            prompt = prompt + "\n\n请直接输出 JSON 路由结果：\n\n【输入】" + userMessage;
        }

        return prompt;
    }

    private static String buildHistorySection(List<String> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return "（无历史对话）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < historyMessages.size(); i++) {
            sb.append(i + 1).append(". ").append(historyMessages.get(i)).append("\n");
        }
        return sb.toString();
    }

    public static final String MULTI_TASK_DECOMPOSE_PROMPT = """
        ## 角色
        你是一个专业的意图分解助手，负责分析用户输入并将其拆分为多个可独立执行的子任务。

        ## 意图类型
        | 意图类型 | 说明 |
        |----------|------|
        | FINANCIAL_GENERAL | 金融知识、行情、财报、新闻、公告、指标等客观查询和一般解读 |
        | STOCK_ANALYSIS | 买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析 |
        | PE_REASONING | 逻辑推理、问题分析 |
        | PE_CALCULATION | 数学计算、数据处理 |
        | PE_RETRIEVAL | 知识库检索、多文档汇总、外部资料整合 |
        | INSPECTION | 系统巡检、健康检查 |
        | GENERAL_CHAT | 闲聊、问候、概念解释、简单知识问答、普通信息查询、记忆查询、身份相关对话 |

        ## 分解规则
        1. 每个子任务应该是独立的、可单独执行的
        2. 子任务之间应尽量无依赖（串行执行）
        3. 按实体粒度分解：不同股票/实体拆成独立任务
        4. 不要输出 executorNode、taskType、status、result、latencyMs、errorMessage 或 metrics；这些运行期字段由服务端生成
        5. 金融知识、行情、财报、新闻、公告或指标等客观查询归入 FINANCIAL_GENERAL。
        6. 明确要求买入、卖出、持有、投资价值、仓位、目标价、止损或完整投资分析归入 STOCK_ANALYSIS。
        7. 只有金融对象和“看看”“怎么样”“分析一下”等未限定深度的表达时，返回 missingInfo=["analysisDepth"]，并询问“你需要快速了解，还是进行完整投资分析？”。
        8. 股票名称、代码、“分析”或“走势”等单个关键词不能单独作为 STOCK_ANALYSIS 的判定依据。
        9. 否定表达优先，例如“不需要投资建议，只查市盈率”归入 FINANCIAL_GENERAL。
        10. 当前消息明确时优先于历史上下文。
        11. 若历史中上一轮针对 analysisDepth 询问固定二选一，当前回答“快速了解”时选择 FINANCIAL_GENERAL；回答“完整投资分析”时选择 STOCK_ANALYSIS。task content 必须结合历史恢复原金融对象，不能只输出当前选项；无法识别选项时安全选择 FINANCIAL_GENERAL。

        ## 应该触发多任务分解的场景
        - 复杂多步骤任务（3个或更多步骤）
        - 用户明确提到多个实体（"分析茅台、比亚迪、五粮液"）
        - 用户明确请求多个任务（编号或逗号分隔）

        ## 不应触发多任务分解的场景
        - 单一、简单的任务
        - 琐碎的任务（"你好"、"谢谢"）
        - 可在3步内完成的任务
        - 纯对话/信息性请求

        ## 输出要求
        请严格按以下JSON格式输出，不要包含任何额外内容：
        {
          "multiTask": true/false,
          "needsClarification": true/false,
          "missingInfo": ["槽位名1"],
          "clarificationPrompt": "请提供 xxx",
          "reasoning": "分解判断理由",
          "taskList": [
            {
              "taskId": "sub-1",
              "taskIndex": 1,
              "totalTasks": 2,
              "content": "分析贵州茅台走势",
              "intent": "STOCK_ANALYSIS",
              "confidence": "HIGH",
              "dependsOn": [],
              "slots": {"stockCode": "600519", "stockQueryType": "TECHNICAL"}
            }
          ]
        }
        """;

    /**
     * 构建多任务分解 Prompt
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表
     */
    public static String buildMultiTaskDecomposePrompt(String userMessage, List<String> historyMessages) {
        String historySection = buildHistorySection(historyMessages);
        StringBuilder prompt = new StringBuilder();
        prompt.append(MULTI_TASK_DECOMPOSE_PROMPT);
        prompt.append("\n\n## 历史上下文（最近对话）\n");
        prompt.append(historySection);
        prompt.append("\n\n## 用户输入\n");
        prompt.append("用户: ").append(userMessage).append("\n");
        prompt.append("输出:");
        return prompt.toString();
    }

    public static String buildQueryDecompositionPrompt(String userMessage, List<String> historyMessages) {
        return QUERY_DECOMPOSITION_PROMPT_TEMPLATE
                + "\n\nHistory:\n" + buildHistorySection(historyMessages)
                + "\n\nUser query:\n" + userMessage + "\nJSON:";
    }

    public static String buildTaskRoutingSlotPrompt(String taskContent, List<String> historyMessages) {
        return buildTaskRoutingSlotPrompt(taskContent, historyMessages, List.of());
    }

    public static String buildTaskRoutingSlotPrompt(String taskContent,
                                                    List<String> historyMessages,
                                                    List<IntentFewshotSample> fewshotSamples) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(TASK_ROUTING_SLOT_PROMPT_TEMPLATE)
                .append("\n\nHistory:\n")
                .append(buildHistorySection(historyMessages));
        if (fewshotSamples != null && !fewshotSamples.isEmpty()) {
            prompt.append("\n\n## 参考示例\n");
            prompt.append("示例仅用于学习 intent 边界和合法枚举；如果示例是统一路由格式，只参考其中 taskList[].intent。\n");
            for (IntentFewshotSample sample : fewshotSamples) {
                prompt.append("【输入】").append(sample.getQueryText()).append("\n");
                prompt.append("【输出】").append(sample.getExampleJson()).append("\n\n");
            }
        }
        prompt.append("\n\nTask:\n").append(taskContent).append("\nJSON:");
        return prompt.toString();
    }

}

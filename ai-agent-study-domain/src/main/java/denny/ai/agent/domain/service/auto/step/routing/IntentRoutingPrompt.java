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

    public static final String SYSTEM_PROMPT_TEMPLATE = """
        ## 角色
        你是一个专业的意图识别助手，负责分析用户输入并将其分类到以下8种意图之一。

        ## 意图分类（共 8 种）
        1. STOCK_ANALYSIS: 用户询问股票、基金、期货、市场行情等技术分析、基本面分析、交易建议
        2. PE_REASONING: 用户提出逻辑推理、问题分析、方案设计等需要深度思考的任务
        3. PE_CALCULATION: 用户提出数学计算、数据处理、统计建模等需要精确计算的任务
        4. PE_RETRIEVAL: 用户明确要求知识库检索、多文档汇总、外部资料整合等重型检索任务
        5. INSPECTION: 用户请求系统巡检、健康检查、状态监控等运维任务
        6. GENERAL_CHAT: 闲聊、问候、概念解释、简单知识问答、普通信息查询、记忆查询（如询问个人偏好、之前聊过的内容）、询问个人身份信息（如询问自己的职业、身份、角色等）、无法归类的对话
        7. AMBIGUOUS: 意图模糊或复合语义，需要进一步澄清
        8. UNKNOWN: 无法明确判断

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

        ## 意图类型与执行节点映射
        | 意图类型 | 执行节点 (executorNode) | 说明 |
        |----------|------------------------|------|
        | STOCK_ANALYSIS | tradingStarter | 股票/市场分析 |
        | PE_REASONING | step1AnalyzerNode | 逻辑推理、问题分析 |
        | PE_CALCULATION | step1AnalyzerNode | 数学计算、数据处理 |
        | PE_RETRIEVAL | step1AnalyzerNode | 知识库检索、多文档汇总、外部资料整合 |
        | INSPECTION | intelligentInspection | 系统巡检、健康检查 |
        | GENERAL_CHAT | generalChatNode | 闲聊、问候、概念解释、简单知识问答、普通信息查询、记忆查询、身份相关对话 |

        ## 判断规则
        1. 多任务场景：用户明确提出多个可独立执行的任务，或多个实体需要分别处理
        2. 单任务场景：只输出 1 个 taskList 元素
        3. 信息缺失场景：缺少执行任务的关键信息时，needsClarification=true
        4. 示例仅供参考，必须以当前用户输入和历史上下文为准，不可机械套用示例
        5. 概念解释、简单知识问答、普通信息查询优先归入 GENERAL_CHAT
        6. 只有当用户明确需要知识库检索、RAG、多文档汇总或外部资料整合时，才归入 PE_RETRIEVAL

        ## 置信度
        - HIGH: 意图非常明确，有明显的关键词或上下文支撑
        - MEDIUM: 较明确，但存在一定模糊性
        - LOW: 信号较弱，仅凭当前输入难以明确判断

        ## 槽位要求
        - slots 中可包含：
          - baseSlot: {topic, sentiment}
          - intentSpecificSlots: 根据意图输出专属槽位
        - STOCK_ANALYSIS 的 intentSpecificSlots 推荐包含：stockCode, stockQueryType, timeRange, exchange

        ## 历史上下文（最近对话）
        %s

        ## 输出要求
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
              "executorNode": "step1AnalyzerNode",
              "confidence": "HIGH",
              "taskType": 0,
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

        ## 意图类型与执行节点映射
        | 意图类型 | 执行节点 (executorNode) | 说明 |
        |----------|------------------------|------|
        | STOCK_ANALYSIS | tradingStarter | 股票/市场分析 |
        | PE_REASONING | step1AnalyzerNode | 逻辑推理、问题分析 |
        | PE_CALCULATION | step1AnalyzerNode | 数学计算、数据处理 |
        | PE_RETRIEVAL | step1AnalyzerNode | 知识库检索、多文档汇总、外部资料整合 |
        | INSPECTION | intelligentInspection | 系统巡检、健康检查 |
        | GENERAL_CHAT | generalChatNode | 闲聊、问候、概念解释、简单知识问答、普通信息查询、记忆查询、身份相关对话 |

        ## 分解规则
        1. 每个子任务应该是独立的、可单独执行的
        2. 子任务之间应尽量无依赖（串行执行）
        3. 按实体粒度分解：不同股票/实体拆成独立任务
        4. 每个 SubTask 必须指定 executorNode（对应执行节点名称）

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
              "executorNode": "tradingStarter",
              "confidence": "HIGH",
              "taskType": 0,
              "slots": {"stockCode": "600519", "stockQueryType": "TECHNICAL"}
            }
          ]
        }

        ## taskType 字段说明
        - taskType 必须为整数，表示执行该子任务使用的模型配置编号
        - 推荐使用 taskType=0（通用模型），除非任务需要特殊模型
        - taskType 仅限非负整数，禁止包含字母或特殊字符
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
}

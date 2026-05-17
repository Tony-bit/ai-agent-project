package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.ConfidenceEnum;
import denny.ai.agent.trading.api.vo.IntentEnumVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStarter;
import denny.ai.agent.trading.domain.prompt.IntentRoutingPrompt;
import denny.ai.agent.trading.domain.service.TradingIntentRoutingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 意图路由节点。
 * <p>
 * 前置于 Step1AnalyzerNode，负责识别用户意图：
 * - STOCK_ANALYSIS + 高置信度 → 启动状态机执行交易分析
 * - STOCK_ANALYSIS + 中置信度 → 设置 needs_confirmation 标志
 * - GENERAL_CHAT 或低置信度 → 不修改，正常流转
 */
@Slf4j
@Service("tradingIntentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {

    public static final String TRADING_REQUEST_KEY = "trading_request";
    public static final String NEEDS_CONFIRMATION_KEY = "needs_confirmation";
    public static final String INTENT_ROUTING_RESULT_KEY = "intent_routing_result";
    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    @Resource
    private TradingIntentRoutingService tradingIntentRoutingService;

    @Resource
    private TradingStarter starter;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 意图路由节点执行开始 ===");
        log.info("用户消息: {}", requestParameter.getMessage());

        long startAt = System.currentTimeMillis();

        log.info("开始意图识别，用户消息: {}", requestParameter.getMessage());
        ChatClient chatClient = getChatClientByClientId("6001", 0);

        // 构建临时对话历史，让 LLM 在其中执行 tool 调用
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new SystemMessage(IntentRoutingPrompt.SYSTEM_PROMPT),
                new UserMessage(requestParameter.getMessage())
        );

        // stream() 返回 Flux<String>，通过 block() 同步获取最终内容
        List<String> contentParts = chatClient.prompt()
                .messages(messages)
                .advisors(MessageChatMemoryAdvisor.builder(
                        MessageWindowChatMemory.builder()
                                .maxMessages(10)
                                .build()
                ).build())
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, "intent_routing_" + requestParameter.getSessionId()))
                .stream()
                .content()
                .collectList()
                .block();
        String response = contentParts != null ? String.join("", contentParts) : "";

        log.debug("意图识别 LLM 原始响应: {}", response);

        TradingIntentRoutingService.IntentRoutingResult result = tradingIntentRoutingService.parseResponse(response);

        long latencyMs = System.currentTimeMillis() - startAt;
        log.info("意图识别完成: intent={}, confidence={}, ticker={}, 耗时={}ms",
                result.getIntent(), result.getConfidence(), result.getTicker(), latencyMs);

        sendIntentRoutingEvent(dynamicContext, result);

        if (result.getIntent() == IntentEnumVO.STOCK_ANALYSIS) {
            handleStockAnalysisIntent(requestParameter, dynamicContext, result);
        } else {
            log.info("非股票分析意图，不设置 trading_request，继续流转到 Step1AnalyzerNode");
        }

        dynamicContext.setValue(INTENT_ROUTING_RESULT_KEY, result);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        StockAnalysisRequestVO tradingRequest = dynamicContext.getValue(TRADING_REQUEST_KEY);
        if (tradingRequest != null) {
            try {
                starter.start(tradingRequest, dynamicContext, (type, event) -> {
                    // SSE 发送已由 TradingStateContext 处理，此处为空
                });
            } catch (Exception e) {
                log.error("交易分析执行异常: {}", e.getMessage(), e);
            }
            return null;
        }

        Boolean needsConfirmation = dynamicContext.getValue(NEEDS_CONFIRMATION_KEY);
        if (Boolean.TRUE.equals(needsConfirmation)) {
            dynamicContext.setValue("awaiting_confirmation", true);
            return null;
        }

        return null;
    }

    private void handleStockAnalysisIntent(ExecuteCommandEntity requestParameter,
                                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                          TradingIntentRoutingService.IntentRoutingResult result) {
        String ticker = result.getTicker();

        // 兜底：如果 ticker 为 null 且意图是 STOCK_ANALYSIS，尝试从消息中提取公司名称并搜索
        if (ticker == null && result.getIntent() == IntentEnumVO.STOCK_ANALYSIS) {
            String companyName = extractCompanyName(requestParameter.getMessage());
            if (companyName != null && !companyName.isEmpty()) {
                log.info("开始通过公司名搜索股票: company={}", companyName);
                ticker = tradingIntentRoutingService.searchTickerByName(companyName);
                if (ticker != null) {
                    log.info("Java 兜底搜索成功: company={}, ticker={}", companyName, ticker);
                } else {
                    log.warn("Java 兜底搜索失败，将以 ticker=null 继续流程: company={}", companyName);
                }
            } else {
                log.warn("无法从消息中提取有效公司名: {}", requestParameter.getMessage());
            }
        }

        if (result.getConfidence() == ConfidenceEnum.HIGH) {
            StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                    .ticker(ticker)
                    .selectedAnalysts(result.getSelectedAnalysts())
                    .maxDebateRounds(2)
                    .maxRiskRounds(1)
                    .sessionId(requestParameter.getSessionId())
                    .build();

            dynamicContext.setValue(TRADING_REQUEST_KEY, tradingRequest);
            log.info("高置信度股票分析意图，设置 trading_request: ticker={}", ticker);

        } else if (result.getConfidence() == ConfidenceEnum.MEDIUM) {
            dynamicContext.setValue(NEEDS_CONFIRMATION_KEY, true);
            dynamicContext.setValue("pending_ticker", ticker);
            dynamicContext.setValue("pending_analysts", result.getSelectedAnalysts());
            log.info("中置信度股票分析意图，等待用户确认: ticker={}", ticker);

        } else {
            log.info("低置信度股票分析意图，不触发交易流程: ticker={}", ticker);
        }
    }

    /**
     * 从消息中提取公司名称。
     */
    private String extractCompanyName(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        // 去除常见前缀（按长度降序排列，确保长前缀优先匹配）
        String[] prefixes = {
                "帮我分析一下", "帮我看看", "帮我查一下", "帮我找一下",
                "分析一下", "看看", "查一下", "找一下",
                "分析", "查看", "查询", "查找",
                "帮我", "我想了解", "请帮我", "请问"
        };
        String temp = message.trim();
        for (String prefix : prefixes) {
            if (temp.startsWith(prefix)) {
                temp = temp.substring(prefix.length()).trim();
                break; // 只去除第一个匹配的前缀
            }
        }
        // 如果去除前缀后是"的股票"结尾，去掉
        if (temp.endsWith("的股票")) {
            temp = temp.substring(0, temp.length() - 4).trim();
        }
        // 去除常见后缀
        String[] suffixes = {"怎么样", "如何", "好吗", "股票"};
        for (String suffix : suffixes) {
            if (temp.endsWith(suffix)) {
                temp = temp.substring(0, temp.length() - suffix.length()).trim();
            }
        }
        // 如果剩余内容太短（<2字符）或太长（>4字符），认为是无效提取
        // A股公司名最长4个字，如"贵州茅台"、"宁德时代"
        if (temp.length() < 2 || temp.length() > 4) {
            log.warn("公司名提取结果可疑（长度不在2-4之间），返回 null: original={}, extracted={}", message, temp);
            return null;
        }
        return temp;
    }

    private void sendIntentRoutingEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                       TradingIntentRoutingService.IntentRoutingResult result) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("intent_routing")
                .subType("intent_result")
                .step(0)
                .content(String.format("意图识别: %s (置信度: %s)", result.getIntent().getDescription(), result.getConfidence().getDescription()))
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}

package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.sse.SseEventSender;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.config.TradingStarter;
import denny.ai.agent.trading.domain.exception.StockIdentityNotFoundException;
import denny.ai.agent.trading.domain.exception.StockIdentityProviderException;
import denny.ai.agent.trading.domain.exception.StockIdentityValidationException;
import denny.ai.agent.trading.domain.service.AnalysisTypeMapper;
import denny.ai.agent.trading.domain.service.TargetContextFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;

@Slf4j
@Service("tradingRequestNode")
public class TradingRequestNode extends AbstractExecuteSupport {

    public static final String TRADING_REQUEST_KEY = "trading_request";
    public static final String ROUTING_TERMINAL_RESPONSE_KEY = "routingTerminalResponse";
    public static final String ROUTING_TERMINAL_KIND_KEY = "routingTerminalKind";

    private static final String STOCK_SLOT_KEY = "stockSlot";
    private static final String VALIDATED_TARGET_KEY = "validatedTradingTarget";
    private static final String CLARIFICATION = "CLARIFICATION";
    private static final String ERROR = "ERROR";
    private static final String STOCK_CLARIFICATION = "请提供完整 A 股名称或 6 位代码";

    @Resource
    private AnalysisTypeMapper analysisTypeMapper;

    @Resource
    private TargetContextFactory targetContextFactory;

    @Resource
    private TradingStarter starter;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        StockSlot stockSlot = dynamicContext.getValue(STOCK_SLOT_KEY);
        String ticker;
        try {
            ticker = normalizeTicker(stockSlot == null ? null : stockSlot.getStockCode());
        } catch (IllegalArgumentException error) {
            return recordTerminal(dynamicContext, CLARIFICATION, STOCK_CLARIFICATION);
        }

        StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                .ticker(ticker)
                .stockName(stockSlot.getStockName())
                .selectedAnalysts(analysisTypeMapper.map(stockSlot.getStockQueryType()))
                .maxDebateRounds(2)
                .maxRiskRounds(1)
                .sessionId(requestParameter == null ? null : requestParameter.getSessionId())
                .build();

        try {
            TargetContext targetContext = targetContextFactory.create(
                    ticker, stockSlot.getStockName(), LocalDate.parse(tradingRequest.getTradeDate()));
            tradingRequest.setTicker(targetContext.targetId());
            tradingRequest.setStockName(targetContext.stockName());
            dynamicContext.setValue(TRADING_REQUEST_KEY, tradingRequest);
            dynamicContext.setValue(VALIDATED_TARGET_KEY, targetContext);
            return router(requestParameter, dynamicContext);
        } catch (StockIdentityNotFoundException error) {
            return recordTerminal(dynamicContext, CLARIFICATION, STOCK_CLARIFICATION);
        } catch (StockIdentityProviderException error) {
            log.error("股票身份 Provider 调用失败: ticker={}", ticker, error.getCause());
            return recordTerminal(dynamicContext, ERROR, "股票数据服务暂时不可用，请稍后重试");
        } catch (StockIdentityValidationException error) {
            log.warn("股票身份校验失败: ticker={}, stockName={}, error={}",
                    ticker, stockSlot.getStockName(), error.getMessage());
            return recordTerminal(dynamicContext, ERROR, "股票身份校验失败，本次分析已停止");
        }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        StockAnalysisRequestVO tradingRequest = dynamicContext.getValue(TRADING_REQUEST_KEY);
        TargetContext targetContext = dynamicContext.getValue(VALIDATED_TARGET_KEY);
        if (tradingRequest == null || targetContext == null) {
            return null;
        }
        SseEventSender sender = (type, event) -> event instanceof AutoAgentExecuteResultEntity result
                && sendSseResult(dynamicContext, result);
        starter.start(tradingRequest, targetContext, dynamicContext, sender);
        return null;
    }

    private String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker must not be blank");
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[0-9]{6}(\\.(SH|SZ|BJ))?$")) {
            throw new IllegalArgumentException("ticker must be an A-share code");
        }
        return normalized;
    }

    private String recordTerminal(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                  String kind,
                                  String response) {
        dynamicContext.setValue(ROUTING_TERMINAL_KIND_KEY, kind);
        dynamicContext.setValue(ROUTING_TERMINAL_RESPONSE_KEY, response);
        if (CLARIFICATION.equals(kind)) {
            dynamicContext.setValue("clarificationPrompt", response);
        }
        return response;
    }
}

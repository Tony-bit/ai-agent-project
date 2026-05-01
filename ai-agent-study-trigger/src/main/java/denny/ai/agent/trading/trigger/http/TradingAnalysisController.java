package denny.ai.agent.trading.trigger.http;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStarter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 股票分析独立 HTTP 端点。
 * <p>
 * 端点：POST /api/v1/trading/analysis
 * <p>
 * 显式调用入口，用户无需意图识别，直接指定股票代码和分析参数。
 * 通过 SSE 流式输出分析过程和最终决策。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/trading")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {
        org.springframework.web.bind.annotation.RequestMethod.GET,
        org.springframework.web.bind.annotation.RequestMethod.POST,
        org.springframework.web.bind.annotation.RequestMethod.OPTIONS
})
public class TradingAnalysisController {

    private final TradingStarter tradingStarter;

    public TradingAnalysisController(TradingStarter tradingStarter) {
        this.tradingStarter = tradingStarter;
    }

    /**
     * 股票分析独立端点。
     *
     * @param request  分析请求参数
     * @param response HTTP 响应
     * @return SSE 流式响应
     */
    @RequestMapping(value = "/analysis", method = RequestMethod.POST,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter analyze(@RequestBody TradingAnalysisRequestDTO request,
                                      HttpServletResponse response) {
        log.info("股票分析请求: ticker={}, analysts={}, debateRounds={}",
                request.getTicker(),
                request.getSelectedAnalysts(),
                request.getMaxDebateRounds());

        if (request.getTicker() == null || request.getTicker().isBlank()) {
            return buildErrorEmitter(response, "股票代码不能为空");
        }

        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Accel-Buffering", "no");
        } catch (Exception e) {
            log.error("设置响应头失败: {}", e.getMessage());
        }

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);

        CompletableFuture.runAsync(() -> {
            try {
                // 发送开始任务标记给前端，展示给用户
                sendStartEvent(emitter, request);

                // 开始执行整个分析流程
                executeAnalysis(request, emitter);

                try {
                    emitter.complete();
                } catch (IllegalStateException e) {
                    log.info("emitter 已在 TradingStarter 中关闭，跳过: {}", e.getMessage());
                } catch (Exception e) {
                    log.warn("emitter 关闭异常: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("股票分析执行异常: {}", e.getMessage(), e);
                try {
                    emitter.send("event: error\ndata: " + JSON.toJSONString(
                            AutoAgentExecuteResultEntity.builder()
                                    .type("error")
                                    .subType("system_error")
                                    .content("分析执行失败: " + e.getMessage())
                                    .timestamp(System.currentTimeMillis())
                                    .build()
                    ) + "\n\n");
                    emitter.complete();
                } catch (Exception ex) {
                    log.error("发送错误事件失败: {}", ex.getMessage());
                }
            }
        });

        return emitter;
    }

    private void executeAnalysis(TradingAnalysisRequestDTO request, ResponseBodyEmitter emitter) {
        String ticker = request.getTicker().toUpperCase().trim();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                .ticker(ticker)
                .tradeDate(request.getTradeDate())
                .selectedAnalysts(request.getSelectedAnalysts() != null && !request.getSelectedAnalysts().isEmpty()
                        ? request.getSelectedAnalysts()
                        : List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
                                AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS))
                .maxDebateRounds(request.getMaxDebateRounds() != null ? request.getMaxDebateRounds() : 2)
                .maxRiskRounds(request.getMaxRiskRounds() != null ? request.getMaxRiskRounds() : 1)
                .sessionId(sessionId)
                .build();

        log.info("交易请求构建完成: ticker={}, analysts={}", tradingRequest.getTicker(), tradingRequest.getSelectedAnalysts());
        try {
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
            dynamicContext.setValue("emitter", emitter);
            dynamicContext.setValue("step", 1);

            BiConsumerSseSender sseSender = new BiConsumerSseSender(emitter, dynamicContext);

            tradingStarter.start(tradingRequest, dynamicContext, sseSender);
        } catch (Exception e) {
            log.error("交易分析执行异常: ticker={}, error={}", ticker, e.getMessage(), e);
            sendEvent(emitter, "error", "分析失败: " + e.getMessage());
        }
    }

    private void sendStartEvent(ResponseBodyEmitter emitter, TradingAnalysisRequestDTO request) {
        try {
            String eventData = JSON.toJSONString(AutoAgentExecuteResultEntity.builder()
                    .type("progress")
                    .subType("analysis_start")
                    .content("开始分析股票: " + request.getTicker())
                    .timestamp(System.currentTimeMillis())
                    .build());
            emitter.send("event: progress\ndata: " + eventData + "\n\n");
        } catch (Exception e) {
            log.error("发送开始事件失败: {}", e.getMessage());
        }
    }

    private void sendEvent(ResponseBodyEmitter emitter, String eventType, String content) {
        try {
            String eventData = JSON.toJSONString(AutoAgentExecuteResultEntity.builder()
                    .type(eventType)
                    .subType(eventType)
                    .content(content)
                    .timestamp(System.currentTimeMillis())
                    .build());
            emitter.send("event: " + eventType + "\ndata: " + eventData + "\n\n");
        } catch (Exception e) {
            log.error("发送SSE事件失败: {}", e.getMessage());
        }
    }

    private ResponseBodyEmitter buildErrorEmitter(HttpServletResponse response, String message) {
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            errorEmitter.send("{\"error\":\"" + message + "\"}");
            errorEmitter.complete();
            return errorEmitter;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SSE 发送器适配器，将 BiConsumer 转换为 SSE 格式发送
     */
    private static class BiConsumerSseSender implements java.util.function.BiConsumer<String, Object> {
        private final ResponseBodyEmitter emitter;
        private final DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

        BiConsumerSseSender(ResponseBodyEmitter emitter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            this.emitter = emitter;
            this.dynamicContext = dynamicContext;
        }

        @Override
        public void accept(String type, Object event) {
            try {
                String eventData;
                if (event instanceof AutoAgentExecuteResultEntity entity) {
                    entity.setStep(dynamicContext.getValue("step") != null ? (Integer) dynamicContext.getValue("step") : 0);
                    eventData = JSON.toJSONString(entity);
                } else {
                    eventData = JSON.toJSONString(event);
                }
                emitter.send("event: " + type + "\ndata: " + eventData + "\n\n");
            } catch (Exception e) {
                log.warn("SSE 发送失败，断连或客户端异常: {}", e.getMessage());
            }
        }
    }
}

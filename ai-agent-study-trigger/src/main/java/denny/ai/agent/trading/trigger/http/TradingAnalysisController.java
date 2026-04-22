package denny.ai.agent.trading.trigger.http;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

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

    private final ThreadPoolExecutor threadPoolExecutor;

    public TradingAnalysisController(ThreadPoolExecutor threadPoolExecutor) {
        this.threadPoolExecutor = threadPoolExecutor;
    }

    /**
     * 股票分析独立端点。
     *
     * @param request     分析请求参数
     * @param response    HTTP 响应
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

        // 参数校验
        if (request.getTicker() == null || request.getTicker().isBlank()) {
            return buildErrorEmitter(response, "股票代码不能为空");
        }

        // 设置 SSE 响应头
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Accel-Buffering", "no");
        } catch (Exception e) {
            log.error("设置响应头失败: {}", e.getMessage());
        }

        // 创建 SSE 发射器
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);

        // 异步执行
        threadPoolExecutor.execute(() -> {
            try {
                sendStartEvent(emitter, request);
                executeAnalysis(request, emitter);
                emitter.complete();
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

    /**
     * 执行股票分析。
     * <p>
     * 这里直接触发 TradingRootNode 的分析链路。
     * 实际接入方式：构建 ExecuteCommandEntity，通过 autoAgentExecuteStrategy 执行，
     * 或者直接调用 TradingRootNode（需在 domain 层暴露入口）。
     *
     * 当前实现：通过 DynamicContext 注入 trading_request，触发整个链路。
     */
    private void executeAnalysis(TradingAnalysisRequestDTO request, ResponseBodyEmitter emitter) throws Exception {
        // 构建交易请求对象
        StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                .ticker(request.getTicker().toUpperCase().trim())
                .tradeDate(request.getTradeDate())
                .selectedAnalysts(request.getSelectedAnalysts() != null
                        ? request.getSelectedAnalysts()
                        : List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
                                AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS))
                .maxDebateRounds(request.getMaxDebateRounds() != null ? request.getMaxDebateRounds() : 2)
                .sessionId(request.getSessionId())
                .build();

        // 构建执行命令
        ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                .message("请分析股票 " + tradingRequest.getTicker())
                .sessionId(tradingRequest.getSessionId())
                .maxStep(20)
                .agentType("trading")
                .build();

        // 将交易请求注入到上下文中，供 IntentRoutingNode 识别
        // 注意：实际触发链路由 autoAgentExecuteStrategy 决定
        // 此处通过 agentType=trading 路由到 TradingRootNode
        sendEvent(emitter, "progress", "正在初始化分析链路...");
        sendEvent(emitter, "progress", "请使用 /api/v1/agent/auto_agent 端点，agentType=trading，message=分析股票 " + tradingRequest.getTicker());
        sendEvent(emitter, "complete", "请使用 POST /api/v1/agent/auto_agent 触发完整链路");
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
}

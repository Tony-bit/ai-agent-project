package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.node.*;
import denny.ai.agent.trading.domain.guard.DataSanityGuard;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 交易状态机核心调度器。
 * <p>
 * 职责：
 * <ul>
 *   <li>接收节点末尾的事件调用</li>
 *   <li>switch-case 分派到对应阶段 handler</li>
 *   <li>handler 中同步调用节点（含 60s 超时保护）</li>
 *   <li>节点异常统一捕获，发送 ERROR SSE</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>Dispatcher 是 Spring Bean，持有所有节点引用</li>
 *   <li>节点 doApply() 由 Dispatcher 主动调用，不再由节点反射驱动</li>
 *   <li>节点末尾调用 TradingDriver，Driver 委托 Dispatcher.onEvent()</li>
 *   <li>TradingStateContext 通过方法参数传入，非构造器注入</li>
 * </ul>
 */
@Slf4j
@Component
public class TradingDispatcher {

    @Resource private FundamentalAnalystNode fundamentalAnalystNode;
    @Resource private TechnicalAnalystNode technicalAnalystNode;
    @Resource private SentimentAnalystNode sentimentAnalystNode;
    @Resource private NewsAnalystNode newsAnalystNode;
    @Resource private BullResearcherNode bullResearcherNode;
    @Resource private BearResearcherNode bearResearcherNode;

    @Resource
    private ResearchManagerNode researchManagerNode;

    @Resource
    private RecommendationNode recommendationNode;

    @Resource
    private AggressiveRiskAnalystNode aggressiveRiskAnalystNode;

    @Resource
    private ConservativeRiskAnalystNode conservativeRiskAnalystNode;

    @Resource
    private NeutralRiskAnalystNode neutralRiskAnalystNode;

    @Resource
    private PortfolioManagerNode portfolioManagerNode;

    @Resource(name = "tradingTaskExecutor")
    private ThreadPoolExecutor tradingTaskExecutor;

    @Resource
    private DataSanityGuard dataSanityGuard;

    @Resource
    private TradingAgentProperties tradingAgentProperties;

    /**
     * 核心事件处理入口
     */
    public void onEvent(TradingEvent event, TradingStateContext stateContext) {
        TradingPhase current = stateContext.getCurrentPhase();
        log.info("Dispatcher 收到事件: event={}, 当前阶段={}", event, current);
        try {
            switch (current) {
                case INIT -> handleInit(event, stateContext);
                case ANALYST_COLLECTION -> handleAnalystCollection(event, stateContext);
                case INVESTMENT_DEBATE -> handleInvestmentDebate(event, stateContext);
                case RECOMMENDATION_DECISION -> handleRecommendationDecision(event, stateContext);
                case RISK_MANAGEMENT -> handleRiskManagement(event, stateContext);
                case FINAL_REPORT -> handleFinalReport(event, stateContext);
                case ERROR -> { /* 终止状态，不处理 */ }
            }
        } catch (Exception e) {
            log.error("阶段处理异常: phase={}, event={}", current, event, e);
            stateContext.sendError("阶段处理异常: " + e.getMessage());
        }
    }


    private void handleInit(TradingEvent event, TradingStateContext stateContext) {
        if (event == TradingEvent.START_TRADING) {
            stateContext.transitionTo(TradingPhase.ANALYST_COLLECTION);
            stateContext.sendSseResult("trading", "trading_init",
                    "交易分析开始", false);
            invokeAnalystsInParallel(stateContext, stateContext.getSelectedAnalysts());
        }
    }

    private void handleAnalystCollection(TradingEvent event, TradingStateContext stateContext) {
        if (event == TradingEvent.START_TRADING) {
            invokeAnalystsInParallel(stateContext, stateContext.getSelectedAnalysts());
        }
    }

    private void handleInvestmentDebate(TradingEvent event, TradingStateContext stateContext) {
        switch (event) {
            case INVESTMENT_DEBATE_COMPLETE -> {
                TradingContextVO.InvestmentDebateVO debate = stateContext.getTradingContext().getInvestmentDebate();
                String latest = stateContext.getLatestDebateSpeaker();
                if (latest == null) latest = "";
                switch (latest) {
                    case "BULL" -> {
                        stateContext.setLatestDebateSpeaker("BEAR");
                        invokeNodeAsync(
                            () -> {
                                bearResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                                return null;
                            },
                            stateContext,
                            () -> TradingDispatcher.this.onEvent(TradingEvent.INVESTMENT_DEBATE_COMPLETE, stateContext)
                        );
                    }
                    case "BEAR" -> {
                        stateContext.setLatestDebateSpeaker("RESEARCH_MANAGER");
                        invokeNodeAsync(
                            () -> {
                                researchManagerNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                                return null;
                            },
                            stateContext,
                            () -> TradingDispatcher.this.onEvent(TradingEvent.INVESTMENT_DEBATE_COMPLETE, stateContext)
                        );
                    }
                    case "RESEARCH_MANAGER" -> {
                        if (debate != null && debate.isNeedMoreDebate()
                                && debate.getCurrentRound() < debate.getMaxRounds()) {
                            debate.nextRound();
                            stateContext.setLatestDebateSpeaker("BULL");
                            invokeNodeAsync(
                                () -> {
                                    bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                                    return null;
                                },
                                stateContext,
                                () -> TradingDispatcher.this.onEvent(TradingEvent.INVESTMENT_DEBATE_COMPLETE, stateContext)
                            );
                        } else {
                            log.info("辩论结束");
                        }
                    }
                    default -> log.warn("latestDebateSpeaker 收到意外事件: {}", event);
                }
            }
            case CONTINUE_DEBATE -> {
                TradingContextVO.InvestmentDebateVO debate = stateContext.getTradingContext().getInvestmentDebate();
                if (debate != null) {
                    debate.nextRound();
                }
                stateContext.setLatestDebateSpeaker("BULL");
            }
            case DEBATE_FINISH -> {
                stateContext.transitionTo(TradingPhase.RECOMMENDATION_DECISION);
                stateContext.sendSseResult("debate", "debate_complete", "辩论结束，进入推荐决策", false);
                invokeRecommendation(stateContext);
            }
            default -> log.warn("INVESTMENT_DEBATE 阶段收到意外事件: {}", event);
        }
    }

    private void handleRecommendationDecision(TradingEvent event, TradingStateContext stateContext) {
        if (event == TradingEvent.RECOMMENDATION_COMPLETE) {
            stateContext.transitionTo(TradingPhase.RISK_MANAGEMENT);
            stateContext.sendSseResult("risk", "risk_start", "风控阶段开始", false);
            stateContext.setLatestRiskSpeaker("AGGRESSIVE");
            invokeNodeAsync(
                () -> {
                    aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                    return null;
                },
                stateContext,
                null
            );
        } else {
            log.warn("RECOMMENDATION_DECISION 阶段收到意外事件: {}", event);
        }
    }

    private void handleRiskManagement(TradingEvent event, TradingStateContext stateContext) {
        if (event == TradingEvent.RISK_DEBATE_COMPLETE) {
            TradingContextVO.RiskDebateVO riskDebate = stateContext.getTradingContext().getRiskDebate();
            String latest = stateContext.getLatestRiskSpeaker();
            if (latest == null) latest = "";
            switch (latest) {
                case "AGGRESSIVE" -> {
                    stateContext.setLatestRiskSpeaker("CONSERVATIVE");
                    invokeNodeAsync(
                        () -> {
                            conservativeRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                            return null;
                        },
                        stateContext,
                        null
                    );
                }
                case "CONSERVATIVE" -> {
                    stateContext.setLatestRiskSpeaker("NEUTRAL");
                    invokeNodeAsync(
                        () -> {
                            neutralRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                            return null;
                        },
                        stateContext,
                        null
                    );
                }
                case "NEUTRAL" -> {
                    int totalExchanges = riskDebate != null ? riskDebate.getTotalExchangeCount() : 0;
                    int maxRounds = riskDebate != null ? riskDebate.getMaxRounds() : 1;
                    if (totalExchanges >= 3 * maxRounds) {
                        log.info("风控轮次耗尽，进入最终报告");
                        stateContext.transitionTo(TradingPhase.FINAL_REPORT);
                        stateContext.sendSseResult("final", "final_report_start", "最终报告生成中", false);
                        invokeNodeAsync(
                            () -> {
                                portfolioManagerNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                                return null;
                            },
                            stateContext,
                            () -> TradingDispatcher.this.onEvent(TradingEvent.PORTFOLIO_COMPLETE, stateContext)
                        );
                    } else {
                        stateContext.setLatestRiskSpeaker("AGGRESSIVE");
                        invokeNodeAsync(
                            () -> {
                                aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                                return null;
                            },
                            stateContext,
                            null
                        );
                    }
                }
                default -> {
                    stateContext.setLatestRiskSpeaker("AGGRESSIVE");
                    invokeNodeAsync(
                        () -> {
                            aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                            return null;
                        },
                        stateContext,
                        null
                    );
                }
            }
        }
    }

    private void handleFinalReport(TradingEvent event, TradingStateContext stateContext) {
        log.info("handleFinalReport 收到事件: {}", event);
        if (event == TradingEvent.PORTFOLIO_COMPLETE) {
            stateContext.countDownTaskLatch();
        }
    }


    private void handleAllAnalystsComplete(TradingStateContext stateContext) {
        log.info("所有分析师执行完毕，进入辩论阶段");
        if (dataSanityGuard != null) {
            stateContext.getTradingContext().setDataWarnings(
                    dataSanityGuard.check(stateContext.getTradingContext()));
        }
        stateContext.transitionTo(TradingPhase.INVESTMENT_DEBATE);
        stateContext.sendSseResult("debate", "debate_start", "辩论阶段开始", false);
        StockAnalysisRequestVO request = stateContext.getRequest();
        int maxRounds = (request != null && request.getMaxDebateRounds() > 0) ? request.getMaxDebateRounds() : 2;
        TradingContextVO.InvestmentDebateVO debate = TradingContextVO.InvestmentDebateVO.createNew(maxRounds);
        stateContext.getTradingContext().setInvestmentDebate(debate);
        stateContext.setLatestDebateSpeaker("BULL");
        log.info("start bull 做多任务投放");
        invokeNodeAsync(
            () -> {
                bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                return null;
            },
            stateContext,
            () -> TradingDispatcher.this.onEvent(TradingEvent.INVESTMENT_DEBATE_COMPLETE, stateContext)
        );
    }

    private void invokeRecommendation(TradingStateContext stateContext) {
        stateContext.getTradingContext().setRiskDebate(new TradingContextVO.RiskDebateVO());
        StockAnalysisRequestVO request = stateContext.getRequest();
        int maxRiskRounds = (request != null && request.getMaxRiskRounds() > 0) ? request.getMaxRiskRounds() : 1;
        stateContext.getTradingContext().getRiskDebate().setMaxRounds(maxRiskRounds);
        invokeNodeAsync(
            () -> {
                recommendationNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                return null;
            },
            stateContext,
            null
        );
    }

    private void invokeAnalystsInParallel(TradingStateContext stateContext,
                                         List<AnalystTypeEnum> analysts) {
        log.info("分析师并行执行: {}", analysts);

        List<CompletableFuture<Void>> futures = analysts.stream()
            .map(analyst -> CompletableFuture.runAsync(() -> {
                try {

                    invokeAnalystNode(analyst, stateContext);
                } catch (Exception e) {
                    log.error("分析师执行异常: analyst={}", analyst, e);
                }
            }, tradingTaskExecutor))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .orTimeout(nodeTimeoutMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((result, ex) -> {

                if (ex != null) {
                    log.error("分析师并行执行异常", ex);
                    stateContext.sendError("分析师执行异常: " + ex.getMessage());
                }
                handleAllAnalystsComplete(stateContext);
                // ★ 不在此处倒计时，辩论节点异步执行，emitter 需要保持打开直到辩论阶段结束
                // ★ latch 倒计时移至 handleFinalReport() 中
            });
    }

    private void invokeAnalystNode(AnalystTypeEnum analyst, TradingStateContext stateContext) {
        try {
            switch (analyst) {
                case FUNDAMENTAL -> fundamentalAnalystNode.doApply(
                        new ExecuteCommandEntity(), stateContext.getDynamicContext());
                case TECHNICAL -> technicalAnalystNode.doApply(
                        new ExecuteCommandEntity(), stateContext.getDynamicContext());
                case SENTIMENT -> sentimentAnalystNode.doApply(
                        new ExecuteCommandEntity(), stateContext.getDynamicContext());
                case NEWS -> newsAnalystNode.doApply(
                        new ExecuteCommandEntity(), stateContext.getDynamicContext());
            }
        } catch (Exception e) {
            log.error("分析师执行异常: analyst={}", analyst, e);
            stateContext.sendError("分析师执行异常: " + e.getMessage());
        }
    }

    private void invokeNodeAsync(Callable<Void> nodeAction,
                                 TradingStateContext stateContext,
                                 Runnable onComplete) {

        // onComplete is only for dispatcher-owned transitions. Nodes that call TradingDriver themselves pass null.
        CompletableFuture.<Void>runAsync(() -> {
            try {

                log.info("节点执行开始: {}", nodeAction.getClass().getSimpleName());
                nodeAction.call();
                log.info("节点执行结束: {}", nodeAction.getClass().getSimpleName());
            } catch (Exception e) {
                log.error("节点执行异常", e);
                stateContext.sendError("节点执行异常: " + e.getMessage());
            }
        }, tradingTaskExecutor)
        .orTimeout(nodeTimeoutMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((result, ex) -> {

            if (ex != null && !(ex.getCause() instanceof TimeoutException)) {
                log.error("invokeNodeAsync 执行失败", ex);
                return;
            }
            if (onComplete != null) {
                try {
                    onComplete.run();
                } catch (Exception e) {
                    log.error("回调执行异常", e);
                    stateContext.sendError("回调执行异常: " + e.getMessage());
                }
            }
        });
    }

    private void invokeNode(Callable<Void> nodeAction, TradingStateContext stateContext) {

        try {
            CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                try {

                    log.info("节点执行开始: {}", nodeAction.getClass().getSimpleName());
                    nodeAction.call();
                    log.info("节点执行结束: {}", nodeAction.getClass().getSimpleName());

                    return null;
                } catch (Exception e) {
                    log.error("节点执行异常", e);
                    stateContext.sendError("节点执行异常: " + e.getMessage());
                    return null;
                }
            }, tradingTaskExecutor);
            future.get(nodeTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("节点执行被中断", e);
            stateContext.sendError("节点执行被中断: " + e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.error("节点执行超时或异常", e);
            stateContext.sendError("节点执行超时或异常: " + e.getMessage());
        }
    }

    private long nodeTimeoutMillis() {
        TradingAgentProperties effective = tradingAgentProperties == null
                ? new TradingAgentProperties() : tradingAgentProperties;
        effective.validate();
        return effective.getNodeTimeout().toMillis();
    }
}

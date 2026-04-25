package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.node.*;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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

    private static final int NODE_TIMEOUT_SECONDS = 60;

    @Resource private FundamentalAnalystNode fundamentalAnalystNode;
    @Resource private TechnicalAnalystNode technicalAnalystNode;
    @Resource private SentimentAnalystNode sentimentAnalystNode;
    @Resource private NewsAnalystNode newsAnalystNode;
    @Resource private BullResearcherNode bullResearcherNode;
    @Resource private BearResearcherNode bearResearcherNode;
    @Resource private ResearchManagerNode researchManagerNode;
    @Resource private TraderNode traderNode;
    @Resource private AggressiveRiskAnalystNode aggressiveRiskAnalystNode;
    @Resource private ConservativeRiskAnalystNode conservativeRiskAnalystNode;
    @Resource private NeutralRiskAnalystNode neutralRiskAnalystNode;
    @Resource private PortfolioManagerNode portfolioManagerNode;

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
                case TRADER_DECISION -> handleTraderDecision(event, stateContext);
                case RISK_MANAGEMENT -> handleRiskManagement(event, stateContext);
                case FINAL_REPORT -> handleFinalReport(event, stateContext);
                case ERROR -> { /* 终止状态，不处理 */ }
            }
        } catch (Exception e) {
            log.error("阶段处理异常: phase={}, event={}", current, event, e);
            stateContext.sendError("阶段处理异常: " + e.getMessage());
        }
    }

    // ===== 阶段 Handler =====

    private void handleInit(TradingEvent event, TradingStateContext stateContext) {
        if (event == TradingEvent.START_TRADING) {
            stateContext.transitionTo(TradingPhase.ANALYST_COLLECTION);
            stateContext.sendSseResult("trading", "trading_init",
                    "交易分析开始", false);
            invokeNextAnalyst(stateContext);
        }
    }

    private void handleAnalystCollection(TradingEvent event, TradingStateContext stateContext) {
        switch (event) {
            case ANALYST_COMPLETE -> {
                int nextIndex = stateContext.getAnalystIndex() + 1;
                stateContext.setAnalystIndex(nextIndex);
                if (nextIndex < stateContext.getSelectedAnalysts().size()) {
                    invokeNextAnalyst(stateContext);
                } else {
                    handleAllAnalystsComplete(stateContext);
                }
            }
            case ALL_ANALYSTS_COMPLETE -> handleAllAnalystsComplete(stateContext);
            default -> log.warn("ANALYST_COLLECTION 阶段收到意外事件: {}", event);
        }
    }

    private void handleInvestmentDebate(TradingEvent event, TradingStateContext stateContext) {
        switch (event) {
            case INVESTMENT_DEBATE_COMPLETE -> {
                TradingContextVO.InvestmentDebateVO debate = stateContext.getTradingContext().getInvestmentDebate();
                String latest = stateContext.getLatestDebateSpeaker();
                if (latest == null) latest = "";
                switch (latest) {
                    case "BULL" -> invokeNode(() -> {
                        stateContext.setLatestDebateSpeaker("BEAR");
                        bearResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                        return null;
                    }, stateContext);
                    case "BEAR" -> invokeNode(() -> {
                        stateContext.setLatestDebateSpeaker("RESEARCH_MANAGER");
                        researchManagerNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                        return null;
                    }, stateContext);
                    case "RESEARCH_MANAGER" -> {
                        if (debate != null && debate.isNeedMoreDebate()
                                && debate.getCurrentRound() < debate.getMaxRounds()) {
                            invokeNode(() -> {
                                debate.nextRound();
                                stateContext.setLatestDebateSpeaker("BULL");
                                bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                                return null;
                            }, stateContext);
                        } else {
                            log.info("辩论结束");
                        }
                    }
                    default -> invokeNode(() -> {
                        stateContext.setLatestDebateSpeaker("BULL");
                        bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                        return null;
                    }, stateContext);
                }
            }
            case CONTINUE_DEBATE -> {
                TradingContextVO.InvestmentDebateVO debate = stateContext.getTradingContext().getInvestmentDebate();
                if (debate != null) {
                    debate.nextRound();
                }
                stateContext.setLatestDebateSpeaker("BULL");
                invokeNode(() -> {
                    bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                    return null;
                }, stateContext);
            }
            case DEBATE_FINISH -> {
                stateContext.transitionTo(TradingPhase.TRADER_DECISION);
                stateContext.sendSseResult("debate", "debate_complete", "辩论结束，进入交易员决策", false);
                invokeTrader(stateContext);
            }
            default -> log.warn("INVESTMENT_DEBATE 阶段收到意外事件: {}", event);
        }
    }

    private void handleTraderDecision(TradingEvent event, TradingStateContext stateContext) {
        if (event == TradingEvent.TRADER_COMPLETE) {
            stateContext.transitionTo(TradingPhase.RISK_MANAGEMENT);
            stateContext.sendSseResult("risk", "risk_start", "风控阶段开始", false);
            stateContext.setLatestRiskSpeaker("AGGRESSIVE");
            invokeNode(() -> {
                aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                return null;
            }, stateContext);
        } else {
            log.warn("TRADER_DECISION 阶段收到意外事件: {}", event);
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
                    invokeNode(() -> {
                        conservativeRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                        return null;
                    }, stateContext);
                }
                case "CONSERVATIVE" -> {
                    stateContext.setLatestRiskSpeaker("NEUTRAL");
                    invokeNode(() -> {
                        neutralRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                        return null;
                    }, stateContext);
                }
                case "NEUTRAL" -> {
                    int totalExchanges = riskDebate != null ? riskDebate.getTotalExchangeCount() : 0;
                    int maxRounds = riskDebate != null ? riskDebate.getMaxRounds() : 1;
                    if (totalExchanges >= 3 * maxRounds) {
                        log.info("风控轮次耗尽，进入最终报告");
                        invokeNode(() -> {
                            stateContext.transitionTo(TradingPhase.FINAL_REPORT);
                            stateContext.sendSseResult("final", "final_report_start", "最终报告生成中", false);
                            portfolioManagerNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                            return null;
                        }, stateContext);
                    } else {
                        stateContext.setLatestRiskSpeaker("AGGRESSIVE");
                        invokeNode(() -> {
                            aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                            return null;
                        }, stateContext);
                    }
                }
                default -> {
                    stateContext.setLatestRiskSpeaker("AGGRESSIVE");
                    invokeNode(() -> {
                        aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                        return null;
                    }, stateContext);
                }
            }
        } else if (event == TradingEvent.PORTFOLIO_COMPLETE) {
            stateContext.transitionTo(TradingPhase.FINAL_REPORT);
            invokeNode(() -> {
                portfolioManagerNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                return null;
            }, stateContext);
        } else {
            log.warn("RISK_MANAGEMENT 阶段收到意外事件: {}", event);
        }
    }

    /**
     * FINAL_REPORT 是终止状态，空实现，保留为扩展点。
     * 正常流程结束时的 completed=true SSE 由 Starter.finally 发送。
     */
    private void handleFinalReport(TradingEvent event, TradingStateContext stateContext) {
    }

    // ===== 私有辅助方法 =====

    private void invokeNextAnalyst(TradingStateContext stateContext) {
        List<AnalystTypeEnum> analysts = stateContext.getSelectedAnalysts();
        int index = stateContext.getAnalystIndex();
        if (index >= analysts.size()) {
            handleAllAnalystsComplete(stateContext);
            return;
        }
        AnalystTypeEnum analyst = analysts.get(index);
        log.info("执行分析师: {}, 索引: {}/{}", analyst, index + 1, analysts.size());
        invokeNode(() -> {
            switch (analyst) {
                case FUNDAMENTAL -> {
                    fundamentalAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                }
                case TECHNICAL -> {
                    technicalAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                }
                case SENTIMENT -> {
                    sentimentAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                }
                case NEWS -> {
                    newsAnalystNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
                }
            }
            return null;
        }, stateContext);
    }

    private void handleAllAnalystsComplete(TradingStateContext stateContext) {
        log.info("所有分析师执行完毕，进入辩论阶段");
        stateContext.transitionTo(TradingPhase.INVESTMENT_DEBATE);
        stateContext.sendSseResult("debate", "debate_start", "辩论阶段开始", false);
        StockAnalysisRequestVO request = stateContext.getRequest();
        int maxRounds = (request != null && request.getMaxDebateRounds() > 0) ? request.getMaxDebateRounds() : 2;
        TradingContextVO.InvestmentDebateVO debate = TradingContextVO.InvestmentDebateVO.createNew(maxRounds);
        stateContext.getTradingContext().setInvestmentDebate(debate);
        stateContext.setLatestDebateSpeaker("BULL");
        invokeNode(() -> {
            bullResearcherNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
            return null;
        }, stateContext);
    }

    private void invokeTrader(TradingStateContext stateContext) {
        stateContext.getTradingContext().setRiskDebate(new TradingContextVO.RiskDebateVO());
        StockAnalysisRequestVO request = stateContext.getRequest();
        int maxRiskRounds = (request != null && request.getMaxRiskRounds() > 0) ? request.getMaxRiskRounds() : 1;
        stateContext.getTradingContext().getRiskDebate().setMaxRounds(maxRiskRounds);
        invokeNode(() -> {
            traderNode.doApply(new ExecuteCommandEntity(), stateContext.getDynamicContext());
            return null;
        }, stateContext);
    }

    private void invokeNode(Callable<Void> nodeAction, TradingStateContext stateContext) {
        try {
            CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                try {
                    nodeAction.call();
                    return null;
                } catch (Exception e) {
                    log.error("节点执行异常", e);
                    stateContext.sendError("节点执行异常: " + e.getMessage());
                    return null;
                }
            });
            future.get(NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("节点执行被中断", e);
            stateContext.sendError("节点执行被中断: " + e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.error("节点执行超时或异常", e);
            stateContext.sendError("节点执行超时或异常: " + e.getMessage());
        }
    }
}

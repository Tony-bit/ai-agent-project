package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.*;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradingStageFlowTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        TradingDriver.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void pipelineRunsStubStagesWithoutLegacyDriverCallbacks() {
        executor = Executors.newFixedThreadPool(4);
        TradingNodeInvoker invoker = new TradingNodeInvoker(executor);
        List<String> calls = new ArrayList<>();
        TradingStateContext context = createContext(calls);

        TradingPipeline pipeline = new TradingPipeline(List.of(
                new AnalystCollectionStage(
                        new FundamentalStub(calls),
                        new TechnicalStub(calls),
                        new SentimentStub(calls),
                        new NewsStub(calls),
                        executor),
                new InvestmentDebateStage(
                        new BullStub(calls),
                        new BearStub(calls),
                        new ResearchManagerStub(calls),
                        invoker),
                new RecommendationStage(new RecommendationStub(calls), invoker),
                new RiskManagementStage(
                        new AggressiveStub(calls),
                        new ConservativeStub(calls),
                        new NeutralStub(calls),
                        invoker),
                new FinalReportStage(new PortfolioStub(calls), invoker)
        ));

        pipeline.execute(context);

        assertEquals(List.of(
                "sse:trading",
                "fundamental",
                "sse:debate",
                "bull",
                "bear",
                "research_manager",
                "sse:debate",
                "recommendation",
                "sse:risk",
                "aggressive",
                "conservative",
                "neutral",
                "sse:final",
                "portfolio"
        ), calls);
        assertEquals(TradingPhase.FINAL_REPORT, context.getCurrentPhase());
        assertNotNull(context.getTradingContext().getFinalDecision());
        assertNull(TradingDriver.getCurrent());
    }

    private TradingStateContext createContext(List<String> calls) {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        request.setSelectedAnalysts(List.of(AnalystTypeEnum.FUNDAMENTAL));
        request.setMaxDebateRounds(1);
        request.setMaxRiskRounds(1);

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        TradingStateContext context = new TradingStateContext(
                request,
                dynamicContext,
                (type, event) -> calls.add("sse:" + type)
        );
        dynamicContext.setValue("trading_context", context.getTradingContext());
        return context;
    }

    private abstract static class DriverFreeNode {
        void assertNoDriver() {
            assertNull(TradingDriver.getCurrent(), "pipeline nodes should not see a legacy TradingDriver");
        }
    }

    private static class FundamentalStub extends FundamentalAnalystNode {
        private final List<String> calls;

        private FundamentalStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("fundamental");
            return "fundamental_done";
        }
    }

    private static class TechnicalStub extends TechnicalAnalystNode {
        private final List<String> calls;

        private TechnicalStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            calls.add("technical");
            return "technical_done";
        }
    }

    private static class SentimentStub extends SentimentAnalystNode {
        private final List<String> calls;

        private SentimentStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            calls.add("sentiment");
            return "sentiment_done";
        }
    }

    private static class NewsStub extends NewsAnalystNode {
        private final List<String> calls;

        private NewsStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            calls.add("news");
            return "news_done";
        }
    }

    private static class BullStub extends BullResearcherNode {
        private final List<String> calls;

        private BullStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("bull");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.getInvestmentDebate().addBullArgument("bull opinion");
            return "bull_done";
        }
    }

    private static class BearStub extends BearResearcherNode {
        private final List<String> calls;

        private BearStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("bear");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.getInvestmentDebate().addBearArgument("bear opinion");
            return "bear_done";
        }
    }

    private static class ResearchManagerStub extends ResearchManagerNode {
        private final List<String> calls;

        private ResearchManagerStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("research_manager");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.getInvestmentDebate().setNeedMoreDebate(false);
            context.getInvestmentDebate().setConclusion("debate conclusion");
            return "research_done";
        }
    }

    private static class RecommendationStub extends RecommendationNode {
        private final List<String> calls;

        private RecommendationStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("recommendation");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.setInvestmentPlan(new TradingContextVO.InvestmentPlanVO());
            return "recommendation_done";
        }
    }

    private static class AggressiveStub extends AggressiveRiskAnalystNode {
        private final List<String> calls;

        private AggressiveStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("aggressive");
            return "aggressive_done";
        }
    }

    private static class ConservativeStub extends ConservativeRiskAnalystNode {
        private final List<String> calls;

        private ConservativeStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("conservative");
            return "conservative_done";
        }
    }

    private static class NeutralStub extends NeutralRiskAnalystNode {
        private final List<String> calls;

        private NeutralStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("neutral");
            return "neutral_done";
        }
    }

    private static class PortfolioStub extends PortfolioManagerNode {
        private final List<String> calls;

        private PortfolioStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String doApply(ExecuteCommandEntity requestParameter,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("portfolio");
            TradingContextVO context = dynamicContext.getValue("trading_context");
            context.setFinalDecision(TradingContextVO.FinalTradeDecisionVO.builder()
                    .decision("HOLD")
                    .confidence("MEDIUM")
                    .reasoning("stub")
                    .build());
            return "portfolio_done";
        }
    }
}

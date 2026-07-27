package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.payload.RiskAssessmentPayload;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.*;
import denny.ai.agent.trading.domain.guard.DataSanityGuard;
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
                        executor,
                        new RecordingDataSanityGuard(calls)),
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
                "sse:analyst",
                "data_sanity_guard",
                "sse:debate",
                "bull",
                "sse:debate",
                "bear",
                "sse:debate",
                "research_manager",
                "sse:debate",
                "recommendation",
                "sse:recommendation",
                "sse:risk",
                "aggressive",
                "sse:risk_debate",
                "conservative",
                "sse:risk_debate",
                "neutral",
                "sse:risk_debate",
                "sse:final",
                "portfolio",
                "sse:final"
        ), calls);
        assertEquals(TradingPhase.FINAL_REPORT, context.getCurrentPhase());
        assertNotNull(context.getTradingContext().getFinalDecision());
        assertNull(TradingDriver.getCurrent());
    }

    @Test
    void identityPollutionStopsBeforeDebateRecommendationRiskAndPortfolio() {
        executor = Executors.newFixedThreadPool(4);
        TradingNodeInvoker invoker = new TradingNodeInvoker(executor);
        List<String> calls = new ArrayList<>();
        TradingStateContext context = createContext(calls);
        TradingPipeline pipeline = new TradingPipeline(List.of(
                new AnalystCollectionStage(
                        new PollutedFundamentalStub(calls),
                        new TechnicalStub(calls),
                        new SentimentStub(calls),
                        new NewsStub(calls), executor, new RecordingDataSanityGuard(calls)),
                new InvestmentDebateStage(
                        new BullStub(calls), new BearStub(calls), new ResearchManagerStub(calls), invoker),
                new RecommendationStage(new RecommendationStub(calls), invoker),
                new RiskManagementStage(
                        new AggressiveStub(calls), new ConservativeStub(calls), new NeutralStub(calls), invoker),
                new FinalReportStage(new PortfolioStub(calls), invoker)
        ));

        pipeline.execute(context);

        assertEquals(TradingPhase.ERROR, context.getCurrentPhase());
        assertNull(context.getTradingContext().getFundamentalReport());
        assertEquals(List.of("sse:trading", "fundamental", "sse:trading"), calls);
    }

    private TradingStateContext createContext(List<String> calls) {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        request.setSelectedAnalysts(List.of(AnalystTypeEnum.FUNDAMENTAL));
        request.setMaxDebateRounds(1);
        request.setMaxRiskRounds(1);

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        TradingStateContext context = denny.ai.agent.trading.domain.support.TestTargets.stateContext(
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

    private static class RecordingDataSanityGuard extends DataSanityGuard {
        private final List<String> calls;

        private RecordingDataSanityGuard(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<String> check(TradingContextVO context) {
            calls.add("data_sanity_guard");
            return List.of();
        }
    }

    private static class FundamentalStub extends FundamentalAnalystNode {
        private final List<String> calls;

        private FundamentalStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.FundamentalReportVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("fundamental");
            return denny.ai.agent.trading.api.vo.FundamentalReportVO.builder().rating(3).build();
        }
    }

    private static class PollutedFundamentalStub extends FundamentalStub {
        private PollutedFundamentalStub(List<String> calls) {
            super(calls);
        }

        @Override
        public denny.ai.agent.trading.api.vo.FundamentalReportVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            denny.ai.agent.trading.api.vo.FundamentalReportVO report = super.prepare(context, dynamicContext);
            report.setSummary("错误引入 001309 德明利");
            return report;
        }
    }

    private static class TechnicalStub extends TechnicalAnalystNode {
        private final List<String> calls;

        private TechnicalStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.TechnicalReportVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            calls.add("technical");
            return denny.ai.agent.trading.api.vo.TechnicalReportVO.builder().rating(3).build();
        }
    }

    private static class SentimentStub extends SentimentAnalystNode {
        private final List<String> calls;

        private SentimentStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.SentimentReportVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            calls.add("sentiment");
            return denny.ai.agent.trading.api.vo.SentimentReportVO.builder().rating(3).build();
        }
    }

    private static class NewsStub extends NewsAnalystNode {
        private final List<String> calls;

        private NewsStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.NewsReportVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            calls.add("news");
            return denny.ai.agent.trading.api.vo.NewsReportVO.builder().rating(3).build();
        }
    }

    private static class BullStub extends BullResearcherNode {
        private final List<String> calls;

        private BullStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload prepare(TradingContextVO context,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("bull");
            return argument("BULL", "bull opinion");
        }
    }

    private static class BearStub extends BearResearcherNode {
        private final List<String> calls;

        private BearStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload prepare(TradingContextVO context,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("bear");
            return argument("BEAR", "bear opinion");
        }
    }

    private static class ResearchManagerStub extends ResearchManagerNode {
        private final List<String> calls;

        private ResearchManagerStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public denny.ai.agent.trading.api.vo.payload.ResearchManagerPayload prepare(TradingContextVO context,
                                        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("research_manager");
            return new denny.ai.agent.trading.api.vo.payload.ResearchManagerPayload(
                    "DECIDED", 3.0, false, List.of("factor"), List.of(),
                    "debate conclusion", null);
        }
    }

    private static denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload argument(
            String stance, String summary) {
        return new denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload(
                stance,
                List.of(new denny.ai.agent.trading.api.vo.payload.ResearchArgumentPayload.EvidenceArgument(
                        "FACT", "HIGH", summary)),
                List.of(), summary, null);
    }

    private static class RecommendationStub extends RecommendationNode {
        private final List<String> calls;

        private RecommendationStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public TradingContextVO.InvestmentPlanVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("recommendation");
            return new TradingContextVO.InvestmentPlanVO();
        }
    }

    private static class AggressiveStub extends AggressiveRiskAnalystNode {
        private final List<String> calls;

        private AggressiveStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public RiskAssessmentPayload prepare(TradingContextVO context,
                                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("aggressive");
            return risk("AGGRESSIVE", "aggressive opinion");
        }
    }

    private static class ConservativeStub extends ConservativeRiskAnalystNode {
        private final List<String> calls;

        private ConservativeStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public RiskAssessmentPayload prepare(TradingContextVO context,
                                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("conservative");
            return risk("CONSERVATIVE", "conservative opinion");
        }
    }

    private static class NeutralStub extends NeutralRiskAnalystNode {
        private final List<String> calls;

        private NeutralStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public RiskAssessmentPayload prepare(TradingContextVO context,
                                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("neutral");
            return risk("NEUTRAL", "neutral opinion");
        }
    }

    private static RiskAssessmentPayload risk(String perspective, String summary) {
        return new RiskAssessmentPayload(
                perspective, 3, List.of("risk"), List.of("mitigation"), summary, null);
    }

    private static class PortfolioStub extends PortfolioManagerNode {
        private final List<String> calls;

        private PortfolioStub(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public TradingContextVO.FinalTradeDecisionVO prepare(
                TradingContextVO context,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            assertNull(TradingDriver.getCurrent());
            calls.add("portfolio");
            return TradingContextVO.FinalTradeDecisionVO.builder()
                    .decision("HOLD")
                    .confidence("MEDIUM")
                    .reasoning("stub")
                    .build();
        }
    }
}

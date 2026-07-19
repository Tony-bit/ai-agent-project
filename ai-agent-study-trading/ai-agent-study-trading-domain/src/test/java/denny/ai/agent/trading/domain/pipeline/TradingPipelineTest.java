package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingPipelineTest {

    @Test
    void executeRunsStagesInOrderWhenEachStageTransitionsToNextPhase() {
        List<String> calls = new ArrayList<>();
        TradingStateContext context = createContext();
        TradingPipeline pipeline = new TradingPipeline(List.of(
                new StubStage("analyst", TradingPhase.INIT, TradingPhase.INVESTMENT_DEBATE, calls),
                new StubStage("debate", TradingPhase.INVESTMENT_DEBATE, TradingPhase.FINAL_REPORT, calls)
        ));

        pipeline.execute(context);

        assertEquals(List.of("analyst", "debate"), calls);
        assertEquals(TradingPhase.FINAL_REPORT, context.getCurrentPhase());
    }

    @Test
    void executeFailsFastWhenStageStartsFromUnexpectedPhase() {
        TradingStateContext context = createContext();
        TradingPipeline pipeline = new TradingPipeline(List.of(
                new StubStage("debate", TradingPhase.INVESTMENT_DEBATE, TradingPhase.FINAL_REPORT, new ArrayList<>())
        ));

        TradingPipelineException exception = assertThrows(
                TradingPipelineException.class,
                () -> pipeline.execute(context)
        );

        assertEquals("Stage debate expected INVESTMENT_DEBATE but was INIT", exception.getMessage());
    }

    @Test
    void executeFailsFastWhenStageDoesNotTransitionToDeclaredNextPhase() {
        TradingStateContext context = createContext();
        TradingPipeline pipeline = new TradingPipeline(List.of(new NoTransitionStage()));

        TradingPipelineException exception = assertThrows(
                TradingPipelineException.class,
                () -> pipeline.execute(context)
        );

        assertEquals("Stage broken must transition to INVESTMENT_DEBATE but was INIT", exception.getMessage());
    }

    @Test
    void executeStopsWithoutAfterValidationWhenStageEntersError() {
        List<String> calls = new ArrayList<>();
        TradingStateContext context = createContext();
        TradingPipeline pipeline = new TradingPipeline(List.of(
                new ErrorStage(calls),
                new StubStage("should-not-run", TradingPhase.INVESTMENT_DEBATE, TradingPhase.FINAL_REPORT, calls)
        ));

        pipeline.execute(context);

        assertEquals(List.of("error"), calls);
        assertEquals(TradingPhase.ERROR, context.getCurrentPhase());
    }

    private TradingStateContext createContext() {
        StockAnalysisRequestVO request = new StockAnalysisRequestVO();
        request.setTicker("000001");
        return new TradingStateContext(
                request,
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(),
                (type, event) -> {
                    return true;
                }
        );
    }

    private static class StubStage implements TradingStage {
        private final String name;
        private final TradingPhase expectedPhase;
        private final TradingPhase nextPhase;
        private final List<String> calls;

        private StubStage(String name, TradingPhase expectedPhase, TradingPhase nextPhase, List<String> calls) {
            this.name = name;
            this.expectedPhase = expectedPhase;
            this.nextPhase = nextPhase;
            this.calls = calls;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TradingPhase expectedPhase() {
            return expectedPhase;
        }

        @Override
        public TradingPhase nextPhase() {
            return nextPhase;
        }

        @Override
        public void execute(TradingStateContext context) {
            calls.add(name);
            context.transitionTo(nextPhase);
        }
    }

    private static class NoTransitionStage implements TradingStage {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public TradingPhase expectedPhase() {
            return TradingPhase.INIT;
        }

        @Override
        public TradingPhase nextPhase() {
            return TradingPhase.INVESTMENT_DEBATE;
        }

        @Override
        public void execute(TradingStateContext context) {
            // Intentionally leaves the phase unchanged.
        }
    }

    private static class ErrorStage implements TradingStage {
        private final List<String> calls;

        private ErrorStage(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String name() {
            return "error";
        }

        @Override
        public TradingPhase expectedPhase() {
            return TradingPhase.INIT;
        }

        @Override
        public TradingPhase nextPhase() {
            return TradingPhase.INVESTMENT_DEBATE;
        }

        @Override
        public void execute(TradingStateContext context) {
            calls.add("error");
            context.transitionTo(TradingPhase.ERROR);
        }
    }
}

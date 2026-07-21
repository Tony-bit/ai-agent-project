package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnalysisDepthFollowUpResolverTest {

    private final AnalysisDepthFollowUpResolver resolver = new AnalysisDepthFollowUpResolver();

    @Test
    public void resolvesCompleteAnalysisAgainstNearestFinancialRequest() {
        AnalysisDepthFollowUpResolver.Resolution resolution = resolver.resolve(
                "我要进行完整投资分析",
                List.of(
                        "user: 给我分析一下中国平安",
                        "assistant: 你需要快速了解，还是进行完整投资分析？"
                ));

        MultiIntentRoutingResult result = resolver.enforce(null, resolution);

        assertTrue(resolution.resolved());
        assertEquals("给我分析一下中国平安；进行完整投资分析", resolution.effectiveQuery());
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getTaskList().get(0).getIntent());
        assertEquals(resolution.effectiveQuery(), result.getTaskList().get(0).getContent());
        assertFalse(result.getNeedsClarification());
    }

    @Test
    public void resolvesQuickOverviewAgainstNearestFinancialRequest() {
        AnalysisDepthFollowUpResolver.Resolution resolution = resolver.resolve(
                "快速了解",
                List.of(
                        "user: 中国平安最近怎么样",
                        "assistant: 你需要快速了解，还是进行完整投资分析？"
                ));

        MultiIntentRoutingResult result = resolver.enforce(null, resolution);

        assertTrue(resolution.resolved());
        assertEquals(IntentTypeEnum.FINANCIAL_GENERAL, result.getTaskList().get(0).getIntent());
        assertEquals("generalChatNode", result.getTaskList().get(0).getExecutorNode());
    }

    @Test
    public void leavesChoiceUnchangedWhenPriorRequestHasNoEntity() {
        AnalysisDepthFollowUpResolver.Resolution resolution = resolver.resolve(
                "完整投资分析",
                List.of(
                        "user: 帮我分析一下这只股票",
                        "assistant: 你需要快速了解，还是进行完整投资分析？"
                ));

        assertFalse(resolution.resolved());
        assertEquals("完整投资分析", resolution.effectiveQuery());
    }

    @Test
    public void doesNotReachPastNearestEntityFreeRequest() {
        AnalysisDepthFollowUpResolver.Resolution resolution = resolver.resolve(
                "完整投资分析",
                List.of(
                        "user: 给我分析一下中国平安",
                        "assistant: 好的",
                        "user: 帮我分析一下",
                        "assistant: 你需要快速了解，还是进行完整投资分析？"
                ));

        assertFalse(resolution.resolved());
    }

    @Test
    public void doesNotInheritUnrelatedHistoryWithoutFixedClarification() {
        AnalysisDepthFollowUpResolver.Resolution resolution = resolver.resolve(
                "完整投资分析",
                List.of(
                        "user: 给我分析一下中国平安",
                        "assistant: 中国平安是保险与金融服务公司"
                ));

        assertFalse(resolution.resolved());
    }

    @Test
    public void leavesNonChoiceFollowUpUnchanged() {
        AnalysisDepthFollowUpResolver.Resolution resolution = resolver.resolve(
                "先看看财报",
                List.of(
                        "user: 给我分析一下中国平安",
                        "assistant: 你需要快速了解，还是进行完整投资分析？"
                ));

        assertFalse(resolution.resolved());
        assertEquals("先看看财报", resolution.effectiveQuery());
    }
}

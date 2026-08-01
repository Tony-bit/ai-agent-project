package denny.ai.agent.trading.domain.node;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingCollectorCoverageTest {

    private static final List<String> COLLECTOR_NODES = List.of(
            "FundamentalAnalystNode", "TechnicalAnalystNode",
            "SentimentAnalystNode", "NewsAnalystNode",
            "BullResearcherNode", "BearResearcherNode", "ResearchManagerNode",
            "ConservativeRiskAnalystNode", "NeutralRiskAnalystNode",
            "AggressiveRiskAnalystNode", "PortfolioManagerNode",
            "RecommendationNode");

    @Test
    void should_route_all_declared_trading_stream_nodes_through_shared_collector()
            throws IOException {
        Path sourceDirectory = sourceDirectory();

        for (String node : COLLECTOR_NODES) {
            Path sourceFile = sourceDirectory.resolve(node + ".java");
            assertTrue(Files.isRegularFile(sourceFile), "missing node source: " + node);
            String source = Files.readString(sourceFile);
            assertTrue(source.contains("collectStreamingResponse("),
                    node + " must use the shared streaming collector");
            assertFalse(source.contains(".collectList().block("),
                    node + " must not bypass the shared streaming collector");
        }
    }

    private Path sourceDirectory() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path moduleRelative = Path.of("src/main/java/denny/ai/agent/trading/domain/node");
        Path direct = workingDirectory.resolve(moduleRelative);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return workingDirectory.resolve(
                "ai-agent-study-trading/ai-agent-study-trading-domain")
                .resolve(moduleRelative);
    }
}

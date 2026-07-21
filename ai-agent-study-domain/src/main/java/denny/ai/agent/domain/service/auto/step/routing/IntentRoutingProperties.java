package denny.ai.agent.domain.service.auto.step.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intent.routing")
public class IntentRoutingProperties {
    private IntentRoutingMode mode = IntentRoutingMode.UNIFIED;
    private Fewshot fewshot = new Fewshot();
    private Debug debug = new Debug();

    @Data
    public static class Fewshot {
        private int topK = 5;
        private double similarityThreshold = 0.5d;

        public void setTopK(int topK) {
            if (topK < 1) {
                throw new IllegalArgumentException("intent.routing.fewshot.top-k must be at least 1");
            }
            this.topK = topK;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            if (similarityThreshold < 0.0d || similarityThreshold > 1.0d) {
                throw new IllegalArgumentException(
                        "intent.routing.fewshot.similarity-threshold must be between 0 and 1");
            }
            this.similarityThreshold = similarityThreshold;
        }
    }

    @Data
    public static class Debug {
        private boolean enabled = false;
        private boolean includeQuery = false;
        private boolean includeResults = false;
        private boolean includeFinalPrompt = false;
        private boolean includeModelResponse = false;
        private int maxContentLength = 12000;

        public void setMaxContentLength(int maxContentLength) {
            if (maxContentLength < 1) {
                throw new IllegalArgumentException(
                        "intent.routing.debug.max-content-length must be at least 1");
            }
            this.maxContentLength = maxContentLength;
        }

        public String truncate(String content) {
            if (content == null || content.length() <= maxContentLength) {
                return content;
            }
            return content.substring(0, maxContentLength)
                    + "... [truncated, originalLength=" + content.length() + "]";
        }
    }
}

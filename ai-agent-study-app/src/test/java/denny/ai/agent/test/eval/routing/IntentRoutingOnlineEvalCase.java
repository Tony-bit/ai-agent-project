package denny.ai.agent.test.eval.routing;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class IntentRoutingOnlineEvalCase {

    private String caseId;
    private Boolean enabled = true;
    private String suite;
    private String category;
    private String description;
    private Input input;
    private Expected expected;
    private Evaluation evaluation;
    private List<String> tags = new ArrayList<>();

    @Data
    public static class Input {
        private String query;
        private List<String> historyMessages = new ArrayList<>();
    }

    @Data
    public static class Expected {
        private Boolean multiTask;
        private Boolean needsClarification;
        private List<String> taskIntents = new ArrayList<>();
        private List<Object> acceptableTaskIntents = new ArrayList<>();
        private Boolean orderSensitive = true;
        private List<String> missingInfoContains = new ArrayList<>();
        private Boolean missingInfoNotEmpty = false;
    }

    @Data
    public static class Evaluation {
        private Integer runs;
        private Double minPassRate;
        private Double minConsistencyRate;
    }
}

package denny.ai.agent.infrastructure.adapter.repository;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RerankReq {
    private String model;

    private Input input;

    private Parameters parameters;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Input {
        private String query;

        private List<String> documents;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Parameters {
        private int top_n;
    }
}

package denny.ai.agent.infrastructure.adapter.repository.model;

import lombok.Data;

import java.util.Optional;

@Data
public class RagSimpleDoc {
    private String source;
    private String title;
    private String content;
    private float score;
    private double rrfScore;
    private double rerankScore;
    private int vectorRank = Integer.MAX_VALUE;
    private int esRank = Integer.MAX_VALUE;

    public String uniqueKey() {
        return Optional.ofNullable(title).orElse("") + "||" + Optional.ofNullable(content).orElse("");
    }
}

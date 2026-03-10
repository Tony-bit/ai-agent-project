package denny.ai.agent.infrastructure.adapter.repository.model;

import lombok.Data;

@Data
public class RagRetrievedDoc {
    private String docId;
    private String source;
    private String title;
    private String content;
    private double rrfScore;
    private double rerankScore;
}

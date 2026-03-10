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
public class TextEmbeddingReq {
    private String model;

    private Input input;

    private Parameters parameters;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Input {
        @com.fasterxml.jackson.annotation.JsonProperty("contents")
        private List<Embedding> contents;

    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Parameters {
        private String output_type;

        /**
         * qwen3-vl-embedding 支持 2560、2048、1536、1024、768、512、256，默认值为 2560；
         * qwen2.5-vl-embedding 支持 2048、1024、768、512，默认值为 1024；
         * tongyi-embedding-vision-plus 支持 64、128、256、512、1024、1152，默认值为 1152；
         * tongyi-embedding-vision-flash 支持 64、128、256、512、768，默认值为 768；
         * multimodal-embedding-v1 不支持此参数，固定返回 1024 维向量
         */
        private Integer dimension;

        // 控制视频的帧数，比例越小，实际抽取的帧数越少，范围为 [0,1]。默认值为1.0
        private Float fps;

        // 添加自定义任务说明，可用于指导模型理解查询意图。建议使用英文撰写，通常可带来约 1%–5% 的效果提升。
        private String instruct;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Embedding {
        private String text;

        // url
        private String image;

        // url
        private String video;
    }
}

package denny.ai.agent.infrastructure.rag;

import java.util.List;

/**
 * Cross-Encoder 重排服务接口。
 *
 * 当前阶段先预留统一接口，后续可接入外部/自建模型服务：
 * - 输入：query + candidate passages
 * - 输出：与 passages 顺序对齐的相关性分数
 */
public interface RagCrossEncoderService {

    /**
     * 对候选文档进行相关性打分。
     *
     * @param query    用户问题
     * @param passages 候选文本列表
     * @return 与 passages 等长的分数列表
     */
    List<Double> score(String query, List<String> passages);
}

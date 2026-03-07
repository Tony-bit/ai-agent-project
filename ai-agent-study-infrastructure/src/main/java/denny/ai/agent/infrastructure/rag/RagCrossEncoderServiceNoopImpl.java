package denny.ai.agent.infrastructure.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-Encoder 默认空实现（降级实现）。
 *
 * 在未接入真实 Cross-Encoder 模型服务时，返回固定分数，保持系统可运行。
 * 后续接入模型服务后，可新增真实实现并替换该 Bean。
 */
@Slf4j
@Service
@ConditionalOnMissingBean(RagCrossEncoderService.class)
public class RagCrossEncoderServiceNoopImpl implements RagCrossEncoderService {

    @Override
    public List<Double> score(String query, List<String> passages) {
        if (passages == null || passages.isEmpty()) {
            return new ArrayList<>();
        }
        log.debug("Cross-Encoder 未接入，使用 Noop 实现，queryLength={}, candidateSize={}",
                query == null ? 0 : query.length(), passages.size());

        List<Double> scores = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) {
            scores.add(0.0d);
        }
        return scores;
    }
}

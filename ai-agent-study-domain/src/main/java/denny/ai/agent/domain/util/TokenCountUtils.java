package denny.ai.agent.domain.util;

import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Token 计数工具类
 * <p>
 * 基于 JTokkit 编码库估算文本/消息的 token 数量
 * </p>
 *
 * @author denny
 */
public class TokenCountUtils {

    private static final TokenCountEstimator ESTIMATOR = new JTokkitTokenCountEstimator();

    private TokenCountUtils() {
    }

    /**
     * 估算文本的 token 数量
     *
     * @param text 文本内容
     * @return token 数量
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ESTIMATOR.estimate(text);
    }

    /**
     * 估算消息列表的 token 数量
     *
     * @param messages 消息列表（支持 String 或 MediaContent）
     * @return token 数量
     */
    public static int estimate(Iterable<?> messages) {
        if (messages == null) {
            return 0;
        }
        int count = 0;
        for (Object msg : messages) {
            if (msg instanceof String) {
                count += estimate((String) msg);
            } else if (msg instanceof MediaContent) {
                count += ESTIMATOR.estimate((MediaContent) msg);
            }
        }
        return count;
    }
}

package denny.ai.agent.domain.model.valobj.enums;

import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import denny.ai.agent.domain.model.valobj.AiClientAdvisorVO;
import denny.ai.agent.domain.service.armory.factory.element.ObservabilityAdvisor;
import denny.ai.agent.domain.service.armory.factory.element.RagAnswerAdvisor;
import denny.ai.agent.domain.service.chatmemory.ConversationContextAdvisor;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.chatmemory.SpringAiConversationMemoryRepository;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.Map;

/**
 * 顾问类型枚举
 *
 * @author denny
 * 2025/7/19 09:02
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public enum AiClientAdvisorTypeEnumVO {

    CHAT_MEMORY("ChatMemory", "上下文记忆（内存模式）") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO,
                                     VectorStore vectorStore,
                                     IRagKnowledgeRepository ragKnowledgeRepository,
                                     ObservabilityService observabilityService,
                                     SkillRegistry skillRegistry,
                                     ConversationContextProvider conversationContextProvider,
                                     SpringAiConversationMemoryRepository springAiConversationMemoryRepository) {
            AiClientAdvisorVO.ChatMemory chatMemory = aiClientAdvisorVO.getChatMemory();
            int maxMessages = chatMemory == null ? 20 : chatMemory.getMaxMessages();
            return new ConversationContextAdvisor(conversationContextProvider,
                    springAiConversationMemoryRepository, maxMessages);
        }
    },

    RAG_ANSWER("RagAnswer", "知识库") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO,
                                     VectorStore vectorStore,
                                     IRagKnowledgeRepository ragKnowledgeRepository,
                                     ObservabilityService observabilityService,
                                     SkillRegistry skillRegistry,
                                     ConversationContextProvider conversationContextProvider,
                                     SpringAiConversationMemoryRepository springAiConversationMemoryRepository) {
            AiClientAdvisorVO.RagAnswer ragAnswer = aiClientAdvisorVO.getRagAnswer();
            return new RagAnswerAdvisor(ragKnowledgeRepository, SearchRequest.builder()
                    .topK(ragAnswer.getTopK())
                    .filterExpression(ragAnswer.getFilterExpression())
                    .build());
        }
    },

    OBSERVABILITY("Observability", "可观测日志打点") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO,
                                     VectorStore vectorStore,
                                     IRagKnowledgeRepository ragKnowledgeRepository,
                                     ObservabilityService observabilityService,
                                     SkillRegistry skillRegistry,
                                     ConversationContextProvider conversationContextProvider,
                                     SpringAiConversationMemoryRepository springAiConversationMemoryRepository) {
            return new ObservabilityAdvisor(observabilityService);
        }
    },

    TRADING_SKILL("TradingSkill", "交易技能（渐进式披露）") {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO,
                                     VectorStore vectorStore,
                                     IRagKnowledgeRepository ragKnowledgeRepository,
                                     ObservabilityService observabilityService,
                                     SkillRegistry skillRegistry,
                                     ConversationContextProvider conversationContextProvider,
                                     SpringAiConversationMemoryRepository springAiConversationMemoryRepository) {
            log.info("创建交易技能 Advisor: {}", this.name());
            return SpringAiSkillAdvisor.builder()
                    .skillRegistry(skillRegistry)
                    .lazyLoad(true)
                    .build();
        }
    }

    ;

    private String code;
    private String info;

    private static final Map<String, AiClientAdvisorTypeEnumVO> CODE_MAP = new HashMap<>();

    static {
        for (AiClientAdvisorTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }

    public abstract Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO,
                                          VectorStore vectorStore,
                                          IRagKnowledgeRepository ragKnowledgeRepository,
                                          ObservabilityService observabilityService,
                                          SkillRegistry skillRegistry,
                                          ConversationContextProvider conversationContextProvider,
                                          SpringAiConversationMemoryRepository springAiConversationMemoryRepository);

    public static AiClientAdvisorTypeEnumVO getByCode(String code) {
        AiClientAdvisorTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! advisorType " + code + " not exist!");
        }
        return enumVO;
    }

}

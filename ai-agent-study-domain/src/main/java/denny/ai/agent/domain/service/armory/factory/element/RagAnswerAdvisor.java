package denny.ai.agent.domain.service.armory.factory.element;

import com.alibaba.fastjson.JSON;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

public class RagAnswerAdvisor implements BaseAdvisor {

    private final IRagKnowledgeRepository ragKnowledgeRepository;
    private final SearchRequest searchRequest;
    private final String userTextAdvise;

    public RagAnswerAdvisor(IRagKnowledgeRepository ragKnowledgeRepository, SearchRequest searchRequest) {
        this.ragKnowledgeRepository = ragKnowledgeRepository;
        this.searchRequest = searchRequest;
        this.userTextAdvise = "\nContext information is below, surrounded by ---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Map<String, Object> context = new HashMap<>(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();
        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise;

        int topK = this.searchRequest != null ? this.searchRequest.getTopK() : 5;
        String userId = doGetUserId(context);
        String ragContext = ragKnowledgeRepository.retrieveContext(userId, userText, topK);

        context.put("qa_retrieved_documents", ragContext);

        Map<String, Object> advisedUserParams = new HashMap<>(chatClientRequest.context());
        advisedUserParams.put("question_answer_context", ragContext);

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage(advisedUserText), new AssistantMessage(JSON.toJSONString(advisedUserParams))).build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponseBuilder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        ChatResponse chatResponse = chatResponseBuilder.build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    protected String doGetUserId(Map<String, Object> context) {
        return context.containsKey("user_id")
                ? String.valueOf(context.get("user_id"))
                : "";
    }

}

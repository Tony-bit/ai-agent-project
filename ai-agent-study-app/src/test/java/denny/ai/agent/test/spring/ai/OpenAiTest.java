package denny.ai.agent.test.spring.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson2.JSONObject;
import denny.ai.agent.config.RagRetrievalEvalScheduler;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class OpenAiTest {

    @Value("classpath:data/dog.png")
    private Resource imageResource;

    @Value("classpath:data/file.txt")
    private Resource textResource;

    @Value("classpath:data/article-prompt-words.txt")
    private Resource articlePromptWordsResource;

    @Value("classpath:data/file1.text")
    private Resource reimbursementDocFileResource;

    @Value("classpath:data/file2.text")
    private Resource reimbursementEvalCaseFileResource;

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Autowired
    private PgVectorStore pgVectorStore;

    @Autowired
    private ObservabilityService observabilityService;

    @Autowired
    private IRagKnowledgeRepository ragKnowledgeRepository;

    @Autowired
    private RagRetrievalEvalScheduler ragRetrievalEvalScheduler;

    private final TokenTextSplitter tokenTextSplitter = new TokenTextSplitter();

    @Test
    public void test_call() {
        ChatResponse response = openAiChatModel.call(new Prompt(
                "1+1",
                OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .build()));
        log.info("测试结果(call):{}", JSON.toJSONString(response));
    }

    /**
     * Langfuse 闭环验证：
     * 1. 启动一个 trace
     * 2. 调用一次大模型
     * 3. 上报 generation 与 score
     * 4. 在 Langfuse UI 中确认可见
     */
    @Test
    public void test_langfuse_observability_closed_loop() {
        String userMessage = "请用一句中文解释什么是 Langfuse";

        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("scene", "unit-test");
        traceMetadata.put("testCase", "test_langfuse_observability_closed_loop");

        String traceId = observabilityService.startTrace(
                "test-session-langfuse-001",
                userMessage,
                traceMetadata
        );

        String spanId = observabilityService.startSpan(traceId, "test_openai_call", traceMetadata);

        try {
            long startAt = System.currentTimeMillis();

            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl("https://api.deepseek.com/")
                    .apiKey("sk-a0df0f93a5ac475e8c73c6e3495cfc05")
                    .completionsPath("v1/chat/completions")
                    .build();

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model("deepseek-chat")
                            .build())
                    .build();

            ChatResponse response = chatModel.call(new Prompt(
                    userMessage,
                    OpenAiChatOptions.builder()
                            .model("deepseek-chat")
                            .build()));

            String output = response.getResult() == null || response.getResult().getOutput() == null
                    ? ""
                    : response.getResult().getOutput().getText();

            long latencyMs = System.currentTimeMillis() - startAt;

            Map<String, Object> generationMetadata = new HashMap<>();
            generationMetadata.put("latencyMs", latencyMs);
            generationMetadata.put("source", "OpenAiTest#test_langfuse_observability_closed_loop");

            Map<String, Object> tokenUsage = new HashMap<>();
            if (response != null && response.getMetadata() != null) {
                Object promptTokens = response.getMetadata().get("promptTokens");
                Object completionTokens = response.getMetadata().get("completionTokens");
                Object totalTokens = response.getMetadata().get("totalTokens");
                if (promptTokens != null) tokenUsage.put("promptTokens", promptTokens);
                if (completionTokens != null) tokenUsage.put("completionTokens", completionTokens);
                if (totalTokens != null) tokenUsage.put("totalTokens", totalTokens);
            }

            observabilityService.logGeneration(
                    traceId,
                    spanId,
                    "deepseek-chat",
                    userMessage,
                    output,
                    generationMetadata,
                    tokenUsage
            );

            // 测试用固定评分，方便在 Langfuse 上确认 score 链路
            observabilityService.logScore(
                    traceId,
                    "test_quality_pass",
                    1.0,
                    "manual test score",
                    generationMetadata
            );

            observabilityService.endSpan(spanId, true, null);

            log.info("Langfuse 闭环测试完成，traceId={}, output={}", traceId, output);
        } catch (Exception e) {
            observabilityService.endSpan(spanId, false, e.getMessage());
            throw e;
        }
    }

    @Test
    public void test_call_images() {
        UserMessage userMessage = UserMessage.builder()
                .text("请描述这张图片的主要内容，并说明图中物品的可能用途。")
                .media(org.springframework.ai.content.Media.builder()
                        .mimeType(MimeType.valueOf(MimeTypeUtils.IMAGE_PNG_VALUE))
                        .data(imageResource)
                        .build())
                .build();

        ChatResponse response = openAiChatModel.call(new Prompt(
                userMessage,
                OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .build()));

        log.info("测试结果(images):{}", JSON.toJSONString(response));
    }

    @Test
    public void test_stream() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        Flux<ChatResponse> stream = openAiChatModel.stream(new Prompt(
                "1+1",
                OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .build()));

        stream.subscribe(
                chatResponse -> {
                    AssistantMessage output = chatResponse.getResult().getOutput();
                    log.info("测试结果(stream): {}", JSON.toJSONString(output));
                },
                Throwable::printStackTrace,
                () -> {
                    countDownLatch.countDown();
                    log.info("测试结果(stream): done!");
                }
        );

        countDownLatch.await();
    }

    @Test
    public void upload() {
        // textResource、articlePromptWordsResource
        TikaDocumentReader reader = new TikaDocumentReader(articlePromptWordsResource);

        List<Document> documents = reader.get();
        List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

        documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "article-prompt-words"));

        pgVectorStore.accept(documentSplitterList);

        log.info("上传完成");
    }

    @Test
    public void importMockReimbursementDocs() throws Exception {
        String userId = "eval-expense-v1";

        List<String> lines = java.nio.file.Files.readAllLines(
                reimbursementDocFileResource.getFile().toPath(),
                StandardCharsets.UTF_8
        );

        int successCount = 0;
        for (String line : lines) {
            String row = line == null ? "" : line.trim();
            if (row.isEmpty()) {
                continue;
            }
            if (row.endsWith(",")) {
                row = row.substring(0, row.length() - 1);
            }

            JSONObject item = JSONObject.parseObject(row);
            String docId = item.getString("doc_id");
            String title = item.getString("title");
            String summary = item.getString("summary");
            String tags = String.valueOf(item.getJSONArray("tags"));
            String department = item.getString("department");
            String effectiveDate = item.getString("effective_date");

            String fileName = String.format("%s-%s.md", docId, title);
            String content = String.format("""
                    # %s
                    
                    - 文档ID: %s
                    - 标题: %s
                    - 生效日期: %s
                    - 归口部门: %s
                    - 标签: %s
                    
                    ## 摘要
                    %s
                    """,
                    title,
                    docId,
                    title,
                    effectiveDate,
                    department,
                    tags,
                    summary
            );

            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    fileName,
                    "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8)
            );
            String result = ragKnowledgeRepository.uploadAndIndex(userId, file, fileName);
            log.info("mock文档导入：docId={}, fileName={}, result={}", docId, fileName, result);
            successCount++;
        }

        List<String> evalCases = java.nio.file.Files.readAllLines(
                reimbursementEvalCaseFileResource.getFile().toPath(),
                StandardCharsets.UTF_8
        );
        long caseCount = evalCases.stream().map(String::trim).filter(s -> !s.isEmpty()).count();
        log.info("评测集文件检测完成：file2.text 共 {} 条case（本次仅导入file1文档）", caseCount);

        log.info("mock报销文档导入完成，userId={}, count={}", userId, successCount);
    }

    @Test
    public void testQuery() {
        log.info("开始测试可靠性集");
        ragRetrievalEvalScheduler.runManualEval();
        log.info("完成测试可靠性集");
    }

    @Test
    public void chat() {
        String message = "王大瓜今年几岁";

        String SYSTEM_PROMPT = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in Chinese!
                DOCUMENTS:
                    {documents}
                """;

        SearchRequest request = SearchRequest.builder()
                .query(message)
                .topK(5)
                .filterExpression("knowledge == '知识库名称-v4'")
                .build();

        List<Document> documents = pgVectorStore.similaritySearch(request);

        String documentsCollectors = null == documents ? "" : documents.stream().map(Document::getText).collect(Collectors.joining());

        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(Map.of("documents", documentsCollectors));

        ArrayList<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(message));
        messages.add(ragMessage);

        ChatResponse chatResponse = openAiChatModel.call(new Prompt(
                messages,
                OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .build()));

        log.info("测试结果:{}", JSON.toJSONString(chatResponse));
    }

}

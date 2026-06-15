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
import org.springframework.beans.factory.annotation.Qualifier;
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
public class OpenAiIntegrationTest {

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
    @Qualifier("intentFewshotVectorStore")
    private PgVectorStore intentFewshotVectorStore;

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
                    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
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


    // RAG注入信息专用！！
    @Test
    public void test_intent_fewshot_pgvector_recall() {
        log.info("=== test_intent_fewshot_pgvector_recall start ===");

        // FewShot 示例生成 lambda（每种意图一个）
        final java.util.function.Function<String, String> generalChatExample = content ->
                String.format(
                        "{\"multiTask\":false,\"needsClarification\":false,\"reasoning\":\"通用问答/概念解释\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"%s\",\"intent\":\"GENERAL_CHAT\",\"executorNode\":\"generalChatNode\",\"confidence\":\"HIGH\",\"taskType\":0,\"slots\":{}}]}",
                        content
                );

        final java.util.function.Function<String, String> peRetrievalExample = content ->
                String.format(
                        "{\"multiTask\":false,\"needsClarification\":false,\"reasoning\":\"知识库检索/多文档汇总\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"%s\",\"intent\":\"PE_RETRIEVAL\",\"executorNode\":\"step1AnalyzerNode\",\"confidence\":\"HIGH\",\"taskType\":0,\"slots\":{}}]}",
                        content
                );

        final java.util.function.Function<String, String> peReasoningExample = content ->
                String.format(
                        "{\"multiTask\":false,\"needsClarification\":false,\"reasoning\":\"逻辑推理/问题分析\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"%s\",\"intent\":\"PE_REASONING\",\"executorNode\":\"step1AnalyzerNode\",\"confidence\":\"HIGH\",\"taskType\":0,\"slots\":{}}]}",
                        content
                );

        final java.util.function.Function<String, String> peCalculationExample = content ->
                String.format(
                        "{\"multiTask\":false,\"needsClarification\":false,\"reasoning\":\"数学计算/数据处理\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"%s\",\"intent\":\"PE_CALCULATION\",\"executorNode\":\"step1AnalyzerNode\",\"confidence\":\"HIGH\",\"taskType\":0,\"slots\":{}}]}",
                        content
                );

        final java.util.function.Function<String, String> stockAnalysisExample = content ->
                String.format(
                        "{\"multiTask\":false,\"needsClarification\":false,\"reasoning\":\"股票/市场分析\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"%s\",\"intent\":\"STOCK_ANALYSIS\",\"executorNode\":\"tradingStarter\",\"confidence\":\"HIGH\",\"taskType\":0,\"slots\":{}}]}",
                        content
                );

        final java.util.function.Function<String, String> inspectionExample = content ->
                String.format(
                        "{\"multiTask\":false,\"needsClarification\":false,\"reasoning\":\"系统巡检/健康检查\",\"taskList\":[{\"taskId\":\"sub-1\",\"taskIndex\":1,\"totalTasks\":1,\"content\":\"%s\",\"intent\":\"INSPECTION\",\"executorNode\":\"intelligentInspection\",\"confidence\":\"HIGH\",\"taskType\":0,\"slots\":{}}]}",
                        content
                );

        String stockClarificationExample = """
                {"multiTask":false,"needsClarification":true,"missingInfo":["stockCode"],
                 "clarificationPrompt":"请提供要分析的股票代码或股票名称。",
                 "reasoning":"用户只使用“这只股票”进行模糊指代，当前上下文没有明确股票标的，无法执行股票分析。",
                 "taskList":[]}
                """;

        String retrievalFollowupExample = """
                {"multiTask":false,"needsClarification":false,"missingInfo":[],"clarificationPrompt":"",
                 "reasoning":"用户使用“这些文档”指代历史中已检索的知识库文档，当前任务是继续对检索文档做汇总，属于PE_RETRIEVAL。",
                 "taskList":[{"taskId":"sub-1","taskIndex":1,"totalTasks":1,
                 "content":"汇总历史检索文档中的风险点","intent":"PE_RETRIEVAL",
                 "confidence":"HIGH","dependsOn":[],"slots":{"baseSlot":{"topic":"历史检索文档风险点","sentiment":"neutral"},"intentSpecificSlots":{}}}]}
                """;

        List<Document> docs = new ArrayList<>();
        int idCounter = 1;

        // ========== GENERAL_CHAT 样本（5条） ==========
        docs.add(createDoc(String.valueOf(idCounter++), "什么是向量数据库？", "GENERAL_CHAT", generalChatExample));
        docs.add(createDoc(String.valueOf(idCounter++), "RAG 是什么意思？", "GENERAL_CHAT", generalChatExample));
        docs.add(createDoc(String.valueOf(idCounter++), "给我讲讲大语言模型", "GENERAL_CHAT", generalChatExample));
        docs.add(createDoc(String.valueOf(idCounter++), "transformer 是什么", "GENERAL_CHAT", generalChatExample));
        docs.add(createDoc(String.valueOf(idCounter++), "解释一下注意力机制", "GENERAL_CHAT", generalChatExample));

        // ========== PE_RETRIEVAL 样本（5条） ==========
        docs.add(createDoc(String.valueOf(idCounter++), "查询一下最新的 AI Agent 论文", "PE_RETRIEVAL", peRetrievalExample));
        docs.add(createDoc(String.valueOf(idCounter++), "查找向量数据库选型相关的文档", "PE_RETRIEVAL", peRetrievalExample));
        docs.add(createDoc(String.valueOf(idCounter++), "检索 RAG 架构优化的资料", "PE_RETRIEVAL", peRetrievalExample));
        docs.add(createDoc(String.valueOf(idCounter++), "汇总一下 LangChain 官方文档里关于 memory 的用法", "PE_RETRIEVAL", peRetrievalExample));
        docs.add(createDoc(String.valueOf(idCounter++), "基于知识库检索整理一份向量数据库对比", "PE_RETRIEVAL", peRetrievalExample));

        // ========== PE_REASONING 样本（5条） ==========
        docs.add(createDoc(String.valueOf(idCounter++), "分析一下为什么 LLM 会产生幻觉", "PE_REASONING", peReasoningExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我推理一下这个算法的复杂度", "PE_REASONING", peReasoningExample));
        docs.add(createDoc(String.valueOf(idCounter++), "设计一个分布式缓存方案", "PE_REASONING", peReasoningExample));
        docs.add(createDoc(String.valueOf(idCounter++), "分析这个问题应该用什么设计模式", "PE_REASONING", peReasoningExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我理清这个业务逻辑", "PE_REASONING", peReasoningExample));

        // ========== PE_CALCULATION 样本（5条） ==========
        docs.add(createDoc(String.valueOf(idCounter++), "计算一下 12345 的阶乘", "PE_CALCULATION", peCalculationExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我算一下复利的终值", "PE_CALCULATION", peCalculationExample));
        docs.add(createDoc(String.valueOf(idCounter++), "统计一下这组数据的均值和方差", "PE_CALCULATION", peCalculationExample));
        docs.add(createDoc(String.valueOf(idCounter++), "计算这两个矩阵的乘积", "PE_CALCULATION", peCalculationExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我做一个回归分析", "PE_CALCULATION", peCalculationExample));

        // ========== STOCK_ANALYSIS 样本（5条） ==========
        docs.add(createDoc(String.valueOf(idCounter++), "分析一下贵州茅台的走势", "STOCK_ANALYSIS", stockAnalysisExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我看看比亚迪最近怎么样", "STOCK_ANALYSIS", stockAnalysisExample));
        docs.add(createDoc(String.valueOf(idCounter++), "查询宁德时代的K线", "STOCK_ANALYSIS", stockAnalysisExample));
        docs.add(createDoc(String.valueOf(idCounter++), "分析上证指数的技术指标", "STOCK_ANALYSIS", stockAnalysisExample));
        docs.add(createDoc(String.valueOf(idCounter++), "给我看看腾讯控股的行情", "STOCK_ANALYSIS", stockAnalysisExample));

        // ========== 边界澄清 / 历史指代样本 ==========
        docs.add(createDoc(String.valueOf(idCounter++), "帮我分析一下这只股票最近的走势。", "STOCK_ANALYSIS", stockClarificationExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我分析这只股票最近是不是可以抄底，顺便看看风险大不大。", "STOCK_ANALYSIS", stockClarificationExample));
        docs.add(createDoc(String.valueOf(idCounter++), "再把这些文档里的风险点汇总一下。", "PE_RETRIEVAL", retrievalFollowupExample));

        // ========== INSPECTION 样本（5条） ==========
        docs.add(createDoc(String.valueOf(idCounter++), "检查一下系统健康状态", "INSPECTION", inspectionExample));
        docs.add(createDoc(String.valueOf(idCounter++), "做个系统巡检", "INSPECTION", inspectionExample));
        docs.add(createDoc(String.valueOf(idCounter++), "查看 CPU 和内存使用情况", "INSPECTION", inspectionExample));
        docs.add(createDoc(String.valueOf(idCounter++), "检查一下数据库连接池", "INSPECTION", inspectionExample));
        docs.add(createDoc(String.valueOf(idCounter++), "帮我看看各个服务的状态", "INSPECTION", inspectionExample));

        log.info("准备写入 intent_fewshot_vector_store，docs.size={}", docs.size());
        // DashScope text-embedding-v3 单次批量上限为 10，分批写入
        int batchSize = 10;
        for (int i = 0; i < docs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, docs.size());
            List<Document> batch = docs.subList(i, end);
            intentFewshotVectorStore.accept(batch);
            log.info("pgvector 分批写入完成，批次 {}/{}, 本批数量={}", (i / batchSize) + 1, (docs.size() + batchSize - 1) / batchSize, batch.size());
        }
        log.info("pgvector 全部写入完成");

        // 检索测试：查询"向量数据库是什么"应该召回 GENERAL_CHAT
        SearchRequest request = SearchRequest.builder()
                .query("向量数据库是什么")
                .topK(5)
                .similarityThreshold(0.0d)
                .build();

        List<Document> result = intentFewshotVectorStore.similaritySearch(request);
        log.info("检索完成，result.size={}", result == null ? null : result.size());

        if (result != null) {
            result.forEach(doc -> log.info("recall result: text={}, metadata={}", doc.getText(), doc.getMetadata()));
        }

        log.info("=== test_intent_fewshot_pgvector_recall end ===");
    }

    private Document createDoc(String id, String text, String intentCode,
                               java.util.function.Function<String, String> exampleGenerator) {
        return createDoc(id, text, intentCode, exampleGenerator.apply(text));
    }

    private Document createDoc(String id, String text, String intentCode, String exampleJson) {
        Document doc = new Document(text);
        doc.getMetadata().put("id", "fewshot-test-" + id);
        doc.getMetadata().put("intentCode", intentCode);
        doc.getMetadata().put("exampleJson", exampleJson);
        return doc;
    }
}

package denny.ai.agent.infrastructure.adapter.repository;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
public class OSSIntegrationTest {

    private AmazonS3 s3;

    private final String accessKey = System.getenv("JD_CLOUD_OSS_ACCESS_KEY");
    private final String secretKey = System.getenv("JD_CLOUD_OSS_SECRET_KEY");
    private final String endpoint = "https://s3.cn-north-1.jdcloud-oss.com";
    private final String region = "cn-north-1";
    private final String bucketName = "denny-test";

    private void init() {
        Assume.assumeTrue("JD Cloud OSS credentials are required", isConfigured(accessKey) && isConfigured(secretKey));
        ClientConfiguration config = new ClientConfiguration();
        AwsClientBuilder.EndpointConfiguration endpointConfig =
                new AwsClientBuilder.EndpointConfiguration(endpoint, region);

        AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);

        this.s3 = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(endpointConfig)
                .withClientConfiguration(config)
                .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                .disableChunkedEncoding()
                .build();
    }

    @Test
    public void testUpload() throws IOException {
        init();
        String key = "dog.png";
        // 使用 ClassLoader 读取资源，不需要 @Value
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("data/dog.png")) {
            if (inputStream == null) {
                throw new RuntimeException("找不到图片文件: data/dog.png");
            }

            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType("image/png");
            // 注意：流上传时建议设置长度，或者让 SDK 自动处理

            s3.putObject(bucketName, key, inputStream, objectMetadata);

            // 打印通义千问需要的 URL
            String url = s3.getUrl(bucketName, key).toString();
            System.out.println("上传成功，访问 URL: " + url);
        }
    }

    @Test
    public void testParsePicture() throws IOException {
        String url = "https://denny-test.s3.cn-north-1.jdcloud-oss.com/dog.png";

        MultiModalConversation conv = new MultiModalConversation();
        MultiModalMessage userMessage = MultiModalMessage.builder().role(Role.USER.getValue())
                .content(Arrays.asList(
                        Collections.singletonMap("image", url),
                        Collections.singletonMap("text", "图中描绘的是什么景象?"))).build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                // 若没有配置环境变量，请用百炼API Key将下行替换为：.apiKey("sk-xxx")
                // 各地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
                .apiKey(requireEnv("DASHSCOPE_API_KEY"))
                .model("qwen3.5-plus")  // 此处以qwen3.5-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/models
                .messages(Arrays.asList(userMessage))
                .build();
        try {
            MultiModalConversationResult result = conv.call(param);
            System.out.println(result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text"));
        }  catch (ApiException | NoApiKeyException | UploadFileException e) {
            System.out.println(e.getMessage());
        }
    }

    @Test
    public void testDashscopeTextRerank() throws Exception {
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        String apiKey = requireEnv("DASHSCOPE_API_KEY");

        RerankReq requestBody = RerankReq.builder()
                .model("qwen3-rerank")
                .input(RerankReq.Input.builder()
                        .query("什么是文本排序模型？")
                        .documents(Arrays.asList(
                                "文本排序模型广泛用于搜索引擎和推荐系统中，它们根据文本相关性对候选文本进行排序",
                                "量子计算是计算科学的一个前沿领域",
                                "预训练语言模型的发展给文本排序模型带来了新的进展"
                        ))
                        .build())
                .parameters(RerankReq.Parameters.builder()
                        .top_n(2)
                        .build())
                .build();

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CohereRerankResponse rerankResponse = objectMapper.readValue(response.body(), CohereRerankResponse.class);

        System.out.println("statusCode: " + response.statusCode());
        System.out.println("output: " + objectMapper.writeValueAsString(rerankResponse.getOutput()));
    }

    @Test
    public void testDashscopeTextEmbeddings() throws Exception {
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
        String apiKey = requireEnv("DASHSCOPE_API_KEY");

        TextEmbeddingReq requestBody = TextEmbeddingReq.builder()
                .model("text-embedding-v3")
                .input(TextEmbeddingReq.Input.builder().contents(Arrays.asList(
                        TextEmbeddingReq.Embedding.builder()
                                .text("多模态向量模型")
                                .build()
                ))
                        .build())
                .build();

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        EmbeddingResponse embeddingResponse = objectMapper.readValue(response.body(), EmbeddingResponse.class);

        System.out.println("statusCode: " + response.statusCode());
        System.out.println("output: " + objectMapper.writeValueAsString(embeddingResponse.getOutput()));
    }

//    @Test
//    public void upload() {
//        TikaDocumentReader reader = new TikaDocumentReader("classpath:/data/file.text");
//
//        List<Document> documents = reader.get();
//        List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
//
//        documents.forEach(doc -> doc.getMetadata().put("knowledge", "知识库名称"));
//        documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "知识库名称"));
//
//        pgVectorStore.accept(documentSplitterList);
//
//        log.info("上传完成");
//    }
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        Assume.assumeTrue(name + " is required", isConfigured(value));
        return value;
    }

    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }
}

package denny.ai.agent.domain.service.qwen;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

@Slf4j
@Service
public class QwenService {

    @Value("${dashscope.api.key:}")
    private String apiKey;

    private final String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private final String model = "qwen-vl-plus";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 识别图片内容
     */
    public String recognizeImage(MultipartFile file, String question) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = System.getenv("DASHSCOPE_API_KEY");
            }
            if (apiKey == null || apiKey.isEmpty()) {
                return "错误：未配置 DASHSCOPE_API_KEY";
            }

            // 1. 将图片转为 Base64
            String base64Content = Base64.getEncoder().encodeToString(file.getBytes());
            String mimeType = file.getContentType();
            if (mimeType == null) mimeType = "image/png";
            String dataUrl = "data:" + mimeType + ";base64," + base64Content;

            // 2. 构建 OpenAI 兼容格式请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");

            JSONArray contents = new JSONArray();
            
            // 图片部分
            JSONObject imagePart = new JSONObject();
            imagePart.put("type", "image_url");
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", dataUrl);
            imagePart.put("image_url", imageUrl);
            contents.add(imagePart);

            // 文本部分
            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", (question == null || question.isEmpty()) ? "图中描绘的是什么景象？" : question);
            contents.add(textPart);

            userMessage.put("content", contents);
            messages.add(userMessage);
            requestBody.put("messages", messages);

            // 3. 发送请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            log.info("开始调用通义千问识图接口...");
            
            ResponseEntity<String> response = restTemplate.postForEntity(baseUrl, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject responseJson = JSON.parseObject(response.getBody());
                return responseJson.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            } else {
                return "识图失败，状态码：" + response.getStatusCode();
            }

        } catch (Exception e) {
            log.error("通义千问识图异常", e);
            return "识图异常：" + e.getMessage();
        }
    }
}

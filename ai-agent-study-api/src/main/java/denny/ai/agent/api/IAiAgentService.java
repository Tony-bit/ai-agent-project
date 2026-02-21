package denny.ai.agent.api;


import denny.ai.agent.api.dto.AutoAgentRequestDTO;
import denny.ai.agent.api.response.Response;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Ai Agent 服务接口
 *
 * @author Denny
 */
public interface IAiAgentService {

    ResponseBodyEmitter autoAgent(AutoAgentRequestDTO request, HttpServletResponse response);

    Response<String> uploadImage(@RequestParam("file") MultipartFile file);
}

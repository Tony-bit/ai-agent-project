package denny.ai.agent.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 7000723935764546321L;

    private String code;
    private String info;
    private T data;

    public static Response<Void> ok() {
        return Response.<Void>builder().code("200").info("success").data(null).build();
    }

    public static <T> Response<T> ok(T data) {
        return Response.<T>builder().code("200").info("success").data(data).build();
    }

    public static Response<Void> error(String code, String info) {
        return Response.<Void>builder().code(code).info(info).data(null).build();
    }
}

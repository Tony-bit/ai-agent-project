package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuthUserPO {

    private Long id;
    private String userId;
    private String userType;
    private String account;
    private String passwordHash;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

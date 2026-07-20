-- Incremental authentication user table for existing environments.
CREATE TABLE IF NOT EXISTS `ai_auth_user` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`           VARCHAR(64)     NOT NULL,
    `user_type`         VARCHAR(16)     NOT NULL,
    `account`           VARCHAR(128)    DEFAULT NULL,
    `password_hash`     VARCHAR(255)    DEFAULT NULL,
    `status`            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_auth_user_id` (`user_id`),
    UNIQUE KEY `uk_auth_account` (`account`),
    CONSTRAINT `chk_auth_user_credentials` CHECK (
        (`user_type` = 'ACCOUNT' AND `account` IS NOT NULL AND `password_hash` IS NOT NULL)
        OR (`user_type` = 'GUEST' AND `account` IS NULL AND `password_hash` IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认证用户表';

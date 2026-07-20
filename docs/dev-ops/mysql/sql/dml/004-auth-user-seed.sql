-- Local/test accounts. The BCrypt hash is intentionally stored without a plaintext password.
INSERT INTO `ai_auth_user` (`user_id`, `user_type`, `account`, `password_hash`, `status`)
VALUES
    ('account_user_a', 'ACCOUNT', 'account_user_a', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ACTIVE'),
    ('account_user_b', 'ACCOUNT', 'account_user_b', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ACTIVE'),
    ('disabled_user', 'ACCOUNT', 'disabled_user', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'DISABLED')
ON DUPLICATE KEY UPDATE
    `password_hash` = VALUES(`password_hash`),
    `status` = VALUES(`status`),
    `update_time` = CURRENT_TIMESTAMP;

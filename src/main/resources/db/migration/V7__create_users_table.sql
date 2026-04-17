CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT UNIQUE NOT NULL,
    username VARCHAR(255),
    tier VARCHAR(50) NOT NULL DEFAULT 'FREE',
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Добавляем тестовых пользователей для отладки
INSERT INTO users (chat_id, username, tier, active) 
VALUES (111, 'free_user', 'FREE', TRUE)
ON CONFLICT (chat_id) DO NOTHING;

INSERT INTO users (chat_id, username, tier, active) 
VALUES (999, 'pro_user', 'PRO', TRUE)
ON CONFLICT (chat_id) DO NOTHING;

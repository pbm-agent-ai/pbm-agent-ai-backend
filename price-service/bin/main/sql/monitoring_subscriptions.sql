-- =============================================================================
-- users_monitoring_subscriptions: 사용자별 모니터링 구독 테이블
-- =============================================================================
-- 
-- 역할: 사용자가 등록한 개별 가격 모니터링 건을 저장한다.
--       레코드명을 monitoring_subscriptions → users_monitoring_subscriptions 로
--       변경하여 monitor_targets(공유 수집 대상)와의 혼동을 방지했다.
-- 
-- 연관: monitoring_subscription_status (enum), platform (enum), currency_type (enum)
-- 
-- ※ 현재 프로젝트는 JPA ddl-auto: update로 테이블이 자동 생성되므로,
--   이 SQL은 참고용이며 별도로 실행할 필요는 없다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS users_monitoring_subscriptions (
    id                       BIGSERIAL       PRIMARY KEY,
    user_id                  BIGINT          NOT NULL,
    command_id               VARCHAR(36)     NOT NULL,
    platform                 VARCHAR(30)     NOT NULL,
    product_id               VARCHAR(255)    NOT NULL,
    product_url              VARCHAR(1000),
    snapshot_title           VARCHAR(500),
    snapshot_price           NUMERIC(19, 4),
    search_keyword           VARCHAR(255),
    target_price             NUMERIC(19, 4),
    currency                 VARCHAR(10)     NOT NULL,
    intent                   VARCHAR(30),
    status                   VARCHAR(20)     NOT NULL,
    consecutive_miss_count   INTEGER         NOT NULL DEFAULT 0,
    check_interval_minutes   INTEGER         NOT NULL DEFAULT 10,
    last_checked_at          TIMESTAMP WITH TIME ZONE,
    next_check_at            TIMESTAMP WITH TIME ZONE,
    scheduled_end_at         TIMESTAMP WITH TIME ZONE,
    ai_agent_address         VARCHAR(42),
    ai_agent_private_key     VARCHAR(128),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Unique 제약: 동일 사용자가 같은 상품을 중복 구독하지 않도록 방지
ALTER TABLE users_monitoring_subscriptions ADD CONSTRAINT IF NOT EXISTS uk_ms_user_platform_product
    UNIQUE (user_id, platform, product_id);

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_ums_user_id
    ON users_monitoring_subscriptions (user_id);

CREATE INDEX IF NOT EXISTS idx_ums_status_next_check
    ON users_monitoring_subscriptions (status, next_check_at);

CREATE INDEX IF NOT EXISTS idx_ums_command_id
    ON users_monitoring_subscriptions (command_id);

-- =============================================================================
-- monitoring_subscriptions: 사용자별 모니터링 구독 테이블
-- =============================================================================
-- 
-- 역할: 사용자가 등록한 개별 가격 모니터링 건을 저장한다.
--       Phase 1에서는 기존 monitor_targets 테이블과 공존하며,
--       이후 단계에서 통합 또는 교체될 수 있다.
-- 
-- 연관: monitoring_subscription_status (enum), platform (enum), currency_type (enum)
-- 
-- ※ 현재 프로젝트는 JPA ddl-auto: update로 테이블이 자동 생성되므로,
--   이 SQL은 참고용이며 별도로 실행할 필요는 없다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS monitoring_subscriptions (
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
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_ms_user_id
    ON monitoring_subscriptions (user_id);

CREATE INDEX IF NOT EXISTS idx_ms_status_next_check
    ON monitoring_subscriptions (status, next_check_at);

CREATE INDEX IF NOT EXISTS idx_ms_command_id
    ON monitoring_subscriptions (command_id);

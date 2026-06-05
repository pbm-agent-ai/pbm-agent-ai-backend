-- price-service 수동 마이그레이션 SQL
--
-- 목적:
-- 1. monitoring_subscriptions 테이블명을 users_monitoring_subscriptions로 변경한다.
-- 2. monitor_targets를 keyword 기반에서 productId 기반 구조로 전환한다.
--
-- 주의:
-- - 이 SQL은 PostgreSQL 기준이다.
-- - 운영/개발 DB에 적용 전 반드시 백업을 권장한다.
-- - 애플리케이션(price-service)은 중지한 상태에서 실행하는 것을 권장한다.
-- - 기존 monitor_targets는 (platform, normalized_keyword) 단위였으므로,
--   product 단위로 재구성하기 위해 products 테이블을 기준으로 새 target을 만든다.

BEGIN;

-- ===================================================================
-- 1) 사용자별 구독 테이블명 변경
-- ===================================================================

ALTER TABLE IF EXISTS monitoring_subscriptions
    RENAME TO users_monitoring_subscriptions;

-- 인덱스/제약 이름은 그대로 둬도 동작에는 문제 없지만,
-- 사람이 읽기 쉽도록 이름도 같이 정리한다.
ALTER INDEX IF EXISTS idx_ms_user_id
    RENAME TO idx_ums_user_id;

ALTER INDEX IF EXISTS idx_ms_status_next_check
    RENAME TO idx_ums_status_next_check;

ALTER INDEX IF EXISTS idx_ms_command_id
    RENAME TO idx_ums_command_id;

ALTER TABLE IF EXISTS users_monitoring_subscriptions
    RENAME CONSTRAINT uk_ms_user_platform_product TO uk_ums_user_platform_product;


-- ===================================================================
-- 2) monitor_targets 신규 구조를 담을 임시 테이블 생성
-- ===================================================================
-- 기존 테이블을 직접 변형하기보다, product 단위로 재구성한 뒤 swap 한다.
-- 이렇게 하면 keyword 기반 old row를 product 단위 row로 안전하게 변환하기 쉽다.

DROP TABLE IF EXISTS monitor_targets_new;

CREATE TABLE monitor_targets_new (
    id BIGSERIAL PRIMARY KEY,
    platform VARCHAR(30) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    search_keyword VARCHAR(255) NOT NULL,
    product_url VARCHAR(1000),
    fetch_interval_minutes INTEGER NOT NULL,
    last_fetched_at TIMESTAMPTZ,
    next_fetch_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_monitor_targets_platform_product UNIQUE (platform, product_id)
);


-- ===================================================================
-- 3) products를 기준으로 monitor_targets_new 채우기
-- ===================================================================
-- 기존 구조에서는 products.monitor_target_id -> old monitor_targets(id) 로 연결되어 있다.
-- old monitor_targets의 normalized_keyword를 새 search_keyword로 사용한다.
--
-- 전략:
-- - 상품 1건당 monitor target 1건 생성
-- - product.external_product_id -> new.product_id
-- - old.normalized_keyword -> new.search_keyword
-- - product.product_url -> new.product_url
-- - old scheduling 값(fetch_interval_minutes, last_fetched_at, next_fetch_at) 재사용
-- - created_at / updated_at은 old target 값을 우선 사용

INSERT INTO monitor_targets_new (
    platform,
    product_id,
    search_keyword,
    product_url,
    fetch_interval_minutes,
    last_fetched_at,
    next_fetch_at,
    created_at,
    updated_at
)
SELECT DISTINCT ON (p.platform, p.external_product_id)
    p.platform,
    p.external_product_id AS product_id,
    COALESCE(NULLIF(mt.normalized_keyword, ''), p.external_product_id) AS search_keyword,
    p.product_url,
    COALESCE(mt.fetch_interval_minutes, 10) AS fetch_interval_minutes,
    mt.last_fetched_at,
    mt.next_fetch_at,
    COALESCE(mt.created_at, NOW()) AS created_at,
    COALESCE(mt.updated_at, NOW()) AS updated_at
FROM products p
JOIN monitor_targets mt
  ON mt.id = p.monitor_target_id
ORDER BY p.platform, p.external_product_id, mt.updated_at DESC NULLS LAST, mt.id DESC;


-- ===================================================================
-- 4) products.monitor_target_id를 새 target id로 재매핑
-- ===================================================================

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS new_monitor_target_id BIGINT;

UPDATE products p
SET new_monitor_target_id = mtn.id
FROM monitor_targets_new mtn
WHERE mtn.platform = p.platform
  AND mtn.product_id = p.external_product_id;


-- ===================================================================
-- 5) FK 교체 준비
-- ===================================================================

ALTER TABLE products
    DROP CONSTRAINT IF EXISTS products_monitor_target_id_fkey;


-- ===================================================================
-- 6) old/new 테이블 swap
-- ===================================================================

ALTER TABLE monitor_targets
    RENAME TO monitor_targets_old;

ALTER TABLE monitor_targets_new
    RENAME TO monitor_targets;


-- ===================================================================
-- 7) products.monitor_target_id를 새 monitor_targets에 맞게 교체
-- ===================================================================

ALTER TABLE products
    DROP COLUMN monitor_target_id;

ALTER TABLE products
    RENAME COLUMN new_monitor_target_id TO monitor_target_id;

ALTER TABLE products
    ALTER COLUMN monitor_target_id SET NOT NULL;

ALTER TABLE products
    ADD CONSTRAINT products_monitor_target_id_fkey
    FOREIGN KEY (monitor_target_id) REFERENCES monitor_targets(id);


-- ===================================================================
-- 8) 정리용 인덱스 추가
-- ===================================================================

CREATE INDEX IF NOT EXISTS idx_monitor_targets_next_fetch_at
    ON monitor_targets (next_fetch_at);

CREATE INDEX IF NOT EXISTS idx_monitor_targets_platform_product
    ON monitor_targets (platform, product_id);


-- ===================================================================
-- 9) old 테이블 제거 여부
-- ===================================================================
-- 바로 삭제가 불안하면 아래 DROP 대신 유지한 뒤 수동 검증 후 삭제해도 된다.

DROP TABLE monitor_targets_old;


-- ===================================================================
-- 10) users_monitoring_subscriptions에 snapshot_image_url 컬럼 추가
-- ===================================================================
-- imageUrl은 등록 시점의 상품 이미지 URL 문자열(최대 1000자)을 보관한다.
ALTER TABLE users_monitoring_subscriptions
    ADD COLUMN IF NOT EXISTS snapshot_image_url VARCHAR(1000);


COMMIT;


-- ===================================================================
-- 적용 후 확인 쿼리 예시
-- ===================================================================
-- SELECT table_name FROM information_schema.tables
-- WHERE table_name IN ('users_monitoring_subscriptions', 'monitor_targets');
--
-- SELECT COUNT(*) FROM users_monitoring_subscriptions;
-- SELECT COUNT(*) FROM monitor_targets;
--
-- SELECT platform, product_id, search_keyword, product_url, next_fetch_at
-- FROM monitor_targets
-- ORDER BY updated_at DESC
-- LIMIT 20;

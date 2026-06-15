-- =============================================================================
-- Product 테이블 통합 마이그레이션
-- monitor_targets에 상품 메타데이터 컬럼 추가
-- price_history FK를 product_id → monitor_target_id로 변경
-- products 테이블 삭제
-- =============================================================================

-- 1. monitor_targets에 Product 메타데이터 컬럼 추가
ALTER TABLE monitor_targets ADD COLUMN IF NOT EXISTS title        VARCHAR(255);
ALTER TABLE monitor_targets ADD COLUMN IF NOT EXISTS image_url    VARCHAR(500);
ALTER TABLE monitor_targets ADD COLUMN IF NOT EXISTS mall_name    VARCHAR(120);
ALTER TABLE monitor_targets ADD COLUMN IF NOT EXISTS category_path VARCHAR(255);
ALTER TABLE monitor_targets ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;

-- 2. 기존 products 데이터를 monitor_targets로 복사
UPDATE monitor_targets mt
SET
    title         = p.title,
    image_url     = p.image_url,
    mall_name     = p.mall_name,
    category_path = p.category_path,
    last_seen_at  = p.last_seen_at
FROM products p
WHERE p.monitor_target_id = mt.id;

-- 3. price_history에 monitor_target_id 컬럼 추가
ALTER TABLE price_history ADD COLUMN IF NOT EXISTS monitor_target_id BIGINT;

-- 4. price_history.product_id → monitor_target_id 값 복사 (products 경유)
UPDATE price_history ph
SET monitor_target_id = (
    SELECT p.monitor_target_id
    FROM products p
    WHERE p.id = ph.product_id
);

-- 5. monitor_target_id NOT NULL 제약 적용
ALTER TABLE price_history ALTER COLUMN monitor_target_id SET NOT NULL;

-- 6. price_history 기존 FK 및 product_id 컬럼 제거
ALTER TABLE price_history DROP CONSTRAINT IF EXISTS fk_price_history_product;
ALTER TABLE price_history DROP CONSTRAINT IF EXISTS price_history_product_id_fkey;
ALTER TABLE price_history DROP COLUMN IF EXISTS product_id;

-- 7. price_history에 monitor_target FK 추가
ALTER TABLE price_history
    ADD CONSTRAINT fk_price_history_monitor_target
    FOREIGN KEY (monitor_target_id) REFERENCES monitor_targets(id);

-- 8. products 테이블 삭제
DROP TABLE IF EXISTS products;

ALTER TABLE IF EXISTS payments
    ADD COLUMN IF NOT EXISTS subscription_id BIGINT;

ALTER TABLE IF EXISTS payments
    ADD COLUMN IF NOT EXISTS product_image_url TEXT;

ALTER TABLE IF EXISTS token_transactions
    ADD COLUMN IF NOT EXISTS subscription_id BIGINT;

-- 배달완료 시각(delivered_at) 추가 — 정산 기간을 나누는 기준 (docs/settlement-policy.md)
--   updated_at은 "마지막 수정 시각"이라 이후 다른 컬럼이 바뀌면 움직임 → 배달완료 시각은 따로 저장
--   Order.complete()에서 Clock(Asia/Seoul) 기준으로 한 번만 기록

-- 1. 컬럼 추가 (배달완료 전 주문은 NULL)
ALTER TABLE orders ADD COLUMN delivered_at timestamp(6);

-- 2. 기존 배달완료 주문 백필
--    배달완료는 최종 상태라 이후 수정이 없으므로 updated_at이 곧 배달완료 시각
--    updated_at이 비어 있는 오래된 행은 created_at으로 대신 채움
UPDATE orders
SET delivered_at = COALESCE(updated_at, created_at)
WHERE status = 'DELIVERY_COMPLETED';

-- 3. 배달완료 주문만 배달완료 시각을 가짐 (둘 중 하나만 있으면 거부)
--    → 정산 집계에서 상태와 시각이 어긋나는 행이 생기지 않음
ALTER TABLE orders ADD CONSTRAINT orders_delivered_at_check
    CHECK ((status = 'DELIVERY_COMPLETED') = (delivered_at IS NOT NULL));

-- 4. 정산 조회용 인덱스 — 매장별 + 배달완료 시각 구간 조회
--    (store_id로 시작하므로 매장별 주문 조회에도 사용 가능, 기존 idx_orders_store_id는 V4 그대로 둠)
CREATE INDEX idx_orders_store_delivered_at ON orders (store_id, delivered_at);

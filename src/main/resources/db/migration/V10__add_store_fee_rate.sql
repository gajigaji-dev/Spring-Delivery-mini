-- 매장별 수수료율 (#32) — docs/money-policy.md
--   stores.fee_rate: 매장별 플랫폼 수수료율 (NULL = 공통 수수료율 application.yml payment.fee.rate 사용)
--   - 결제 시점에 "매장 수수료율 → 없으면 공통 수수료율"로 정한 값을 payments.fee_rate에 스냅샷으로 저장
--   - 이 컬럼을 바꿔도 이미 저장된 결제(payments)와 정산(settlements 등)은 바뀌지 않음
--   - 0.0000(수수료 0%)과 NULL(공통 수수료율 사용)은 서로 다른 의미
-- 기존 매장은 NULL로 두어 지금과 똑같이 공통 수수료율을 사용 (백필 없음)
ALTER TABLE stores ADD COLUMN fee_rate numeric(5, 4);

-- payments.fee_rate와 같은 범위 규칙 (0 이상 1 이하, NULL 허용)
ALTER TABLE stores ADD CONSTRAINT stores_fee_rate_check
    CHECK (fee_rate IS NULL OR (fee_rate >= 0 AND fee_rate <= 1));

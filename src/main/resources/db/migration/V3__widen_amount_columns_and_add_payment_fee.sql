-- 1. 금액 컬럼 integer → bigint
--    integer 최대값(약 21억 원)을 넘는 총액도 저장할 수 있도록 엔티티의 int 금액 필드를 long으로 바꾼 것에 맞춤
--    integer → bigint는 값 손실이 없는 변환이라 기존 데이터는 그대로 유지됨
ALTER TABLE menus    ALTER COLUMN price       TYPE bigint;
ALTER TABLE orders   ALTER COLUMN total_price TYPE bigint;
ALTER TABLE payments ALTER COLUMN amount      TYPE bigint;

-- 2. 결제 플랫폼 수수료 컬럼 추가
--    fee_rate:          결제 시점의 수수료율 스냅샷 (설정값이 나중에 바뀌어도 이 결제의 계산 근거가 남도록)
--    fee_amount:        수수료 = amount × fee_rate, 원 단위 HALF_UP 반올림
--    settlement_amount: 정산액 = amount - fee_amount
ALTER TABLE payments ADD COLUMN fee_rate          numeric(5, 4);
ALTER TABLE payments ADD COLUMN fee_amount        bigint;
ALTER TABLE payments ADD COLUMN settlement_amount bigint;

-- 수수료 도입 전에 완료된 결제는 수수료가 없던 시점이므로 수수료 0, 정산액 = 결제액으로 채움
UPDATE payments
SET fee_rate          = 0,
    fee_amount        = 0,
    settlement_amount = amount;

ALTER TABLE payments ALTER COLUMN fee_rate          SET NOT NULL;
ALTER TABLE payments ALTER COLUMN fee_amount        SET NOT NULL;
ALTER TABLE payments ALTER COLUMN settlement_amount SET NOT NULL;

-- 애플리케이션 밖(직접 SQL 등)에서 잘못된 값이 들어오지 않도록 DB에서도 같은 규칙을 강제
ALTER TABLE payments ADD CONSTRAINT payments_fee_rate_check
    CHECK (fee_rate >= 0 AND fee_rate <= 1);
-- "fee_amount + settlement_amount = amount"를 덧셈으로 쓰지 않는 이유:
--   두 값이 각각 bigint 범위 안이어도 더하는 순간 범위를 넘을 수 있음 (예: 9223372036854775807 + 1)
--   → CHECK 위반이 아니라 "bigint out of range" 에러로 문장이 실패함 (AmountMigrationTest로 확인)
--   PostgreSQL은 AND 조건의 평가 순서를 보장하지 않으므로 앞의 >= 0 검사로 막을 수도 없고,
--   뺄셈(amount - fee_amount)도 amount가 음수 극단값이면 똑같이 넘침
-- → numeric(임의 정밀도)으로 올려서 비교 — 어떤 bigint 값이 들어와도 넘치지 않고 CHECK 위반으로 거부됨
--   (amount >= 0은 fee_amount >= 0, settlement_amount >= 0과 합계 식에서 자동으로 성립)
ALTER TABLE payments ADD CONSTRAINT payments_fee_amount_check
    CHECK (fee_amount >= 0
       AND settlement_amount >= 0
       AND fee_amount::numeric + settlement_amount::numeric = amount::numeric);

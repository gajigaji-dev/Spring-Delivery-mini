-- 정산 상태에 PENDING(확정 대기) 추가, 관리자(ADMIN) 역할 추가 — docs/settlement-policy.md
--   PENDING(스케줄러 집계) → CONFIRMED(관리자 확정) → PAID(관리자 지급 처리)
--   기존 행은 바꾸지 않음: V7에서 만들어진 정산은 이미 CONFIRMED(confirmed_at 있음)라 새 제약도 만족

-- 1. 확정 시각은 관리자가 확정할 때 기록 → PENDING 동안은 비어 있음
ALTER TABLE settlements ALTER COLUMN confirmed_at DROP NOT NULL;

-- 2. 상태 값에 PENDING 추가
ALTER TABLE settlements DROP CONSTRAINT settlements_status_check;
ALTER TABLE settlements ADD CONSTRAINT settlements_status_check
    CHECK (status IN ('PENDING', 'CONFIRMED', 'PAID'));

-- 3. 확정 대기 상태 ⇔ 확정 시각 없음 (CONFIRMED, PAID는 확정 시각이 반드시 있음)
--    지급 시각 제약(settlements_paid_at_check: PAID ⇔ paid_at 있음)은 V7 그대로
ALTER TABLE settlements ADD CONSTRAINT settlements_confirmed_at_check
    CHECK ((status = 'PENDING') = (confirmed_at IS NULL));

-- 4. 관리자 역할 — 회원가입으로는 만들 수 없고(UserService), 운영자가 DB에서 직접 지정
--    예) UPDATE users SET role = 'ADMIN' WHERE username = 'admin';
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CUSTOMER', 'OWNER', 'ADMIN'));

-- User, Payment가 BaseEntity를 상속하면서 필요해진 생성·수정 시각 컬럼
-- 기존 DB에는 이 컬럼이 없는 상태로 baseline 되었으므로 여기서 추가
-- IF NOT EXISTS: ddl-auto: update로 이미 컬럼이 생긴 DB에서도 실패하지 않도록
-- NULL 허용: 기존 행에는 값이 없음 (BaseEntity 주석 참고, 신규 행은 JPA Auditing이 채움)

ALTER TABLE users    ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE users    ADD COLUMN IF NOT EXISTS updated_at timestamp(6);

ALTER TABLE payments ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS updated_at timestamp(6);

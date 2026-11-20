-- Refresh Token 후속 (V15 후속) — docs/architecture.md 인증 장
--   1. 절대 만료: 재발급할 때마다 수명이 다시 시작되어 로그인이 끝나지 않던 문제 → 계열(로그인 1번)의 최종 만료 시각
--   2. 보관 정리용 인덱스: 만료된 지 오래된 행을 maintenance.RetentionCleaner가 삭제

-- 1. 계열 절대 만료 시각 — 같은 계열의 모든 행이 같은 값 (로그인 시각 + auth.refresh-token.absolute-ttl)
--    각 행의 expires_at은 이 값을 넘지 않음 (재발급 시 min(재발급 시각 + ttl, family_expires_at))
ALTER TABLE refresh_tokens ADD COLUMN family_expires_at timestamp(6);

-- 기존 행 백필: 계열의 첫 발급 시각 + 30일(기본 absolute-ttl). 단, 이미 그보다 늦게 만료되는 행이 있는 계열은
-- 그 행의 만료 시각까지로 둠 → 이미 발급된 토큰을 마이그레이션이 앞당겨 만료시키지 않고, 아래 CHECK도 지켜짐
UPDATE refresh_tokens t
SET family_expires_at = f.family_expires_at
FROM (SELECT family_id,
             GREATEST(MIN(created_at) + INTERVAL '30 days', MAX(expires_at)) AS family_expires_at
      FROM refresh_tokens
      GROUP BY family_id) f
WHERE t.family_id = f.family_id;

ALTER TABLE refresh_tokens
    ALTER COLUMN family_expires_at SET NOT NULL,
    ADD CONSTRAINT refresh_tokens_family_expires_at_check CHECK (expires_at <= family_expires_at);

-- 2. 만료된 행 정리용 (expires_at이 보관 기간보다 오래된 행 삭제)
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

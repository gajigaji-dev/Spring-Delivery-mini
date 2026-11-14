-- 부하 테스트용 데이터 (#40) — 측정 전용 빈 DB에만 실행 (개발·운영 DB에 실행 금지)
-- 앱을 한 번 기동해 Flyway로 스키마를 만든 뒤 실행: psql -f perf/seed.sql
-- id를 직접 지정해 매번 같은 분포가 만들어지도록 함 (개선 전후를 같은 데이터로 비교하기 위해)
--
-- 규모: 고객 1,000 / 점주 40 / 매장 200(점주당 5) / 메뉴 4,000(매장당 20) / 주문 300,000 / 주문 항목 600,000
--   고객 1명당 주문 300건, 매장 1곳당 1,500건, 점주 1명당 7,500건
-- 로그인 계정: perf_customer(id 1), perf_owner(id 1001, 매장 1~5) — 비밀번호는 아래 :password

\set password 'perf-password-1!'

-- 로그인 계정 2개의 비밀번호만 BCrypt로 만들기 위해 사용 (BCryptPasswordEncoder가 읽는 $2a$ 형식)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;

-- 고객 1~1000, 점주 1001~1040. 로그인하지 않는 계정은 해시가 아닌 값(로그인 불가)
INSERT INTO users (id, username, password, role, created_at, updated_at)
SELECT n,
       CASE n WHEN 1 THEN 'perf_customer' WHEN 1001 THEN 'perf_owner'
              ELSE CASE WHEN n <= 1000 THEN 'perf_c' ELSE 'perf_o' END || n END,
       CASE WHEN n IN (1, 1001) THEN crypt(:'password', gen_salt('bf', 10)) ELSE 'not-a-hash' END,
       CASE WHEN n <= 1000 THEN 'CUSTOMER' ELSE 'OWNER' END,
       TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00'
FROM generate_series(1, 1040) AS n;

-- 매장 1~200, 점주당 5개 (perf_owner = 매장 1~5)
INSERT INTO stores (id, name, owner_id, created_at, updated_at)
SELECT n, '부하테스트 매장 ' || n, 1001 + (n - 1) / 5,
       TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00'
FROM generate_series(1, 200) AS n;

-- 메뉴 1~4000, 매장당 20개 (매장 s의 메뉴 = (s-1)*20+1 ~ s*20), 매장마다 마지막 1개는 삭제 상태
INSERT INTO menus (id, store_id, name, price, description, deleted, created_at, updated_at)
SELECT n, (n - 1) / 20 + 1, '메뉴 ' || n, 5000 + (n % 20) * 500, '부하 테스트용 메뉴', n % 20 = 0,
       TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00'
FROM generate_series(1, 4000) AS n;

-- 주문 1~300000: 고객 = (id-1) % 1000 + 1, 매장 = ((id-1)/1000 + (id-1)%1000) % 200 + 1
--   → 한 고객의 주문이 테이블 전체에 흩어지고(실제처럼 시간순으로 섞임) 여러 매장에 걸침
-- 결제가 필요 없는 상태만 사용 (주문요청 90%, 주문취소 10%) → payments·정산 데이터 없이 목록 조회만 측정
-- 항목 2개: 메뉴 a = 매장의 1~9번째, 메뉴 b = 10~18번째 (삭제된 20번째는 쓰지 않음)
CREATE TEMP TABLE perf_orders ON COMMIT DROP AS
SELECT id, user_id, store_id,
       (store_id - 1) * 20 + 1 + id % 9  AS menu_a,
       (store_id - 1) * 20 + 10 + id % 9 AS menu_b,
       1 + (id % 3)::int                 AS qty_a
FROM (SELECT n AS id,
             (n - 1) % 1000 + 1                         AS user_id,
             ((n - 1) / 1000 + (n - 1) % 1000) % 200 + 1 AS store_id
      FROM generate_series(1, 300000) AS n) t;

INSERT INTO orders (id, user_id, store_id, total_price, delivery_address, status, created_at, updated_at)
SELECT p.id, p.user_id, p.store_id, a.price * p.qty_a + b.price,
       '서울시 부하구 테스트로 ' || p.id,
       CASE WHEN p.id % 10 = 0 THEN 'ORDER_CANCELLED' ELSE 'ORDER_REQUEST' END,
       TIMESTAMP '2026-01-01 00:00:00' + p.id * INTERVAL '1 minute',
       TIMESTAMP '2026-01-01 00:00:00' + p.id * INTERVAL '1 minute'
FROM perf_orders p
JOIN menus a ON a.id = p.menu_a
JOIN menus b ON b.id = p.menu_b
ORDER BY p.id;

INSERT INTO order_items (order_id, menu_id, menu_name, unit_price, quantity)
SELECT p.id, m.id, m.name, m.price, CASE WHEN m.id = p.menu_a THEN p.qty_a ELSE 1 END
FROM perf_orders p
JOIN menus m ON m.id IN (p.menu_a, p.menu_b)
ORDER BY p.id, m.id;

-- id를 직접 넣었으므로 IDENTITY 시퀀스를 마지막 id 뒤로 이동 (이후 API로 만드는 행과 충돌 방지)
SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT max(id) FROM users));
SELECT setval(pg_get_serial_sequence('stores', 'id'), (SELECT max(id) FROM stores));
SELECT setval(pg_get_serial_sequence('menus', 'id'), (SELECT max(id) FROM menus));
SELECT setval(pg_get_serial_sequence('orders', 'id'), (SELECT max(id) FROM orders));

COMMIT;

-- 실행 계획이 실제 분포를 반영하도록 통계 갱신
ANALYZE;

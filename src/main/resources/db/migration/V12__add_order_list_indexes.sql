-- 주문 목록 페이지 조회용 인덱스 (#40)
--   SELECT ... FROM orders WHERE user_id = ?  ORDER BY id OFFSET ? ROWS FETCH FIRST ? ROWS ONLY  (고객의 주문 목록)
--   SELECT ... FROM orders WHERE store_id = ? ORDER BY id OFFSET ? ROWS FETCH FIRST ? ROWS ONLY  (매장별 주문 목록)
--
-- 근거: 주문 30만 건(고객당 300건, 매장당 1,500건)에서 EXPLAIN (ANALYZE, BUFFERS)로 읽은 버퍼 수·실행 시간을 비교
--   앱은 준비된 문장(prepared statement)으로 실행하므로 OFFSET·LIMIT 값을 모르는 계획(generic plan) 기준 — docs/performance.md 4장
--   두 인덱스 모두 (조건 컬럼, id) 순서라 정렬 없이 해당 고객·매장의 행만 id 순서로 읽고, 필요한 만큼 읽으면 멈춤
--   → 조회 비용이 주문 테이블 전체 크기가 아니라 그 고객·매장의 주문 수에만 비례

-- 1. 고객의 주문 목록
-- orders.user_id에는 FK만 있고 인덱스가 없었음 (PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않음)
--   → 고객 1명의 주문을 찾기 위해 PK를 순서대로 읽으며 걸러내거나 테이블 전체를 읽음
--   첫 페이지:    402 버퍼 → 27     (PK 스캔 후 19,980행 버림 → 21행만 읽음)
--   마지막 페이지: 5,249 버퍼 → 304  (테이블 전체 스캔 14.4ms → 해당 고객의 300행만 1.4ms)
CREATE INDEX idx_orders_user_id_id ON orders (user_id, id);

-- 2. 매장별 주문 목록
-- 기존 store_id 인덱스(V4·V6)는 id 순서를 주지 못해, 값을 모르는 계획에서는 PK를 id 순서대로 읽으며 매장을 걸러냄
--   → 뒤쪽 페이지일수록 다른 매장의 주문까지 읽고, 마지막 페이지는 테이블 전체를 읽음
--   첫 페이지:    87 버퍼 → 24       (PK 스캔 후 4,176행 버림 → 21행만 읽음)
--   마지막 페이지: 5,995 버퍼 → 1,508 (PK 전체 스캔 후 298,500행 버림 38.4ms → 해당 매장의 1,500행만 3.9ms)
CREATE INDEX idx_orders_store_id_id ON orders (store_id, id);

-- 3. idx_orders_store_id (store_id)는 (store_id, id)의 앞부분과 같아 역할이 겹침 → 제거
--   store_id만으로 찾는 조회·stores FK 검사는 새 인덱스가 그대로 처리 (측정 중 기존 인덱스 사용 횟수 0)
--   → orders에 쓰기마다 갱신하는 인덱스는 고객용 1개만 늘어남
DROP INDEX idx_orders_store_id;

-- 점주의 전체 매장 주문 목록(stores.owner_id 조인)은 인덱스로 개선되지 않아 추가하지 않음 (docs/performance.md 6장 남은 병목)
--
-- 주의: CREATE INDEX는 만드는 동안 orders 쓰기를 막음. 운영에서 주문이 많이 쌓인 뒤라면
--   트랜잭션 밖에서 CREATE INDEX CONCURRENTLY로 적용하는 방법을 검토 (지금은 배포 전이라 일반 생성)

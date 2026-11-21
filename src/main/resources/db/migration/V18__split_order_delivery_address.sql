-- 배송 주소를 우편번호·도로명 주소·상세 주소로 나눠 저장 (#77)
--   주소 검색(다음 우편번호 서비스)으로 받은 값을 그대로 남겨, 배달·우편번호별 집계에 쓸 수 있게 함
--
-- 기존 주문과의 호환
--   - delivery_address(전체 주소 한 줄)는 그대로 둠: 기존 주문 조회·상세 화면·응답 필드가 바뀌지 않음
--     새 주문도 "도로명 주소 + 상세 주소"를 합쳐 계속 채움 (Order 생성자)
--   - 기존 주문은 한 줄 주소를 우편번호·도로명·상세로 안전하게 나눌 방법이 없음
--     → 세 컬럼을 NULL로 두고 백필하지 않음 (잘못 나눈 값을 남기는 것보다 원래 값만 두는 쪽이 안전)
--   - 컬럼 추가만 하고 기본값이 없으므로 기존 행을 다시 쓰지 않음 (메타데이터만 변경)

ALTER TABLE orders
    ADD COLUMN zip_code       varchar(5),
    ADD COLUMN road_address   varchar(150),
    ADD COLUMN detail_address varchar(100);

-- 나눠 저장한 주소는 "우편번호·도로명이 모두 있음"(상세 주소는 선택)이거나, 기존 주문처럼 셋 다 없어야 함
-- 우편번호는 도로명 주소 체계의 5자리 숫자 (6자리 구 우편번호는 다음 우편번호 서비스도 돌려주지 않음)
ALTER TABLE orders ADD CONSTRAINT orders_address_parts_check
    CHECK (
        (zip_code IS NULL AND road_address IS NULL AND detail_address IS NULL)
        OR (zip_code ~ '^[0-9]{5}$' AND road_address IS NOT NULL)
    );

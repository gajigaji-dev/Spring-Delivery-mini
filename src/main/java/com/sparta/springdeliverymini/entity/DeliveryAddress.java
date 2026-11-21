package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidAddressException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

// 배송 주소 (#77) — 우편번호·도로명 주소·상세 주소
// 값은 생성할 때 한 번 검증하고 바꾸지 않음 → 저장된 주소는 항상 아래 규칙을 만족
//
// 규칙 (프론트 frontend/src/address/validateAddress.ts와 같은 값, DB CHECK는 V18)
// - 우편번호: 숫자 5자리 (도로명 주소 체계)
// - 도로명 주소: 필수, 150자 이하 — 다음 우편번호 서비스의 도로명 주소 + 참고 항목(동·건물명)이 들어감
// - 상세 주소: 선택(단독 주택은 없을 수 있음), 100자 이하
// - 앞뒤 공백은 지우고, 줄바꿈·탭 등 제어 문자는 거부 (알림·영수증 한 줄 표시가 깨지지 않도록)
// 전체 주소(fullAddress) = 도로명 + " " + 상세 ≤ 251자 → 기존 delivery_address(varchar 255)에 그대로 들어감
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryAddress {

    public static final int ROAD_ADDRESS_MAX = 150;
    public static final int DETAIL_ADDRESS_MAX = 100;

    private static final Pattern ZIP_CODE = Pattern.compile("^[0-9]{5}$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    // 기존 주문(V18 이전)은 세 컬럼이 모두 NULL → Order.getAddress()가 null을 돌려줌
    @Column(name = "zip_code", length = 5)
    private String zipCode;

    @Column(name = "road_address", length = ROAD_ADDRESS_MAX)
    private String roadAddress;

    // 상세 주소가 없으면 빈 문자열이 아니라 NULL로 저장
    @Column(name = "detail_address", length = DETAIL_ADDRESS_MAX)
    private String detailAddress;

    public DeliveryAddress(String zipCode, String roadAddress, String detailAddress) {
        String zip = trim(zipCode);
        String road = trim(roadAddress);
        String detail = trim(detailAddress);

        if (zip == null || !ZIP_CODE.matcher(zip).matches()) {
            throw new InvalidAddressException("우편번호는 숫자 5자리여야 합니다.");
        }
        if (road == null) {
            throw new InvalidAddressException("도로명 주소를 입력해야 합니다.");
        }
        if (road.length() > ROAD_ADDRESS_MAX) {
            throw new InvalidAddressException("도로명 주소는 " + ROAD_ADDRESS_MAX + "자 이하여야 합니다.");
        }
        if (detail != null && detail.length() > DETAIL_ADDRESS_MAX) {
            throw new InvalidAddressException("상세 주소는 " + DETAIL_ADDRESS_MAX + "자 이하여야 합니다.");
        }
        if (CONTROL_CHARS.matcher(road).find() || (detail != null && CONTROL_CHARS.matcher(detail).find())) {
            throw new InvalidAddressException("주소에 줄바꿈 등 사용할 수 없는 문자가 있습니다.");
        }

        this.zipCode = zip;
        this.roadAddress = road;
        this.detailAddress = detail;
    }

    // 한 줄 주소 — 기존 delivery_address 컬럼·응답 필드(deliveryAddress)에 그대로 씀
    public String fullAddress() {
        return detailAddress == null ? roadAddress : roadAddress + " " + detailAddress;
    }

    // 공백뿐인 값은 없는 값으로 봄
    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

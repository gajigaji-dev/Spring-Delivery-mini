package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidAddressException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryAddressTest {

    private static final String ROAD = "서울 강남구 테헤란로 152 (역삼동, 강남파이낸스센터)";

    @Test
    void 앞뒤_공백을_지우고_한_줄_주소는_도로명과_상세를_공백으로_잇는다() {
        DeliveryAddress address = new DeliveryAddress(" 06236 ", "  " + ROAD + " ", " 101동 1203호 ");

        assertThat(address.getZipCode()).isEqualTo("06236");
        assertThat(address.getRoadAddress()).isEqualTo(ROAD);
        assertThat(address.getDetailAddress()).isEqualTo("101동 1203호");
        assertThat(address.fullAddress()).isEqualTo(ROAD + " 101동 1203호");
    }

    // 단독 주택처럼 상세 주소가 없는 경우 — 빈 문자열이 아니라 null로 저장
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void 상세_주소는_없어도_되고_null로_저장된다(String detail) {
        DeliveryAddress address = new DeliveryAddress("06236", ROAD, detail);

        assertThat(address.getDetailAddress()).isNull();
        assertThat(address.fullAddress()).isEqualTo(ROAD);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234", "123456", "12a45", "135-080"})
    void 우편번호가_숫자_5자리가_아니면_거부한다(String zipCode) {
        assertThatThrownBy(() -> new DeliveryAddress(zipCode, ROAD, null))
                .isInstanceOf(InvalidAddressException.class)
                .hasMessage("우편번호는 숫자 5자리여야 합니다.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void 도로명_주소가_없으면_거부한다(String road) {
        assertThatThrownBy(() -> new DeliveryAddress("06236", road, null))
                .isInstanceOf(InvalidAddressException.class)
                .hasMessage("도로명 주소를 입력해야 합니다.");
    }

    @Test
    void 길이_제한을_넘으면_거부하고_경계값은_허용한다() {
        String road150 = "가".repeat(DeliveryAddress.ROAD_ADDRESS_MAX);
        String detail100 = "나".repeat(DeliveryAddress.DETAIL_ADDRESS_MAX);

        // 두 값을 합친 한 줄 주소(251자)도 기존 delivery_address(varchar 255)에 들어감
        assertThat(new DeliveryAddress("06236", road150, detail100).fullAddress()).hasSizeLessThanOrEqualTo(255);
        assertThatThrownBy(() -> new DeliveryAddress("06236", road150 + "가", null))
                .isInstanceOf(InvalidAddressException.class);
        assertThatThrownBy(() -> new DeliveryAddress("06236", ROAD, detail100 + "나"))
                .isInstanceOf(InvalidAddressException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"101동\n1203호", "101동\t1203호", "101동\u00001203호"})
    void 줄바꿈_등_제어_문자가_있으면_거부한다(String detail) {
        assertThatThrownBy(() -> new DeliveryAddress("06236", ROAD, detail))
                .isInstanceOf(InvalidAddressException.class)
                .hasMessage("주소에 줄바꿈 등 사용할 수 없는 문자가 있습니다.");
    }
}

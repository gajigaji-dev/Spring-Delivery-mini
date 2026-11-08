package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.PaymentFeeProperties;
import com.sparta.springdeliverymini.dto.AdminStoreFeeRateResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// AdminStoreService — 관리자 확인, 매장 조회(404), 수수료율 설정·해제와 응답 (DB 없이)
@ExtendWith(MockitoExtension.class)
class AdminStoreServiceTest {

    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;

    private AdminStoreService service;
    private Store store;

    @BeforeEach
    void setUp() {
        service = new AdminStoreService(storeRepository, userRepository,
                new PaymentFeeProperties(new BigDecimal("0.05")));
        store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));
        ReflectionTestUtils.setField(store, "id", 1L);
    }

    private void givenUser(String username, Role role) {
        given(userRepository.findByUsername(username)).willReturn(Optional.of(new User(username, "pw", role)));
    }

    @Test
    void 관리자는_매장_수수료율을_설정할_수_있다() {
        givenUser("admin", Role.ADMIN);
        given(storeRepository.findById(1L)).willReturn(Optional.of(store));

        AdminStoreFeeRateResponse response = service.changeFeeRate(1L, new BigDecimal("0.03"), "admin");

        assertThat(store.getFeeRate()).isEqualByComparingTo("0.03");
        assertThat(response.storeId()).isEqualTo(1L);
        assertThat(response.feeRate()).isEqualByComparingTo("0.03");
        assertThat(response.effectiveFeeRate()).isEqualByComparingTo("0.03");
        assertThat(response.defaultFeeRateApplied()).isFalse();
    }

    @Test
    void 관리자는_매장_수수료율을_해제할_수_있고_공통_수수료율이_적용된다() {
        store.changeFeeRate(new BigDecimal("0.03"));
        givenUser("admin", Role.ADMIN);
        given(storeRepository.findById(1L)).willReturn(Optional.of(store));

        AdminStoreFeeRateResponse response = service.clearFeeRate(1L, "admin");

        assertThat(store.getFeeRate()).isNull();
        assertThat(response.feeRate()).isNull();
        assertThat(response.effectiveFeeRate()).isEqualByComparingTo("0.05");
        assertThat(response.defaultFeeRateApplied()).isTrue();
    }

    @Test
    void 관리자가_아니면_403이고_매장을_조회하지_않는다() {
        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            givenUser("user1", role);

            assertThatThrownBy(() -> service.changeFeeRate(1L, new BigDecimal("0.03"), "user1"))
                    .isInstanceOf(ApiException.class)
                    .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
            assertThatThrownBy(() -> service.clearFeeRate(1L, "user1"))
                    .isInstanceOf(ApiException.class)
                    .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
        verify(storeRepository, never()).findById(anyLong());
    }

    @Test
    void 삭제된_회원이면_401() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeFeeRate(1L, new BigDecimal("0.03"), "ghost"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 매장이_없으면_404() {
        givenUser("admin", Role.ADMIN);
        given(storeRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeFeeRate(99L, new BigDecimal("0.03"), "admin"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}

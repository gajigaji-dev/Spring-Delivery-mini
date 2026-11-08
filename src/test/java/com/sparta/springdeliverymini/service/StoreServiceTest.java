package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.StoreCreateRequest;
import com.sparta.springdeliverymini.dto.StoreResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// StoreService의 매장 생성 권한과 매장 소유권 검증(404/403)을 검증
@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private StoreService storeService;

    private final User owner = user(1L, "owner1", Role.OWNER);
    private final User otherOwner = user(2L, "owner2", Role.OWNER);
    private final User customer = user(3L, "customer1", Role.CUSTOMER);

    private static User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Store store() {
        Store store = new Store("치킨집", owner);
        ReflectionTestUtils.setField(store, "id", 10L);
        return store;
    }

    // ===== 매장 생성 =====

    @Test
    void OWNER는_본인_소유의_매장을_생성한다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeRepository.save(any(Store.class))).willAnswer(invocation -> invocation.getArgument(0));

        StoreResponse response = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");

        assertThat(response.name()).isEqualTo("치킨집");
        assertThat(response.ownerId()).isEqualTo(1L);
    }

    @Test
    void CUSTOMER는_서비스에서도_매장_생성이_막힌다() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> storeService.createStore(new StoreCreateRequest("치킨집"), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(storeRepository, never()).save(any());
    }

    @Test
    void 없는_회원은_매장을_생성할_수_없다() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.createStore(new StoreCreateRequest("치킨집"), "ghost"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ===== 조회 =====

    @Test
    void 없는_매장은_404() {
        given(storeRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getStore(99L))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ===== 소유권 검증 =====

    @Test
    void 본인_매장이면_매장을_반환한다() {
        Store store = store();
        given(storeRepository.findById(10L)).willReturn(Optional.of(store));

        assertThat(storeService.getOwnedStore(10L, owner)).isSameAs(store);
    }

    @Test
    void 다른_OWNER의_매장이면_403() {
        given(storeRepository.findById(10L)).willReturn(Optional.of(store()));

        assertThatThrownBy(() -> storeService.getOwnedStore(10L, otherOwner))
                .isInstanceOf(ApiException.class)
                .hasMessage("본인 매장이 아닙니다.")
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 소유권_검증_대상_매장이_없으면_404() {
        given(storeRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getOwnedStore(99L, owner))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}

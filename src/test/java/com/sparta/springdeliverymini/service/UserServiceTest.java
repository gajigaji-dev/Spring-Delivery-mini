package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.MyInfoResponse;
import com.sparta.springdeliverymini.dto.SignupRequest;
import com.sparta.springdeliverymini.entity.Role;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

// 회원가입 — 관리자 계정은 가입으로 만들 수 없음 / 내 정보 조회(#62) — DB 기준 역할·OWNER 매장 id (DB 없이)
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthTokenService authTokenService;

    @InjectMocks
    private UserService userService;

    @Test
    void ADMIN으로는_가입할_수_없다() {
        SignupRequest request = new SignupRequest("admin1", "password123", Role.ADMIN);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    private User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void OWNER의_내_정보에는_매장_id_목록이_포함된다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(user(1L, "owner1", Role.OWNER)));
        given(storeRepository.findIdsByOwnerId(1L)).willReturn(List.of(3L, 7L));

        MyInfoResponse response = userService.getMyInfo("owner1");

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo("owner1");
        assertThat(response.role()).isEqualTo(Role.OWNER);
        assertThat(response.storeIds()).containsExactly(3L, 7L);
    }

    @Test
    void CUSTOMER는_매장을_조회하지_않고_빈_목록을_받는다() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(user(2L, "customer1", Role.CUSTOMER)));

        MyInfoResponse response = userService.getMyInfo("customer1");

        assertThat(response.role()).isEqualTo(Role.CUSTOMER);
        assertThat(response.storeIds()).isEmpty();
        verifyNoInteractions(storeRepository);
    }

    @Test
    void 토큰은_유효하지만_회원이_없으면_401() {
        given(userRepository.findByUsername("deleted")).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMyInfo("deleted"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

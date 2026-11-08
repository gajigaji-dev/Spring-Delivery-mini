package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.SignupRequest;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

// 회원가입 — 관리자 계정은 가입으로 만들 수 없음 (DB 없이)
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtProvider jwtProvider;

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
}

package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.LoginRequest;
import com.sparta.springdeliverymini.dto.LoginResponse;
import com.sparta.springdeliverymini.dto.SignupRequest;
import com.sparta.springdeliverymini.dto.UserResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true) // 기본은 읽기 전용, 저장이 필요한 메서드만 @Transactional로 덮어씀
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // SecurityConfig에 등록한 BCryptPasswordEncoder
    private final JwtProvider jwtProvider;

    @Transactional
    public UserResponse signup(SignupRequest request) {

        // 0. 관리자 계정은 가입으로 만들 수 없음 → 400 (관리자는 운영자가 DB에서 직접 지정)
        //    막지 않으면 누구나 role: ADMIN으로 가입해 정산 확정·지급 권한을 얻게 됨
        if (request.role() == Role.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "role은 CUSTOMER 또는 OWNER만 가입할 수 있습니다.");
        }

        // 1. 아이디 중복 확인
        // 이미 같은 아이디가 있으면 → 409
        if (userRepository.existsByUsername(request.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }

        // 2. 비밀번호 암호화
        // 평문 그대로 저장하지 않고 BCrypt 해시로 변환해서 저장
        // BCrypt는 같은 비밀번호라도 매번 다른 salt를 붙여 다른 해시값이 나옴
        String encodedPassword = passwordEncoder.encode(request.password());

        // 3. 회원 생성
        // role(CUSTOMER/OWNER)은 가입 시 요청으로 받은 값 사용
        User user = new User(request.username(), encodedPassword, request.role());

        // 4. DB에 저장
        // 동시에 같은 아이디로 가입하면 1번 검사를 둘 다 통과할 수 있음
        // saveAndFlush로 INSERT를 즉시 실행해 username unique 제약 위반을 여기서 잡아 409로 변환
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }

        // 5. 응답 DTO로 변환
        // 응답에는 비밀번호를 포함하지 않음 (id, username, role만)
        return UserResponse.from(user);
    }

    public LoginResponse login(LoginRequest request) {

        // 1. 아이디로 회원 조회
        // 아이디/비밀번호 중 어느 쪽이 틀렸는지 노출하지 않도록 같은 메시지 사용 → 401
        // (메시지가 다르면 "이 아이디는 가입돼 있다"는 정보가 새어 나감)
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."));

        // 2. 비밀번호 확인
        // 저장된 해시를 복호화하는 게 아니라, 입력한 비밀번호를 같은 salt로 해시해서 비교 → 틀리면 401
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 3. JWT 발급
        // 토큰에 username(subject)과 role을 담음
        // 이후 요청에서는 JwtAuthenticationFilter가 이 토큰으로 사용자와 권한을 확인
        return LoginResponse.bearer(jwtProvider.createToken(user.getUsername(), user.getRole()));
    }
}

package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.LoginRequest;
import com.sparta.springdeliverymini.dto.MyInfoResponse;
import com.sparta.springdeliverymini.dto.SignupRequest;
import com.sparta.springdeliverymini.dto.UserResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true) // 기본은 읽기 전용, 저장이 필요한 메서드만 @Transactional로 덮어씀
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class UserService {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository; // 내 정보 조회의 OWNER 매장 id 목록 (#62)
    private final PasswordEncoder passwordEncoder; // SecurityConfig에 등록한 BCryptPasswordEncoder
    private final AuthTokenService authTokenService; // 로그인 성공 시 Access·Refresh Token 발급

    @Transactional
    public UserResponse signup(SignupRequest request) {

        // 0. 가입은 고객(CUSTOMER)만 → 그 밖의 role은 400 (V17 승인제)
        //    - ADMIN: 막지 않으면 누구나 정산 확정·지급 권한을 얻음 (관리자는 운영자가 DB에서 직접 지정)
        //    - OWNER: 확인 없이 매장·메뉴를 만들 수 있었음 → 가입 후 사장님 전환 신청 + 관리자 승인 (OwnerApplicationService)
        //    role을 생략하면 CUSTOMER (기존 클라이언트가 role: CUSTOMER를 보내도 그대로 동작)
        if (request.role() != null && request.role() != Role.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "회원가입은 고객(CUSTOMER)만 가능합니다. 사장님은 가입 후 사장님 전환을 신청해주세요.");
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
        // 가입 회원은 항상 CUSTOMER (위에서 다른 값은 거절)
        User user = new User(request.username(), encodedPassword, Role.CUSTOMER);

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

    // 내 정보 조회 (#62) — 토큰의 username으로 DB의 현재 회원을 찾아 응답
    // 토큰의 role은 발급 시점 값이라 그 뒤 역할 변경이 반영되지 않음 → 응답의 role은 DB 값
    public MyInfoResponse getMyInfo(String username) {

        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401 (다른 API와 같은 기준)
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // OWNER는 매장 선택 화면을 바로 그릴 수 있도록 매장 id 목록 포함 (매장 상세는 /api/stores/{id})
        // 그 밖의 역할은 쿼리 없이 빈 배열 → 클라이언트가 null 처리를 하지 않아도 됨
        List<Long> storeIds = user.getRole() == Role.OWNER
                ? storeRepository.findIdsByOwnerId(user.getId())
                : List.of();

        return MyInfoResponse.of(user, storeIds);
    }

    // 쓰기 트랜잭션: Refresh Token 행을 저장함 (클래스 기본값은 읽기 전용)
    @Transactional
    public IssuedTokens login(LoginRequest request) {

        // 1. 아이디로 회원 조회
        // 아이디/비밀번호 중 어느 쪽이 틀렸는지 노출하지 않도록 같은 메시지 사용 → 401
        // (메시지가 다르면 "이 아이디는 가입돼 있다"는 정보가 새어 나감)
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."));

        // 2. 비밀번호 확인
        // 저장된 해시를 복호화하는 게 아니라, 입력한 비밀번호를 같은 salt로 해시해서 비교 → 틀리면 401
        // 소셜 전용 회원(비밀번호 없음, V22)도 같은 401 — 소셜 가입 여부를 알려주지 않음
        if (!user.hasPassword() || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 3. 토큰 발급
        // Access Token(JWT): username(subject)과 role을 담음 → 이후 요청에서 JwtAuthenticationFilter가 사용자와 권한을 확인
        // Refresh Token: 새 계열(로그인 1번)로 DB에 해시 저장, 원문은 컨트롤러가 HttpOnly 쿠키로 내려보냄
        // 로그인할 때마다 계열이 새로 생김 → 기기·브라우저별 로그인이 서로 영향 없음 (로그아웃도 그 계열만)
        return authTokenService.issue(user);
    }
}

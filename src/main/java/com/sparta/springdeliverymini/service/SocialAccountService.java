package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.SocialAccountsResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.SocialAccount;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.repository.SocialAccountRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.social.SocialClientRegistrations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Optional;

// 소셜 로그인·계정 연결·해제 (V22) — docs/architecture.md 소셜 로그인 장
//
// 로그인: (공급자, 공급자 회원 id)로 연결된 회원을 찾아 기존 로그인과 같은 토큰(AuthTokenService.issue) 발급,
//        없으면 CUSTOMER로 새로 가입 (비밀번호 없음, 아이디는 자동 생성)
// 연결: 로그인한 회원이 직접 시작한 경우만 (이메일 자동 매칭 없음). 관리자는 불가
// 중복 방지의 최종 근거는 DB 제약 (social_accounts의 UNIQUE 2개) — 사전 조회는 알맞은 응답을 주기 위한 것
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialAccountService {

    // 아이디 자동 생성: "{공급자}_" + 소문자·숫자 10자 (users.username 20자 이내, 가입 규칙 4~20자와도 맞음)
    private static final String USERNAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int USERNAME_RANDOM_LENGTH = 10;
    private static final int MAX_PREFIX_LENGTH = 9;
    // 동시 첫 로그인·아이디 충돌 시 재시도 횟수
    private static final int MAX_ATTEMPTS = 3;

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;
    private final SocialClientRegistrations registrations;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom random = new SecureRandom();

    // 소셜 로그인 — 연결된 회원으로 로그인, 없으면 CUSTOMER 가입 후 로그인
    // 트랜잭션을 여기서 직접 나누는 이유: 같은 소셜 계정으로 동시에 처음 로그인하면 한쪽이 UNIQUE 위반으로 롤백됨
    //   → 그 트랜잭션은 다시 쓸 수 없으므로 새 트랜잭션에서 다시 조회하면 먼저 가입한 회원으로 로그인됨 (중복 가입 없음)
    public IssuedTokens login(String provider, String providerUserId) {
        DataIntegrityViolationException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> loginOrSignUp(provider, providerUserId));
            } catch (DataIntegrityViolationException e) {
                // 다른 요청이 같은 소셜 계정으로 먼저 가입했거나, 자동 생성 아이디가 겹침 → 다시 시도
                log.info("소셜 첫 로그인 충돌 — 재시도 provider={}, attempt={}", provider, attempt);
                last = e;
            }
        }
        throw last;
    }

    private IssuedTokens loginOrSignUp(String provider, String providerUserId) {
        Optional<SocialAccount> linked = socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        User user;
        if (linked.isPresent()) {
            // users FK가 있어 연결된 회원은 지워지지 않음
            user = userRepository.findById(linked.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException("연결된 회원이 없습니다. userId=" + linked.get().getUserId()));
        } else {
            user = userRepository.saveAndFlush(User.socialCustomer(newUsername(provider)));
            socialAccountRepository.saveAndFlush(new SocialAccount(user.getId(), provider, providerUserId));
            log.info("소셜 첫 로그인 — CUSTOMER 가입 userId={}, provider={}", user.getId(), provider);
        }
        return authTokenService.issue(user);
    }

    // 연결 시작 (POST /api/users/me/social-accounts/{provider}/link) — 연결할 회원 id를 돌려주고 컨트롤러가 쿠키로 발급
    // 공급자 화면에 다녀오기 전에 막을 수 있는 경우를 먼저 거름 (콜백에서도 link가 다시 확인함)
    @Transactional(readOnly = true)
    public Long startLink(String username, String provider) {
        requireEnabled(provider);
        User user = findUser(username);
        requireLinkable(user);
        if (socialAccountRepository.findByUserIdAndProvider(user.getId(), provider).isPresent()) {
            throw providerAlreadyLinked();
        }
        return user.getId();
    }

    // 연결 (OAuth2 콜백) — 같은 회원에 이미 연결된 같은 계정이면 그대로 성공 (멱등)
    @Transactional
    public void link(Long userId, String provider, String providerUserId) {
        requireEnabled(provider);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        requireLinkable(user);

        Optional<SocialAccount> existing = socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existing.isPresent()) {
            if (existing.get().getUserId().equals(userId)) {
                return;
            }
            // 이미 그 소셜 계정으로 가입한(또는 연결한) 다른 회원이 있음 — 회원 합치기는 하지 않음
            throw alreadyLinked();
        }
        if (socialAccountRepository.findByUserIdAndProvider(userId, provider).isPresent()) {
            throw providerAlreadyLinked();
        }
        try {
            socialAccountRepository.saveAndFlush(new SocialAccount(userId, provider, providerUserId));
        } catch (DataIntegrityViolationException e) {
            // 위 조회와 저장 사이에 같은 계정이 다른 곳에서 연결됨
            throw alreadyLinked();
        }
        log.info("소셜 계정 연결 userId={}, provider={}", userId, provider);
    }

    @Transactional(readOnly = true)
    public SocialAccountsResponse getMine(String username) {
        User user = findUser(username);
        return new SocialAccountsResponse(user.hasPassword(),
                socialAccountRepository.findAllByUserIdOrderByIdAsc(user.getId()).stream()
                        .map(SocialAccountsResponse.Item::from)
                        .toList());
    }

    // 연결 해제 — 비밀번호 없는 회원의 마지막 소셜 계정은 409 (로그인할 방법이 없어짐)
    // 회원 행 잠금: 두 계정을 동시에 해제해 둘 다 "아직 하나 남음"으로 판단하는 일이 없도록
    @Transactional
    public void unlink(String username, String provider) {
        User user = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        SocialAccount account = socialAccountRepository.findByUserIdAndProvider(user.getId(), provider)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "연결되지 않은 소셜 계정입니다."));
        if (!user.hasPassword() && socialAccountRepository.countByUserId(user.getId()) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.SOCIAL_LAST_LOGIN_METHOD,
                    "비밀번호가 없는 계정은 마지막 소셜 계정을 해제할 수 없습니다.");
        }
        socialAccountRepository.delete(account);
        log.info("소셜 계정 연결 해제 userId={}, provider={}", user.getId(), provider);
    }

    private void requireEnabled(String provider) {
        if (registrations.provider(provider).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.SOCIAL_PROVIDER_NOT_SUPPORTED,
                    "지원하지 않는 소셜 로그인입니다.");
        }
    }

    // 관리자는 연결 불가 — 관리자 권한(정산 지급·환불)이 소셜 계정 보안에 의존하지 않도록
    private static void requireLinkable(User user) {
        if (user.getRole() == Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.SOCIAL_LINK_FORBIDDEN,
                    "관리자 계정은 소셜 계정을 연결할 수 없습니다.");
        }
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
    }

    private static ApiException alreadyLinked() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED,
                "이미 다른 회원에 연결된 소셜 계정입니다.");
    }

    private static ApiException providerAlreadyLinked() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED,
                "이미 같은 공급자의 소셜 계정이 연결되어 있습니다. 해제 후 다시 연결해주세요.");
    }

    // 사전 확인은 충돌 확률을 줄일 뿐, 최종 판단은 users.username UNIQUE (충돌 시 login이 재시도)
    String newUsername(String provider) {
        String prefix = provider.length() > MAX_PREFIX_LENGTH ? provider.substring(0, MAX_PREFIX_LENGTH) : provider;
        String candidate;
        do {
            StringBuilder sb = new StringBuilder(prefix).append('_');
            for (int i = 0; i < USERNAME_RANDOM_LENGTH; i++) {
                sb.append(USERNAME_CHARS.charAt(random.nextInt(USERNAME_CHARS.length())));
            }
            candidate = sb.toString();
        } while (userRepository.existsByUsername(candidate));
        return candidate;
    }
}

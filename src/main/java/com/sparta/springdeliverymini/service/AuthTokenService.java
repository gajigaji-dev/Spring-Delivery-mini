package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.RefreshTokenProperties;
import com.sparta.springdeliverymini.dto.LoginResponse;
import com.sparta.springdeliverymini.entity.RefreshToken;
import com.sparta.springdeliverymini.entity.RefreshTokenRevokeReason;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.RefreshTokenRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

// Access Token(JWT) + Refresh Token 발급·재발급(rotation)·폐기 — docs/architecture.md 인증 장
//
// 저장소는 DB(refresh_tokens)만 사용, Redis는 쓰지 않음
// - Refresh Token은 로그인 상태 그 자체라 유실되면 전원 로그아웃 → "Redis는 보조 수단" 원칙상 원본을 Redis에 두지 않음
// - Redis 장애와 무관하게 재발급·로그아웃이 동작 (DB 장애 시에는 다른 API와 같이 503)
//
// Refresh Token은 서명 없는 무작위 값(256비트)이고 DB에는 SHA-256 해시만 저장
// - JWT로 만들 필요가 없음: 유효성은 어차피 DB 행(폐기·만료)으로 판단해야 하므로
// - 해시 저장: DB·백업이 유출돼도 저장된 값으로 재발급을 받을 수 없음 (무작위 256비트라 솔트·느린 해시 불필요)
//
// 재사용 감지 (엄격 정책): 이미 재발급에 쓴(ROTATED) 토큰이 다시 오면 탈취로 보고 같은 계열(로그인 1번)을 모두 폐기
// - 정상 사용자와 탈취자 중 누가 먼저 썼든 다른 쪽이 재사용하는 순간 둘 다 재로그인 → 탈취 토큰의 수명이 끝남
// - 같은 토큰으로 동시에 재발급해도(탭 두 개 등) 행 잠금으로 한 요청만 새 토큰을 받고, 나머지는 재사용으로 처리되어 계열이 폐기됨
//   → 프론트엔드는 재발급 요청을 한 번에 하나만 보내야 함 (docs/api.md 인증 장)
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private static final int TOKEN_BYTES = 32;
    // Base64 URL-safe, 패딩 없음 → 32바이트는 43자
    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    // 로그인 — 새 계열의 Refresh Token + Access Token (UserService.login의 트랜잭션 안에서 호출)
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedTokens issue(User user) {
        String raw = newRawToken();
        refreshTokenRepository.save(RefreshToken.issue(user.getId(), hash(raw), LocalDateTime.now(clock),
                properties.ttl(), properties.absoluteTtl()));
        return new IssuedTokens(accessTokenOf(user), raw);
    }

    // 재발급 — 기존 Refresh Token을 폐기하고 같은 계열의 새 Refresh Token + 새 Access Token
    // noRollbackFor: 재사용을 감지하면 계열 폐기를 커밋한 뒤 401을 응답해야 함
    //   (롤백되면 탈취된 계열이 그대로 살아남음). 그 밖의 401 경로는 DB를 바꾸지 않고 던지므로 커밋돼도 영향 없음
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens refresh(String rawToken) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 행 잠금으로 조회 — 같은 토큰의 동시 재발급은 여기서 줄을 섬 (RefreshTokenRepository 주석)
        RefreshToken current = findForUpdate(rawToken);

        if (current.isRevoked()) {
            if (current.isRotated()) {
                // 이미 재발급에 쓴 토큰 → 탈취·재사용으로 보고 계열 전체 폐기 (재발급으로 생긴 최신 토큰 포함)
                int revoked = refreshTokenRepository.revokeFamily(
                        current.getFamilyId(), now, RefreshTokenRevokeReason.REUSE_DETECTED);
                log.warn("Refresh Token 재사용 감지 — 계열 전체 폐기 userId={}, familyId={}, revoked={}",
                        current.getUserId(), current.getFamilyId(), revoked);
                throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_TOKEN_REUSED,
                        "이미 사용된 Refresh Token입니다. 다시 로그인해주세요.");
            }
            // 로그아웃·재사용 감지로 폐기된 토큰 (계열이 이미 폐기됨)
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_TOKEN_REVOKED,
                    "폐기된 Refresh Token입니다. 다시 로그인해주세요.");
        }
        // 개별 수명(ttl) 또는 계열 절대 만료(absolute-ttl, V16) 중 이른 쪽이 지남 — expiresAt에 이미 반영됨
        if (current.isExpired(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_TOKEN_EXPIRED,
                    "Refresh Token이 만료되었습니다. 다시 로그인해주세요.");
        }

        // Access Token의 역할은 DB의 현재 값 (로그인 뒤 역할이 바뀌었으면 재발급 때 반영)
        // users FK가 있어 토큰이 있는 회원은 지워지지 않지만, 혹시 없으면 토큰을 쓰지 못하게 계열을 폐기
        User user = userRepository.findById(current.getUserId()).orElse(null);
        if (user == null) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now, RefreshTokenRevokeReason.LOGOUT);
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_TOKEN_INVALID, "존재하지 않는 회원입니다.");
        }

        String raw = newRawToken();
        refreshTokenRepository.save(current.rotate(hash(raw), now, properties.ttl()));
        return new IssuedTokens(accessTokenOf(user), raw);
    }

    // 로그아웃 — 이 토큰이 속한 계열(로그인 1번)의 유효한 토큰을 모두 폐기, 다른 기기의 로그인은 유지
    // 멱등: 토큰이 없거나 모르는 값·이미 폐기된 토큰이어도 성공 (쿠키 삭제는 컨트롤러가 항상 함)
    // 이미 쓴(ROTATED) 토큰으로 로그아웃해도 계열을 폐기 — 그 토큰을 가진 쪽이 누구든 로그인을 끊는 방향이 안전
    // Access Token은 서명만 검증하는 방식이라 만료(최대 jwt.expiration-ms)까지는 유효 → 클라이언트가 메모리에서 지움
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || !TOKEN_FORMAT.matcher(rawToken).matches()) {
            return;
        }
        refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken)).ifPresent(token ->
                refreshTokenRepository.revokeFamily(token.getFamilyId(), LocalDateTime.now(clock),
                        RefreshTokenRevokeReason.LOGOUT));
    }

    private RefreshToken findForUpdate(String rawToken) {
        // 형식이 다르면 DB 조회 없이 거부 (모르는 값과 같은 응답 — 형식 정보를 따로 알려주지 않음)
        if (rawToken == null || !TOKEN_FORMAT.matcher(rawToken).matches()) {
            throw invalidToken();
        }
        return refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken)).orElseThrow(this::invalidToken);
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_TOKEN_INVALID,
                "유효하지 않은 Refresh Token입니다. 다시 로그인해주세요.");
    }

    private LoginResponse accessTokenOf(User user) {
        return LoginResponse.bearer(jwtProvider.createToken(user.getUsername(), user.getRole()),
                jwtProvider.getExpirationSeconds());
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 모든 JVM이 SHA-256을 제공해야 함 (Java SE 명세)
            throw new IllegalStateException(e);
        }
    }
}

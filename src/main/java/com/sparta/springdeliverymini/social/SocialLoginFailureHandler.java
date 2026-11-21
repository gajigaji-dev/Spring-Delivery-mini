package com.sparta.springdeliverymini.social;

import com.sparta.springdeliverymini.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// OAuth2 콜백 실패 → 프론트 ?error=code (V22). 서버 상태는 바뀐 것이 없음 (회원·토큰 생성 전 단계)
// - access_denied: 사용자가 동의 화면에서 취소 → OAUTH2_CANCELLED (오류가 아니라 안내만)
// - authorization_request_not_found: 시작 기록 쿠키 없음(10분 초과·다른 탭이 덮어씀·쿠키 차단) → OAUTH2_REQUEST_EXPIRED
// - 그 밖(state 불일치·토큰 교환 실패·공급자 장애·ID 토큰 검증 실패) → OAUTH2_FAILED, 원인은 서버 로그에만
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialLoginFailureHandler implements AuthenticationFailureHandler {

    private final SocialLoginRedirects redirects;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String oauthError = exception instanceof OAuth2AuthenticationException e ? e.getError().getErrorCode() : null;
        ErrorCode code = switch (oauthError == null ? "" : oauthError) {
            case "access_denied" -> ErrorCode.OAUTH2_CANCELLED;
            case "authorization_request_not_found" -> ErrorCode.OAUTH2_REQUEST_EXPIRED;
            default -> ErrorCode.OAUTH2_FAILED;
        };
        if (code == ErrorCode.OAUTH2_CANCELLED) {
            log.info("소셜 로그인 취소 uri={}", request.getRequestURI());
        } else {
            // 공급자가 준 error_description은 사용자 입력이 섞일 수 있어 그대로 응답에 넣지 않고 로그에만
            log.warn("소셜 로그인 실패 uri={}, oauthError={}, message={}",
                    request.getRequestURI(), oauthError, exception.getMessage());
        }
        redirects.error(response, code);
    }
}

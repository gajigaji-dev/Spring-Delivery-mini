package com.sparta.springdeliverymini.jwt;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 요청마다 Authorization: Bearer {토큰} 헤더를 검증한다.
 * 토큰이 유효하면 SecurityContext에 인증 정보를 넣고, 없거나 유효하지 않으면 그대로 통과시킨다.
 * (인증이 필요한 경로라면 이후 SecurityConfig의 entry point가 401을 응답)
 */
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    // 토큰 검증 실패 사유(ErrorCode) — 인증이 필요한 경로라면 SecurityConfig의 entry point가 읽어 401 본문의 code로 응답
    // 인증이 필요 없는 경로(permitAll)는 실패해도 익명으로 그대로 처리됨 (기존 동작 유지)
    public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".AUTH_ERROR";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtProvider.parseClaims(token);
                String role = claims.get("role", String.class);

                Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                        claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException e) {
                // 서명은 맞지만 기간이 지남 → 클라이언트는 Refresh Token으로 재발급하면 됨
                // (jjwt는 서명을 먼저 검증하므로 위조된 만료 토큰은 여기로 오지 않고 아래 TOKEN_INVALID)
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                // 서명 불일치(위조)·형식 오류 → 재발급으로 해결되지 않으므로 만료와 구분
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_INVALID);
            }
        }

        filterChain.doFilter(request, response);
    }
}

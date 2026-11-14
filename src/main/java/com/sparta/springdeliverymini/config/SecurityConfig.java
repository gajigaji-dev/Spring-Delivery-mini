package com.sparta.springdeliverymini.config;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.exception.ApiErrorResponse;
import com.sparta.springdeliverymini.jwt.JwtAuthenticationFilter;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper; // 401/403 응답을 GlobalExceptionHandler와 같은 형식(ApiErrorResponse)으로 직렬화

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)      // REST API용
                .formLogin(AbstractHttpConfigurer::disable) // 기본 로그인 페이지 대신 /api/users/login 사용
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/users/signup", "/api/users/login", "/error").permitAll()

                        // 헬스체크·Prometheus 수집은 토큰 없이 허용 (모니터링 도구가 JWT를 발급받을 수 없으므로)
                        // 실제 노출 여부는 management.endpoints.web.exposure가 결정: 기본(application.yml)은 health만 노출,
                        // prometheus는 local·dev 프로필에서만 열고 dev는 호스트에 공개하지 않는 관리 포트(8081)로 분리
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // 그 밖의 Actuator 엔드포인트(metrics, env 등)는 노출하더라도 ADMIN만 (설정·환경 정보 보호)
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // API 문서(Swagger UI·OpenAPI 명세) — springdoc.*.enabled가 true인 프로필(local·dev)에서만 존재
                        // 기본값은 비활성이라 운영 프로필에서는 경로 자체가 없음(404)
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()

                        // 매장별 주문 조회는 OWNER만 가능 (CUSTOMER → 403, 본인 매장 여부는 Service에서 확인)
                        .requestMatchers(HttpMethod.GET, "/api/stores/*/orders").hasRole("OWNER")

                        // 매장별 정산 조회는 OWNER만 가능 (CUSTOMER → 403, 본인 매장 여부는 Service에서 확인)
                        .requestMatchers(HttpMethod.GET, "/api/stores/*/settlements").hasRole("OWNER")

                        // 매장별 일 정산 기록 조회(목록·상세)는 OWNER만 가능 (본인 매장 여부는 Service에서 확인)
                        .requestMatchers(HttpMethod.GET,
                                "/api/stores/*/daily-settlements", "/api/stores/*/daily-settlements/*").hasRole("OWNER")

                        // 매장 조회는 로그인하지 않아도 가능
                        .requestMatchers(HttpMethod.GET, "/api/stores", "/api/stores/*").permitAll()

                        // 매장 생성은 OWNER만 가능
                        .requestMatchers(HttpMethod.POST, "/api/stores").hasRole("OWNER")

                        // 메뉴 조회는 로그인하지 않아도 가능
                        .requestMatchers(HttpMethod.GET, "/api/menus/**").permitAll()

                        // 메뉴 등록은 OWNER만 가능
                        .requestMatchers(HttpMethod.POST, "/api/menus/**").hasRole("OWNER")

                        // 주문 생성은 CUSTOMER만 가능
                        .requestMatchers(HttpMethod.POST, "/api/orders/**").hasRole("CUSTOMER")

                        // 주문 취소는 CUSTOMER만 가능 (OWNER → 403)
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/cancel").hasRole("CUSTOMER")

                        // 주문 상태 변경(수락/배달완료)은 OWNER만 가능 (CUSTOMER → 403)
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("OWNER")

                        // 결제는 CUSTOMER만 가능 (OWNER → 403)
                        .requestMatchers(HttpMethod.POST, "/api/payments/**").hasRole("CUSTOMER")

                        // 관리자 API(정산 확정·지급, 환불, 매장 수수료율 변경 등)는 ADMIN만 가능 (CUSTOMER·OWNER → 403)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        // 토큰이 없거나 유효하지 않은 상태로 인증이 필요한 요청을 보내면 401
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(request, response, HttpStatus.UNAUTHORIZED, "인증이 필요합니다."))
                        // 인증은 됐지만 권한이 없으면(예: CUSTOMER가 메뉴 등록) 403
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(request, response, HttpStatus.FORBIDDEN, "권한이 없습니다."))
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // Security 필터에서 바로 응답하는 401/403은 GlobalExceptionHandler를 거치지 않으므로 같은 형식으로 직접 작성
    // TraceIdFilter가 먼저 실행되므로 여기서도 MDC의 traceId가 응답에 들어감
    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String message) throws IOException {
        log.warn("요청 실패 status={} message={}", status.value(), message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiErrorResponse.of(status, message, request.getRequestURI())));
    }
}

package com.sparta.springdeliverymini.config;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.exception.ApiErrorResponse;
import com.sparta.springdeliverymini.jwt.JwtAuthenticationFilter;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
// CorsProperties를 여기서도 등록: @WebMvcTest처럼 SecurityConfig만 가져오는 테스트에서도 CORS 설정이 같이 적용되도록
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper; // 401/403 응답을 GlobalExceptionHandler와 같은 형식(ApiErrorResponse)으로 직렬화
    private final CorsProperties corsProperties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS는 인증·권한 검사보다 앞(CorsFilter)에서 처리됨
                // → 허용 출처의 Preflight(OPTIONS + Access-Control-Request-Method)는 토큰 없이 CorsFilter가 바로 200으로 응답,
                //   허용하지 않은 출처는 Preflight·실제 요청 모두 403. OPTIONS 전체를 permitAll로 열지 않는 이유는
                //   CORS가 아닌 OPTIONS 요청까지 인증 없이 컨트롤러 쪽으로 넘기지 않기 위해
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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

                        // 내 정산 내역(본인 매장 전체)은 OWNER만 가능 (본인 매장 정산만 Service에서 조회)
                        .requestMatchers(HttpMethod.GET, "/api/settlements").hasRole("OWNER")

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

                        // 결제 단건 조회도 CUSTOMER만 가능 (OWNER → 403), 본인 결제인지는 Service에서 확인
                        .requestMatchers(HttpMethod.GET, "/api/payments/*").hasRole("CUSTOMER")

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

    // 허용 출처는 설정값(cors.allowed-origins, 환경변수 CORS_ALLOWED_ORIGINS)으로만 지정 — 와일드카드 금지는 CorsProperties에서 검사
    // allowCredentials는 끔: 인증은 쿠키가 아닌 Authorization 헤더(JWT)로만 하므로 브라우저 자격 증명(쿠키 등)을 보낼 필요가 없음
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 요청 헤더: JWT, JSON 본문, 결제 멱등성 키(#41), 클라이언트가 정하는 추적 id(#38). 목록에 없는 헤더를 보내면 Preflight가 거부됨
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Trace-Id"));
        // 응답 헤더: 브라우저 JS는 기본 헤더 외에는 노출 목록에 있는 것만 읽을 수 있음
        //   목록 페이징(X-Has-Next·X-Page·X-Size, keyset X-Next-After-Id), 오류 추적(X-Trace-Id), 멱등 재전송 여부(Idempotent-Replayed)
        config.setExposedHeaders(List.of(
                "X-Has-Next", "X-Next-After-Id", "X-Page", "X-Size", "X-Trace-Id", "Idempotent-Replayed"));
        config.setAllowCredentials(false);
        // Preflight 결과 캐시 시간 — 요청마다 OPTIONS가 한 번 더 가지 않도록
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
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

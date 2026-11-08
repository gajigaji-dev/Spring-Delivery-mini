package com.sparta.springdeliverymini.config;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.jwt.JwtAuthenticationFilter;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SecurityConfig {

    private final JwtProvider jwtProvider;

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

                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        // 토큰이 없거나 유효하지 않은 상태로 인증이 필요한 요청을 보내면 401
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "인증이 필요합니다."))
                        // 인증은 됐지만 권한이 없으면(예: CUSTOMER가 메뉴 등록) 403
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpStatus.FORBIDDEN, "권한이 없습니다."))
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

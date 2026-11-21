package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.social.CookieAuthorizationRequestRepository;
import com.sparta.springdeliverymini.social.NoOpAuthorizedClientRepository;
import com.sparta.springdeliverymini.social.SocialAuthorizationRequestResolver;
import com.sparta.springdeliverymini.social.SocialClientRegistrations;
import com.sparta.springdeliverymini.social.SocialLinkCookies;
import com.sparta.springdeliverymini.social.SocialLoginFailureHandler;
import com.sparta.springdeliverymini.social.SocialLoginSuccessHandler;
import com.sparta.springdeliverymini.social.SocialProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 소셜 로그인 구성 (V22) — docs/architecture.md 소셜 로그인 장
// SecurityConfig는 이 빈이 있을 때만 oauth2Login을 켬 (SecurityConfig만 가져오는 @WebMvcTest에는 이 구성이 없음)
@Slf4j
@Configuration
@EnableConfigurationProperties(SocialLoginProperties.class)
public class SocialLoginConfig {

    // 공급자 콜백 주소 — 공급자 콘솔에 등록할 값: {API 주소}/login/oauth2/code/{kakao|google}
    // {baseUrl}은 요청의 Host 기준 — 프록시 뒤에 두면 forward 헤더 설정이 필요 (docs/operations.md)
    static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    @Bean
    public SocialClientRegistrations socialClientRegistrations(SocialLoginProperties properties,
                                                               List<SocialProvider> providers) {
        Set<String> known = new HashSet<>();
        Map<SocialProvider, ClientRegistration> enabled = new LinkedHashMap<>();
        for (SocialProvider provider : providers) {
            known.add(provider.registrationId());
            SocialLoginProperties.Client client = properties.providers().get(provider.registrationId());
            if (client != null && client.configured()) {
                enabled.put(provider, provider.clientRegistration(client.clientId(), client.clientSecret(), REDIRECT_URI));
            }
        }
        // 구현이 없는 공급자 키(오타 등)는 조용히 무시하지 않고 기동 실패
        for (String id : properties.providers().keySet()) {
            if (!known.contains(id)) {
                throw new IllegalStateException("social.providers." + id + "에 해당하는 공급자 구현이 없습니다. 지원: " + known);
            }
        }
        SocialClientRegistrations registrations = new SocialClientRegistrations(enabled);
        log.info("소셜 로그인 공급자 enabled={}", registrations.enabledIds());
        return registrations;
    }

    @Bean
    public SocialLoginSecurity socialLoginSecurity(SocialClientRegistrations registrations,
                                                   SocialLinkCookies linkCookies,
                                                   CookieAuthorizationRequestRepository authorizationRequestRepository,
                                                   SocialLoginSuccessHandler successHandler,
                                                   SocialLoginFailureHandler failureHandler) {
        return http -> http.oauth2Login(oauth2 -> oauth2
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(new NoOpAuthorizedClientRepository())
                // 직접 지정해 기본 로그인 HTML 페이지(/login)가 생기지 않게 함 — 로그인 화면은 프론트
                .loginPage("/login")
                .authorizationEndpoint(endpoint -> endpoint
                        .baseUri(SocialAuthorizationRequestResolver.BASE_URI)
                        .authorizationRequestRepository(authorizationRequestRepository)
                        .authorizationRequestResolver(new SocialAuthorizationRequestResolver(registrations, linkCookies)))
                .successHandler(successHandler)
                .failureHandler(failureHandler));
    }

    // SecurityConfig가 적용하는 소셜 로그인 설정 묶음
    @FunctionalInterface
    public interface SocialLoginSecurity {
        void configure(HttpSecurity http) throws Exception;
    }
}

package com.sparta.springdeliverymini.service;

import com.jayway.jsonpath.JsonPath;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.SocialAccount;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.repository.RefreshTokenRepository;
import com.sparta.springdeliverymini.repository.SocialAccountRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 소셜 로그인(V22)을 실제 PostgreSQL + Spring Security OAuth2 필터까지 통과시켜 검증
// 공급자와 통신하는 두 곳(코드 → 토큰 교환, 사용자 정보 조회)만 가짜 빈으로 바꿈 → 시작·state 쿠키·콜백·회원 처리·쿠키 발급은 실제 코드
// 카카오는 가짜 키로 켬, 구글도 켜서 공급자 목록·시작 리다이렉트만 확인 (구글 ID 토큰 검증은 네트워크가 필요해 여기서 다루지 않음)
@SpringBootTest(properties = {
        "social.providers.kakao.client-id=test-kakao-id",
        "social.providers.kakao.client-secret=test-kakao-secret",
        "social.providers.google.client-id=test-google-id",
        "social.providers.google.client-secret=test-google-secret"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class,
        SocialLoginIntegrationTest.FakeKakaoConfig.class})
class SocialLoginIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);
    private static final String CALLBACK = "http://localhost:5173/oauth/callback";
    private static final String STATE_COOKIE = "oauth2_auth_request";
    private static final String LINK_COOKIE = "social_link";

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private SocialAccountRepository socialAccountRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private SocialAccountService socialAccountService;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        FakeKakaoConfig.kakaoId.set("1001");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: social_accounts·refresh_tokens → users
        socialAccountRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    // 로그인 시작 → 카카오 동의 화면으로 가는 리다이렉트와 시작 기록 쿠키
    private MvcResult start(String path, Cookie... cookies) throws Exception {
        var request = get(path);
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mockMvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
    }

    private static String stateOf(MvcResult started) {
        String location = started.getResponse().getRedirectedUrl();
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        return UriUtils.decode(state, StandardCharsets.UTF_8);
    }

    private static Cookie stateCookieOf(MvcResult started) {
        Cookie cookie = started.getResponse().getCookie(STATE_COOKIE);
        assertThat(cookie).isNotNull();
        return new Cookie(STATE_COOKIE, cookie.getValue());
    }

    // 카카오가 동의 후 돌려보내는 콜백
    private MvcResult callback(MvcResult started) throws Exception {
        return mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "auth-code")
                        .param("state", stateOf(started))
                        .cookie(stateCookieOf(started)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private MvcResult socialLogin() throws Exception {
        return callback(start("/oauth2/authorization/kakao"));
    }

    private static String redirectOf(MvcResult result) {
        return result.getResponse().getRedirectedUrl();
    }

    private User passwordUser(String username, Role role) {
        return userRepository.save(new User(username, passwordEncoder.encode("password123"), role));
    }

    private String accessTokenOf(String username) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    // 연결 시작 API → 연결 요청 쿠키
    private Cookie linkCookie(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/me/social-accounts/kakao/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent()).andReturn();
        Cookie cookie = result.getResponse().getCookie(LINK_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/oauth2/authorization");
        return new Cookie(LINK_COOKIE, cookie.getValue());
    }

    // ===== 시작 =====

    @Test
    void 로그인을_시작하면_카카오_동의_화면으로_보내고_시작_기록은_세션이_아니라_서명된_쿠키에_둔다() throws Exception {
        MvcResult started = start("/oauth2/authorization/kakao");

        assertThat(redirectOf(started)).startsWith("https://kauth.kakao.com/oauth/authorize?")
                .contains("client_id=test-kakao-id")
                .contains("redirect_uri=http://localhost/login/oauth2/code/kakao");
        Cookie cookie = started.getResponse().getCookie(STATE_COOKIE);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/login/oauth2/code");
        assertThat(started.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(h -> h.startsWith(STATE_COOKIE) && h.contains("SameSite=Lax"));
        // STATELESS 유지 — 세션(JSESSIONID)을 만들지 않음
        assertThat(started.getRequest().getSession(false)).isNull();
    }

    @Test
    void 구글은_OpenID_Connect로_시작하고_nonce를_보낸다() throws Exception {
        MvcResult started = start("/oauth2/authorization/google");

        assertThat(redirectOf(started)).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("scope=openid").contains("nonce=");
    }

    @Test
    void 키가_설정된_공급자만_목록에_나오고_없는_공급자로_시작하면_404() throws Exception {
        mockMvc.perform(get("/api/users/social/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("google"))
                .andExpect(jsonPath("$[1]").value("kakao"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/oauth2/authorization/naver")).andExpect(status().isNotFound());
    }

    // ===== 로그인·가입 =====

    @Test
    void 처음_소셜_로그인하면_CUSTOMER로_가입되고_Refresh_쿠키만_받아_프론트로_돌아간다() throws Exception {
        MvcResult result = socialLogin();

        assertThat(redirectOf(result)).isEqualTo(CALLBACK + "?result=login");
        // 토큰은 URL에 없음
        assertThat(redirectOf(result)).doesNotContain("token");
        Cookie refresh = result.getResponse().getCookie("refresh_token");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/users");

        List<User> users = userRepository.findAll();
        assertThat(users).hasSize(1);
        assertThat(users.get(0).getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(users.get(0).hasPassword()).isFalse();
        assertThat(users.get(0).getUsername()).matches("kakao_[a-z0-9]{10}");
        List<SocialAccount> accounts = socialAccountRepository.findAll();
        assertThat(accounts).singleElement().satisfies(a -> {
            assertThat(a.getProvider()).isEqualTo("kakao");
            assertThat(a.getProviderUserId()).isEqualTo("1001");
            assertThat(a.getUserId()).isEqualTo(users.get(0).getId());
        });
    }

    @Test
    void 소셜_로그인의_Refresh_쿠키로_기존_재발급_API에서_Access를_받는다() throws Exception {
        Cookie refresh = socialLogin().getResponse().getCookie("refresh_token");

        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie("refresh_token", refresh.getValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void 같은_소셜_계정으로_다시_로그인하면_같은_회원이고_중복_가입되지_않는다() throws Exception {
        socialLogin();
        socialLogin();

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(socialAccountRepository.count()).isEqualTo(1);
        // 로그인마다 새 Refresh 계열
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_소셜_계정으로_동시에_처음_로그인해도_회원은_하나만_생긴다() throws Exception {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<IssuedTokens>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<IssuedTokens> task = () -> {
                    ready.await();
                    return socialAccountService.login("kakao", "2002");
                };
                futures.add(executor.submit(task));
            }
            ready.countDown();
            for (Future<IssuedTokens> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).refreshToken()).isNotBlank();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(socialAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void 소셜_전용_회원은_아이디_비밀번호_로그인이_401() throws Exception {
        socialLogin();
        String username = userRepository.findAll().get(0).getUsername();

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===== 실패·취소 =====

    @Test
    void 동의_화면에서_취소하면_회원을_만들지_않고_OAUTH2_CANCELLED로_돌아간다() throws Exception {
        MvcResult started = start("/oauth2/authorization/kakao");

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("error", "access_denied")
                        .param("error_description", "User denied access")
                        .param("state", stateOf(started))
                        .cookie(stateCookieOf(started)))
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(redirectOf(result)).isEqualTo(CALLBACK + "?error=OAUTH2_CANCELLED");
        assertThat(result.getResponse().getCookie("refresh_token")).isNull();
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 시작_기록_쿠키가_없으면_OAUTH2_REQUEST_EXPIRED() throws Exception {
        MvcResult started = start("/oauth2/authorization/kakao");

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "auth-code").param("state", stateOf(started)))
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(redirectOf(result)).isEqualTo(CALLBACK + "?error=OAUTH2_REQUEST_EXPIRED");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 시작_기록_쿠키가_변조되거나_10분이_지나면_OAUTH2_REQUEST_EXPIRED() throws Exception {
        MvcResult started = start("/oauth2/authorization/kakao");
        String value = stateCookieOf(started).getValue();
        String tampered = value.substring(0, value.indexOf('.')) + ".AAAA";

        MvcResult forged = mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "auth-code").param("state", stateOf(started))
                        .cookie(new Cookie(STATE_COOKIE, tampered)))
                .andReturn();
        assertThat(redirectOf(forged)).isEqualTo(CALLBACK + "?error=OAUTH2_REQUEST_EXPIRED");

        clock.set(NOW.plusMinutes(11));
        assertThat(redirectOf(callback(started))).isEqualTo(CALLBACK + "?error=OAUTH2_REQUEST_EXPIRED");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void state가_다르면_로그인_CSRF로_보고_실패한다() throws Exception {
        MvcResult started = start("/oauth2/authorization/kakao");

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "attacker-code").param("state", "other-state")
                        .cookie(stateCookieOf(started)))
                .andReturn();

        assertThat(redirectOf(result)).startsWith(CALLBACK + "?error=");
        assertThat(userRepository.count()).isZero();
    }

    // ===== 기존 회원 연결 =====

    @Test
    void 로그인한_회원이_연결을_시작하면_카카오_계정이_그_회원에_연결되고_이후_소셜_로그인은_그_회원이다() throws Exception {
        User member = passwordUser("member1", Role.CUSTOMER);
        Cookie link = linkCookie(accessTokenOf("member1"));

        MvcResult started = start("/oauth2/authorization/kakao?mode=link", link);
        // 연결 요청 쿠키는 한 번만 — 시작하면서 지움
        assertThat(started.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(h -> h.startsWith(LINK_COOKIE + "=") && h.contains("Max-Age=0"));
        MvcResult linked = callback(started);

        assertThat(redirectOf(linked)).isEqualTo(CALLBACK + "?result=linked&provider=kakao");
        // 연결은 로그인이 아님 — 새 Refresh 쿠키를 주지 않음
        assertThat(linked.getResponse().getCookie("refresh_token")).isNull();
        assertThat(socialAccountRepository.findAll()).singleElement()
                .satisfies(a -> assertThat(a.getUserId()).isEqualTo(member.getId()));

        socialLogin();
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(refreshTokenRepository.findAll()).allMatch(t -> t.getUserId().equals(member.getId()));

        mockMvc.perform(get("/api/users/me/social-accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf("member1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.accounts[0].provider").value("kakao"))
                .andExpect(jsonPath("$.accounts[0].providerUserId").doesNotExist());
    }

    @Test
    void 연결_요청_쿠키_없이_연결_모드로_오면_새_회원을_만들지_않고_SOCIAL_LINK_EXPIRED() throws Exception {
        MvcResult result = callback(start("/oauth2/authorization/kakao?mode=link"));

        assertThat(redirectOf(result)).isEqualTo(CALLBACK + "?error=SOCIAL_LINK_EXPIRED");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void 연결_요청_쿠키가_5분이_지나면_SOCIAL_LINK_EXPIRED() throws Exception {
        passwordUser("member1", Role.CUSTOMER);
        Cookie link = linkCookie(accessTokenOf("member1"));
        clock.set(NOW.plusMinutes(6));

        MvcResult result = callback(start("/oauth2/authorization/kakao?mode=link", link));

        assertThat(redirectOf(result)).isEqualTo(CALLBACK + "?error=SOCIAL_LINK_EXPIRED");
        assertThat(socialAccountRepository.count()).isZero();
    }

    @Test
    void 이미_다른_회원이_쓰는_소셜_계정은_연결할_수_없다() throws Exception {
        socialLogin(); // 카카오 1001로 소셜 회원 가입
        passwordUser("member1", Role.CUSTOMER);
        Cookie link = linkCookie(accessTokenOf("member1"));

        MvcResult result = callback(start("/oauth2/authorization/kakao?mode=link", link));

        assertThat(redirectOf(result)).isEqualTo(CALLBACK + "?error=SOCIAL_ACCOUNT_ALREADY_LINKED");
        assertThat(socialAccountRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_공급자를_이미_연결했으면_연결_시작이_409() throws Exception {
        passwordUser("member1", Role.CUSTOMER);
        String access = accessTokenOf("member1");
        callback(start("/oauth2/authorization/kakao?mode=link", linkCookie(access)));

        mockMvc.perform(post("/api/users/me/social-accounts/kakao/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_ALREADY_LINKED"));
    }

    @Test
    void 관리자는_소셜_계정을_연결할_수_없고_꺼진_공급자는_404() throws Exception {
        passwordUser("admin1", Role.ADMIN);
        passwordUser("member1", Role.CUSTOMER);

        mockMvc.perform(post("/api/users/me/social-accounts/kakao/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf("admin1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOCIAL_LINK_FORBIDDEN"));
        mockMvc.perform(post("/api/users/me/social-accounts/naver/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf("member1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_NOT_SUPPORTED"));
    }

    @Test
    void 연결_시작은_로그인해야_한다() throws Exception {
        mockMvc.perform(post("/api/users/me/social-accounts/kakao/link")).andExpect(status().isUnauthorized());
    }

    // ===== 연결 해제 =====

    @Test
    void 비밀번호가_있는_회원은_소셜_계정을_해제할_수_있다() throws Exception {
        passwordUser("member1", Role.CUSTOMER);
        String access = accessTokenOf("member1");
        callback(start("/oauth2/authorization/kakao?mode=link", linkCookie(access)));

        mockMvc.perform(delete("/api/users/me/social-accounts/kakao")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isNoContent());

        assertThat(socialAccountRepository.count()).isZero();
        mockMvc.perform(delete("/api/users/me/social-accounts/kakao")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isNotFound());
    }

    @Test
    void 비밀번호_없는_회원의_마지막_소셜_계정은_해제할_수_없다() throws Exception {
        Cookie refresh = socialLogin().getResponse().getCookie("refresh_token");
        MvcResult refreshed = mockMvc.perform(post("/api/users/refresh")
                        .cookie(new Cookie("refresh_token", refresh.getValue())))
                .andExpect(status().isOk()).andReturn();
        String access = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(delete("/api/users/me/social-accounts/kakao")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOCIAL_LAST_LOGIN_METHOD"));
        assertThat(socialAccountRepository.count()).isEqualTo(1);
    }

    // 공급자와 통신하는 두 곳만 가짜 — 코드 → 토큰 교환, 사용자 정보 조회 (카카오 /v2/user/me 응답 모양)
    @TestConfiguration
    static class FakeKakaoConfig {

        static final AtomicReference<String> kakaoId = new AtomicReference<>("1001");

        @Bean
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> fakeKakaoTokenClient() {
            return request -> OAuth2AccessTokenResponse.withToken("kakao-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(3600)
                    .build();
        }

        @Bean
        OAuth2UserService<OAuth2UserRequest, OAuth2User> fakeKakaoUserService() {
            return request -> new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                    Map.of("id", Long.parseLong(kakaoId.get())), "id");
        }
    }
}

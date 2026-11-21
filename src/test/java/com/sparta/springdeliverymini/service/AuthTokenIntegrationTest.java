package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.RefreshToken;
import com.sparta.springdeliverymini.entity.RefreshTokenRevokeReason;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.maintenance.RetentionCleaner;
import com.sparta.springdeliverymini.repository.RefreshTokenRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Refresh Token(V15)을 실제 PostgreSQL + 보안 필터·CORS까지 통과시켜 검증
// - 로그인: Access는 본문, Refresh는 HttpOnly 쿠키(본문에 없음), DB에는 해시만
// - 재발급(rotation)·재사용 감지(계열 전체 폐기)·만료·로그아웃(멱등)·동시 재발급 1건만 성공
// - 다른 출처의 위조 요청(CSRF) 차단, 401 code로 Access 만료·위조 구분
@SpringBootTest(properties = "cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class AuthTokenIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);
    private static final String COOKIE = "refresh_token";

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RetentionCleaner retentionCleaner;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Value("${jwt.secret}") private String jwtSecret;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        userRepository.save(new User("customer1", passwordEncoder.encode("password123"), Role.CUSTOMER));
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: refresh_tokens → users
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, refreshToken))).andReturn();
    }

    private List<RefreshToken> tokens() {
        return refreshTokenRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
    }

    private long activeTokens() {
        return refreshTokenRepository.findAll().stream().filter(t -> !t.isRevoked()).count();
    }

    // ===== 로그인 =====

    @Test
    void 로그인하면_Access는_본문으로_Refresh는_HttpOnly_쿠키로_받고_DB에는_해시만_저장된다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                // Refresh Token은 본문에 없음 (JS가 읽을 수 없게 쿠키로만)
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/users")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=1209600")))
                .andReturn();

        String raw = refreshCookieOf(result);
        assertThat(raw).hasSize(43);
        List<RefreshToken> saved = tokens();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(saved.get(0).getExpiresAt()).isEqualTo(NOW.plusDays(14));
    }

    @Test
    void 로그인할_때마다_계열이_따로_생긴다() throws Exception {
        login();
        login();

        List<RefreshToken> saved = tokens();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getFamilyId()).isNotEqualTo(saved.get(1).getFamilyId());
    }

    // ===== 재발급 =====

    @Test
    void 재발급하면_새_Access와_새_Refresh를_받고_이전_Refresh는_ROTATED로_폐기된다() throws Exception {
        String first = refreshCookieOf(login());
        clock.set(NOW.plusDays(1));

        MvcResult result = mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andReturn();

        String second = refreshCookieOf(result);
        assertThat(second).isNotEqualTo(first);
        List<RefreshToken> saved = tokens();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(saved.get(0).getRevokedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(saved.get(1).isRevoked()).isFalse();
        assertThat(saved.get(1).getFamilyId()).isEqualTo(saved.get(0).getFamilyId());
        // 수명은 재발급 시점부터 다시
        assertThat(saved.get(1).getExpiresAt()).isEqualTo(NOW.plusDays(15));

        // 새 Refresh Token으로 다시 재발급 가능
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 재발급한_Access_Token에는_DB의_현재_역할이_담긴다() throws Exception {
        String raw = refreshCookieOf(login());
        // 로그인 뒤 운영자가 DB에서 역할을 바꿈
        jdbc.update("UPDATE users SET role = 'OWNER' WHERE username = 'customer1'");

        String accessToken = JsonPath.read(refresh(raw).getResponse().getContentAsString(), "$.accessToken");

        assertThat(jwtProvider.parseClaims(accessToken).get("role", String.class)).isEqualTo("OWNER");
    }

    @Test
    void 이미_쓴_Refresh_Token이_다시_오면_재사용으로_보고_계열_전체를_폐기한다() throws Exception {
        String first = refreshCookieOf(login());
        String second = refreshCookieOf(refresh(first));

        // 탈취자(또는 정상 사용자)가 이미 쓴 first를 다시 사용
        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, first)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"))
                // 더 쓸 수 없는 쿠키는 브라우저에서도 삭제
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // 재발급으로 받은 최신 토큰까지 폐기됨 → 정상 사용자도 재로그인
        assertThat(activeTokens()).isZero();
        assertThat(tokens().get(1).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.REUSE_DETECTED);
        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, second)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    @Test
    void 재사용_감지는_다른_로그인_계열에_영향을_주지_않는다() throws Exception {
        String deviceA = refreshCookieOf(login());
        String deviceB = refreshCookieOf(login());
        refresh(deviceA);

        refresh(deviceA); // 재사용 → A 계열만 폐기

        assertThat(refresh(deviceB).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 만료된_Refresh_Token은_거부한다() throws Exception {
        String raw = refreshCookieOf(login());
        clock.set(NOW.plusDays(14)); // 만료 시각과 같으면 만료

        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        // 만료는 폐기와 다름 — 행은 그대로
        assertThat(tokens().get(0).isRevoked()).isFalse();
    }

    @Test
    void 쿠키가_없거나_모르는_값이면_REFRESH_TOKEN_INVALID() throws Exception {
        mockMvc.perform(post("/api/users/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        String unknown = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, unknown)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, "not-a-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void 같은_Refresh_Token으로_동시에_재발급하면_한_요청만_성공하고_나머지는_재사용으로_처리된다() throws Exception {
        String raw = refreshCookieOf(login());
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<String> task = () -> {
                    start.await();
                    try {
                        authTokenService.refresh(raw);
                        return "OK";
                    } catch (ApiException e) {
                        return e.getCode().name();
                    }
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            // 두 요청이 모두 새 토큰을 받는 일(중복 발급)은 없음
            assertThat(results).filteredOn("OK"::equals).hasSize(1);
            assertThat(results).filteredOn(r -> !"OK".equals(r))
                    .allMatch(r -> r.equals(ErrorCode.REFRESH_TOKEN_REUSED.name())
                            || r.equals(ErrorCode.REFRESH_TOKEN_REVOKED.name()));
        } finally {
            executor.shutdownNow();
        }
        // 엄격 정책: 재사용이 감지되었으므로 이긴 요청이 받은 토큰까지 계열 전체 폐기, 새 행은 1개만 생김
        assertThat(tokens()).hasSize(2);
        assertThat(activeTokens()).isZero();
    }

    // ===== 로그아웃 =====

    @Test
    void 로그아웃하면_계열이_폐기되고_쿠키가_삭제되며_이후_재발급은_거부된다() throws Exception {
        String first = refreshCookieOf(login());
        String second = refreshCookieOf(refresh(first));
        String otherDevice = refreshCookieOf(login());

        mockMvc.perform(post("/api/users/logout").cookie(new Cookie(COOKIE, second)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/users")));

        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, second)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
        // 이미 ROTATED였던 first의 사유는 유지 (덮어쓰지 않음)
        assertThat(tokens().get(0).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.ROTATED);
        assertThat(tokens().get(1).getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.LOGOUT);
        // 다른 기기의 로그인은 유지
        assertThat(refresh(otherDevice).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 로그아웃은_멱등하다_쿠키가_없거나_이미_로그아웃했어도_204() throws Exception {
        String raw = refreshCookieOf(login());

        mockMvc.perform(post("/api/users/logout").cookie(new Cookie(COOKIE, raw))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/users/logout").cookie(new Cookie(COOKIE, raw))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/users/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    // ===== CSRF =====

    @Test
    void 허용하지_않은_출처의_재발급_로그아웃_요청은_쿠키가_있어도_403이고_토큰은_그대로다() throws Exception {
        String raw = refreshCookieOf(login());

        mockMvc.perform(post("/api/users/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/logout")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isForbidden());

        assertThat(activeTokens()).isEqualTo(1);
        assertThat(tokens().get(0).isRevoked()).isFalse();
    }

    @Test
    void 허용_출처의_재발급은_자격_증명_허용_헤더와_함께_성공한다() throws Exception {
        String raw = refreshCookieOf(login());

        mockMvc.perform(post("/api/users/refresh")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    // ===== 401 code (Access Token) =====

    @Test
    void 만료된_Access_Token은_TOKEN_EXPIRED() throws Exception {
        String expired = new JwtProvider(jwtSecret, -60_000).createToken("customer1", Role.CUSTOMER);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("토큰이 만료되었습니다."));
    }

    @Test
    void 다른_키로_서명한_Access_Token은_만료됐더라도_TOKEN_INVALID() throws Exception {
        String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
        String forged = new JwtProvider(otherKey, 3_600_000).createToken("customer1", Role.ADMIN);
        String forgedExpired = new JwtProvider(otherKey, -60_000).createToken("customer1", Role.ADMIN);

        for (String token : List.of(forged, forgedExpired, "not.a.jwt")) {
            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                    .andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다."));
        }
    }

    @Test
    void 토큰이_없으면_code_없이_기존_메시지() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    void 재발급으로_받은_Access_Token으로_API를_호출할_수_있다() throws Exception {
        String raw = refreshCookieOf(login());
        String accessToken = JsonPath.read(
                refresh(raw).getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("customer1"));
    }

    @Test
    void 만료_시각_직전에는_재발급된다() throws Exception {
        String raw = refreshCookieOf(login());
        clock.set(NOW.plus(Duration.ofDays(14)).minusSeconds(1));

        assertThat(refresh(raw).getResponse().getStatus()).isEqualTo(200);
    }

    // ===== 계열 절대 만료 (V16) =====

    @Test
    void 계속_재발급해도_로그인_후_30일이_지나면_만료된다() throws Exception {
        String raw = refreshCookieOf(login());

        // 10일째: 재발급 시각 + 14일 = 24일째 만료
        clock.set(NOW.plusDays(10));
        raw = refreshCookieOf(refresh(raw));
        assertThat(tokens().get(1).getExpiresAt()).isEqualTo(NOW.plusDays(24));

        // 20일째: 재발급 시각 + 14일(34일째)이 아니라 로그인 후 30일째로 잘림
        clock.set(NOW.plusDays(20));
        raw = refreshCookieOf(refresh(raw));
        assertThat(tokens().get(2).getExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(tokens()).allSatisfy(t -> assertThat(t.getFamilyExpiresAt()).isEqualTo(NOW.plusDays(30)));

        // 30일째: 재로그인 필요
        clock.set(NOW.plusDays(30));
        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
    }

    // ===== 보관 정리 (V16) =====

    @Test
    void 만료된_지_보관_기간이_지난_Refresh_Token만_정리된다() throws Exception {
        String old = refreshCookieOf(login());            // NOW + 14일에 만료
        clock.set(NOW.plusDays(10));
        String recent = refreshCookieOf(login());         // NOW + 24일에 만료

        // NOW + 22일: old는 만료 후 8일(보관 7일 초과), recent는 아직 유효
        clock.set(NOW.plusDays(22));
        assertThat(retentionCleaner.cleanUp().refreshTokens()).isEqualTo(1);

        assertThat(tokens()).hasSize(1);
        // 지워진 토큰은 모르는 값으로 거부, 남은 토큰은 그대로 재발급 가능
        mockMvc.perform(post("/api/users/refresh").cookie(new Cookie(COOKIE, old)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        assertThat(refresh(recent).getResponse().getStatus()).isEqualTo(200);
        // 다시 실행해도 더 지우지 않음
        assertThat(retentionCleaner.cleanUp().refreshTokens()).isZero();
    }
}

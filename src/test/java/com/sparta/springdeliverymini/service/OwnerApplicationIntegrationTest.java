package com.sparta.springdeliverymini.service;

import com.jayway.jsonpath.JsonPath;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.OwnerApplication;
import com.sparta.springdeliverymini.entity.OwnerApplicationStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.OwnerApplicationRepository;
import com.sparta.springdeliverymini.repository.RefreshTokenRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 승인제(V17)를 실제 PostgreSQL + 보안 필터로 검증
// - 가입은 고객만 → 신청 → 관리자 승인 → 재발급한 토큰부터 OWNER로 매장 생성
// - 승인 전에 받은 Access(role=CUSTOMER)로는 사장님 API 403 (권한이 토큰에 먼저 생기지 않음)
// - 동시 신청은 부분 유니크 인덱스로 1건만, 같은 신청의 동시 승인·거절은 행 잠금으로 1건만
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnerApplicationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private OwnerApplicationRepository ownerApplicationRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private StoreRepository storeRepository;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(new User("admin1", passwordEncoder.encode("password123"), Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: stores·owner_applications·refresh_tokens → users
        storeRepository.deleteAllInBatch();
        ownerApplicationRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult signupAndLogin(String username) throws Exception {
        mockMvc.perform(json(post("/api/users/signup"),
                        "{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
        return mockMvc.perform(json(post("/api/users/login"),
                        "{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String accessTokenOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private long apply(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/owner-applications"), "{\"businessName\":\"치킨집\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private User customer(String username) {
        return userRepository.save(new User(username, "encoded", Role.CUSTOMER));
    }

    // ===== 가입 =====

    @Test
    void OWNER로는_가입할_수_없다() throws Exception {
        mockMvc.perform(json(post("/api/users/signup"),
                        "{\"username\":\"owner1\",\"password\":\"password123\",\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findByUsername("owner1")).isEmpty();
    }

    // ===== 신청 → 승인 → 재발급 =====

    @Test
    void 승인되면_재발급한_토큰부터_OWNER로_매장을_만들_수_있다() throws Exception {
        MvcResult login = signupAndLogin("cus1");
        String oldAccess = accessTokenOf(login);
        Cookie refreshCookie = login.getResponse().getCookie("refresh_token");
        long applicationId = apply(oldAccess);

        mockMvc.perform(get("/api/owner-applications/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.businessName").value("치킨집"));
        mockMvc.perform(get("/api/admin/owner-applications").header(HttpHeaders.AUTHORIZATION, bearer("admin1", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$[0].id").value(applicationId))
                .andExpect(jsonPath("$[0].username").value("cus1"));

        mockMvc.perform(patch("/api/admin/owner-applications/" + applicationId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin1", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("admin1"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
        assertThat(userRepository.findByUsername("cus1").orElseThrow().getRole()).isEqualTo(Role.OWNER);

        // 승인 전에 받은 Access는 role=CUSTOMER → URL 규칙에서 403
        mockMvc.perform(json(post("/api/stores"), "{\"name\":\"치킨집\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccess))
                .andExpect(status().isForbidden());

        // 재발급하면 DB 역할(OWNER)로 새 Access
        MvcResult refreshed = mockMvc.perform(post("/api/users/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andReturn();
        String newAccess = accessTokenOf(refreshed);
        assertThat(jwtProvider.parseClaims(newAccess).get("role", String.class)).isEqualTo(Role.OWNER.name());

        mockMvc.perform(json(post("/api/stores"), "{\"name\":\"치킨집\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.storeIds.length()").value(1));
    }

    @Test
    void 거절되면_사유가_보이고_다시_신청할_수_있다() throws Exception {
        String access = accessTokenOf(signupAndLogin("cus1"));
        long first = apply(access);

        mockMvc.perform(json(patch("/api/admin/owner-applications/" + first + "/reject"), "{\"reason\":\"상호 확인 불가\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin1", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(get("/api/owner-applications/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(jsonPath("$.rejectReason").value("상호 확인 불가"));

        long second = apply(access);
        assertThat(second).isGreaterThan(first);
        // 이미 처리된 신청은 다시 처리할 수 없음
        mockMvc.perform(patch("/api/admin/owner-applications/" + first + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin1", Role.ADMIN)))
                .andExpect(status().isConflict());
    }

    // ===== 동시성 =====

    @Test
    void 같은_고객이_동시에_신청해도_대기_중_신청은_1건만_생긴다() throws Exception {
        User user = customer("cus1");
        String access = jwtProvider.createToken("cus1", Role.CUSTOMER);

        List<Integer> statuses = runConcurrently(8, () -> mockMvc.perform(
                        json(post("/api/owner-applications"), "{\"businessName\":\"치킨집\"}")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andReturn().getResponse().getStatus());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(7);
        assertThat(ownerApplicationRepository.findAll())
                .filteredOn(a -> a.getUser().getId().equals(user.getId()) && a.getStatus() == OwnerApplicationStatus.REQUESTED)
                .hasSize(1);
    }

    @Test
    void 같은_신청에_승인과_거절이_동시에_와도_한쪽만_성공한다() throws Exception {
        User user = customer("cus1");
        OwnerApplication application = ownerApplicationRepository.save(
                new OwnerApplication(user, "치킨집", java.time.LocalDateTime.of(2026, 11, 21, 10, 0)));
        String adminBearer = bearer(admin.getUsername(), Role.ADMIN);
        String base = "/api/admin/owner-applications/" + application.getId();

        // 승인 3건 + 거절 3건을 동시에
        AtomicInteger seq = new AtomicInteger();
        List<Integer> statuses = runConcurrently(6, () -> {
            MockHttpServletRequestBuilder request = seq.getAndIncrement() % 2 == 0
                    ? patch(base + "/approve")
                    : json(patch(base + "/reject"), "{\"reason\":\"사유\"}");
            return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, adminBearer))
                    .andReturn().getResponse().getStatus();
        });

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);
        OwnerApplication result = ownerApplicationRepository.findById(application.getId()).orElseThrow();
        Role role = userRepository.findById(user.getId()).orElseThrow().getRole();
        // 승인이 이겼으면 OWNER, 거절이 이겼으면 CUSTOMER — 신청 상태와 역할이 항상 일치
        assertThat(role).isEqualTo(result.getStatus() == OwnerApplicationStatus.APPROVED ? Role.OWNER : Role.CUSTOMER);
    }

    private static List<Integer> runConcurrently(int threads, Callable<Integer> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}

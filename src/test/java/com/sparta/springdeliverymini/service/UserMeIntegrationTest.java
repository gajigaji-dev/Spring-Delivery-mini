package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.StoreCreateRequest;
import com.sparta.springdeliverymini.dto.StoreResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내 정보 조회 API(#62)를 실제 PostgreSQL + 보안 필터까지 통과시켜 검증
// - 응답은 토큰 값이 아니라 DB 기준 (역할 변경 반영), OWNER는 본인 매장 id만 오름차순으로
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserMeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private StoreService storeService;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: stores → users
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void OWNER는_DB의_회원_정보와_본인_매장_id를_받는다() throws Exception {
        User owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("owner2", "encoded-password", Role.OWNER));
        StoreResponse first = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        storeService.createStore(new StoreCreateRequest("피자집"), "owner2");
        StoreResponse second = storeService.createStore(new StoreCreateRequest("분식집"), "owner1");

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.getId()))
                .andExpect(jsonPath("$.username").value("owner1"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.storeIds.length()").value(2))
                .andExpect(jsonPath("$.storeIds[0]").value(first.id()))
                .andExpect(jsonPath("$.storeIds[1]").value(second.id()))
                // 비밀번호·토큰 권한 문자열은 응답에 없음
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist());
    }

    @Test
    void 토큰의_역할이_아니라_DB의_역할을_돌려준다() throws Exception {
        // 토큰 발급 뒤 역할이 바뀐 경우 — 토큰은 CUSTOMER, DB는 OWNER
        userRepository.save(new User("user1", "encoded-password", Role.OWNER));

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer("user1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.storeIds.length()").value(0));
    }

    @Test
    void CUSTOMER는_빈_매장_목록을_받는다() throws Exception {
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.storeIds").isArray())
                .andExpect(jsonPath("$.storeIds.length()").value(0));
    }

    @Test
    void 토큰은_유효하지만_회원이_삭제됐으면_401() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer("deleted", Role.CUSTOMER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("존재하지 않는 회원입니다."));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }
}

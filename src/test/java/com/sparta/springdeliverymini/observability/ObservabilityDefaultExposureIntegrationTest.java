package com.sparta.springdeliverymini.observability;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기본 설정(application.yml, 운영 기준)의 노출 정책 검증 — test 프로필은 Actuator·springdoc 설정을 덮어쓰지 않음
// 헬스체크만 공개, 메트릭·API 문서는 노출되지 않아야 함
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ObservabilityDefaultExposureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    void 헬스체크는_토큰_없이_UP만_응답하고_상세_정보는_숨긴다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist()); // DB 등 내부 구성 노출 안 함
    }

    @Test
    void 기본_설정에서는_prometheus_메트릭이_노출되지_않는다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void 그_밖의_Actuator_경로는_토큰이_없으면_401_ADMIN이_아니면_403() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken("owner", Role.OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기본_설정에서는_Swagger_UI와_API_명세가_비활성이다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 모든_응답에_traceId_헤더가_붙는다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().exists("X-Trace-Id"));
    }
}

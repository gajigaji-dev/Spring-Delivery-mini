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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// local·dev 프로필처럼 메트릭·API 문서를 켰을 때의 동작 검증 (설정값만 프로필 파일과 같게 지정)
@SpringBootTest(properties = {
        "management.endpoints.web.exposure.include=health,metrics,prometheus",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ObservabilityEnabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    void prometheus_메트릭은_토큰_없이_수집할_수_있다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp_connections")));
    }

    @Test
    void metrics_엔드포인트는_노출돼도_ADMIN만_볼_수_있다() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken("owner", Role.OWNER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken("admin", Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void API_명세에_주요_API와_JWT_인증_방식이_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Spring Delivery Mini API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/payments']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/payments/{id}/refund']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/settlements/{id}/pay']").exists());
    }

    @Test
    void Swagger_UI는_토큰_없이_열린다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}

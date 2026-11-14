package com.sparta.springdeliverymini.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// springdoc(OpenAPI 3) 문서 기본 정보 + JWT 인증 방식
// 엔드포인트 목록·요청/응답 스키마는 컨트롤러·DTO에서 자동 생성 (문서용 어노테이션을 컨트롤러마다 붙이지 않음)
// Swagger UI·명세 노출 여부는 springdoc.*.enabled 설정으로 프로필별 관리 (기본 비활성, local·dev만 활성)
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring Delivery Mini API")
                        .description("배달 주문·결제·정산 API. 에러 응답 형식과 상세 정책은 docs/api.md 참고")
                        .version("v1"))
                // Swagger UI의 Authorize 버튼에 로그인 응답의 accessToken을 넣으면 모든 요청에 Bearer 헤더가 붙음
                // 회원가입·로그인 등 공개 API에도 표시되지만, 토큰이 없어도 그대로 호출됨
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}

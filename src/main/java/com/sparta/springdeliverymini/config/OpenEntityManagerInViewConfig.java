package com.sparta.springdeliverymini.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// open-in-view(요청 단위 EntityManager)를 직접 등록하고, 오래 열려 있는 응답 경로만 제외 (#76)
//
// 왜: Spring Boot 기본 OSIV는 모든 요청에 EntityManager를 열고 응답이 끝날 때 닫음. 한 번이라도 DB를 조회하면
// 그 커넥션을 EntityManager가 닫힐 때까지 잡고 있어(Spring의 Hibernate 설정 DELAYED_ACQUISITION_AND_HOLD),
// SSE처럼 응답이 몇 분씩 열려 있는 요청은 그동안 커넥션을 계속 점유함 → 사장님 수십 명 접속만으로 풀(20)이 바닥남.
// 트랜잭션을 REQUIRES_NEW로 바꿔도 피할 수 없음: OSIV EntityManager는 "진행 중 트랜잭션"이 아니라서
// 새 트랜잭션이 같은 EntityManager를 그대로 씀 (OwnerOrderStreamIntegrationTest로 확인한 동작).
//
// 그래서 spring.jpa.open-in-view=false로 기본 등록을 끄고, 같은 인터셉터를 SSE 경로만 빼고 다시 등록함.
// 다른 API는 지금과 동작이 같고, 제외 경로는 서비스의 @Transactional이 끝나는 즉시 커넥션을 돌려줌
// (대신 제외 경로의 컨트롤러에서는 지연 로딩을 쓰면 안 됨 — 필요한 값은 서비스 안에서 꺼내 반환)
@Configuration
public class OpenEntityManagerInViewConfig implements WebMvcConfigurer {

    // 연결이 오래 유지되는 응답 경로 — 요청 단위 EntityManager를 열지 않음
    static final String[] LONG_LIVED_PATHS = {"/api/owner/order-events"};

    private final ObjectProvider<EntityManagerFactory> entityManagerFactory;

    public OpenEntityManagerInViewConfig(ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // @WebMvcTest처럼 JPA 없이 웹 계층만 띄우는 경우에는 등록하지 않음
        EntityManagerFactory emf = entityManagerFactory.getIfAvailable();
        if (emf == null) {
            return;
        }
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(emf);
        registry.addWebRequestInterceptor(interceptor).excludePathPatterns(LONG_LIVED_PATHS);
    }
}

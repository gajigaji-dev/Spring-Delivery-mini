package com.sparta.springdeliverymini;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// Kafka 통합 테스트용 컨테이너 (#42) — TestcontainersConfiguration(PostgreSQL·Redis)과 함께 @Import
//
// - TestcontainersConfiguration에 넣지 않은 이유: Kafka가 필요 없는 나머지 통합 테스트까지 Kafka 컨테이너를 띄우지 않도록
//   (test 프로필은 Kafka 토픽 생성·Consumer·주기 발행을 끔 → Kafka 테스트 클래스에서 속성으로 다시 켬)
// - @ServiceConnection: spring.kafka.bootstrap-servers를 컨테이너 주소로 자동 연결
// - docker-compose.yml과 같은 이미지(KRaft 단일 노드)
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));
    }
}

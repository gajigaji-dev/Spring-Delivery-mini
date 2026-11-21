package com.sparta.springdeliverymini.config;

import io.lettuce.core.resource.Transports;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// Redis 클라이언트(Lettuce)의 DNS 조회 시간 상한 — compose에서 Redis 장애 시 503이 5초 넘게 걸리던 원인 (docs/architecture.md Redis 장)
//
// 원인 (2026-11-21 재현)
// - Lettuce는 netty-resolver-dns가 있으면 JVM 대신 Netty 자체 DNS 조회기를 씀
// - compose에서 redis 컨테이너가 멈추면 Docker 내장 DNS(127.0.0.11)가 "redis"를 모르는 이름으로 보고 호스트 DNS로 넘김
//   → NXDOMAIN이 오기까지 약 4초 (앱 컨테이너에서 getent hosts redis 3.9초, 정상일 때 18ms)
// - spring.data.redis.connect-timeout(1초)은 TCP 연결에만 걸리고 이름 조회에는 걸리지 않음
//   → 키 있는 결제가 503을 받기까지 3.9~9.5초 (조회가 한 요청에서 두 번 일어나면 두 배)
// - local jar는 localhost:6380으로 접속해 이름 조회가 없음 → 약 1초
//
// 조치: DNS 질의 1회의 상한을 connect-timeout과 같게 → 이름 조회를 포함한 연결 단계 전체가 의도한 시간 안에 실패
// 정상일 때 compose DNS 응답은 수 ms라 1초 상한에 걸리지 않음
@Configuration
public class RedisClientConfig {

    // Lettuce 기본값과 같은 채널 종류(Transports)로 만들어, 네이티브 전송(epoll 등)을 쓰는 환경에서도 같은 이벤트 루프에 붙음
    // Lettuce가 직접 만든 기본 조회기가 아니라서 종료는 이 빈이 맡음
    @Bean(destroyMethod = "close")
    DnsAddressResolverGroup redisDnsResolverGroup(
            @Value("${spring.data.redis.connect-timeout:1s}") Duration connectTimeout) {
        return new DnsAddressResolverGroup(new DnsNameResolverBuilder()
                .datagramChannelType(Transports.datagramChannelClass())
                .queryTimeoutMillis(connectTimeout.toMillis()));
    }

    @Bean
    ClientResourcesBuilderCustomizer redisDnsTimeoutCustomizer(DnsAddressResolverGroup redisDnsResolverGroup) {
        return builder -> builder.addressResolverGroup(redisDnsResolverGroup);
    }
}

package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

// application.yml의 toss.payments.* — 토스페이먼츠 테스트 모드 결제 (pg.TossPaymentsClient, docs/api.md 토스페이먼츠 결제 장)
//
// 모드 (mode)
// - real(기본): 토스 테스트 서버를 테스트 키로 호출 (pg.TossPaymentsHttpClient). 테스트 키라 실제 돈은 오가지 않음
// - fake: 토스에 아예 접속하지 않는 가짜 PG (pg.FakeTossPaymentsClient) — 키 없이 오프라인 데모, 거절·시간 초과 재현
//   prod 프로필에서는 쓸 수 없음 (ProdProfileGuard)
//
// 키 (real 모드)
// - client-key·secret-key는 기본값 없이 TOSS_CLIENT_KEY·TOSS_SECRET_KEY 환경변수로만 받음 (설정 파일·저장소에 두지 않음)
// - 둘 다 없으면 토스 결제를 끔(enabled() = false) → 결제 설정 API가 enabled=false를 돌려주고 화면은 모의 결제를 보여 줌
// - 하나만 있으면 기동 실패 (반쪽 설정으로 결제창은 열리는데 승인은 실패하는 상황을 막음)
// - 테스트 키(test_ck_ / test_sk_)만 허용 → 실수로 운영(live) 키를 넣어도 실제 돈이 오가지 않도록 기동 단계에서 막음
//
// 시간 초과 (결과를 모르는 상황의 처리는 service.TossPaymentService 참고)
// - connect-timeout: TCP·TLS 연결 상한
// - confirm-timeout: 승인 요청을 보낸 뒤 응답까지의 상한. 이 시간 + query-timeout이 결제 멱등성 processing-ttl(1분)보다 짧아야 함
//   (처리 중 기록이 먼저 만료되면 같은 키 재시도가 동시에 승인을 호출할 수 있음 — 그래도 토스 멱등 키로 결과는 하나)
// - query-timeout: 승인 결과를 모를 때 결제 조회 API의 상한
// - cancel-timeout: 취소 요청 상한
@ConfigurationProperties(prefix = "toss.payments")
public record TossPaymentsProperties(String clientKey, String secretKey, URI baseUrl, Duration connectTimeout,
                                     Duration confirmTimeout, Duration queryTimeout, Duration cancelTimeout,
                                     Cancel cancel, Mode mode) {

    public enum Mode { REAL, FAKE }

    private static final String TEST_CLIENT_KEY_PREFIX = "test_ck_";
    private static final String TEST_SECRET_KEY_PREFIX = "test_sk_";

    public TossPaymentsProperties {
        if (mode == null) {
            mode = Mode.REAL;
        }
        clientKey = StringUtils.hasText(clientKey) ? clientKey.trim() : null;
        secretKey = StringUtils.hasText(secretKey) ? secretKey.trim() : null;
        if ((clientKey == null) != (secretKey == null)) {
            throw new IllegalArgumentException(
                    "TOSS_CLIENT_KEY와 TOSS_SECRET_KEY는 함께 설정해야 합니다. (값은 보안상 출력하지 않음)");
        }
        if (clientKey != null && !clientKey.startsWith(TEST_CLIENT_KEY_PREFIX)) {
            throw new IllegalArgumentException("TOSS_CLIENT_KEY는 테스트 키(test_ck_)만 사용할 수 있습니다.");
        }
        if (secretKey != null && !secretKey.startsWith(TEST_SECRET_KEY_PREFIX)) {
            throw new IllegalArgumentException("TOSS_SECRET_KEY는 테스트 키(test_sk_)만 사용할 수 있습니다.");
        }
        if (baseUrl == null) {
            baseUrl = URI.create("https://api.tosspayments.com");
        }
        if (baseUrl.getHost() == null
                || !("https".equalsIgnoreCase(baseUrl.getScheme()) || "http".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("toss.payments.base-url은 http(s) 절대 주소여야 합니다: " + baseUrl);
        }
        requirePositive("connect-timeout", connectTimeout);
        requirePositive("confirm-timeout", confirmTimeout);
        requirePositive("query-timeout", queryTimeout);
        requirePositive("cancel-timeout", cancelTimeout);
        if (cancel == null) {
            throw new IllegalArgumentException("toss.payments.cancel 설정이 필요합니다.");
        }
        // 임대 시간 안에 취소 호출이 끝나야 다른 작업자가 같은 행을 동시에 가져가지 않음
        if (cancel.lease().compareTo(connectTimeout.plus(cancelTimeout)) <= 0) {
            throw new IllegalArgumentException(
                    "toss.payments.cancel.lease는 connect-timeout + cancel-timeout보다 길어야 합니다.");
        }
    }

    // fake는 키 없이 항상 켜짐, real은 키가 있을 때만
    public boolean enabled() {
        return mode == Mode.FAKE || clientKey != null;
    }

    // PG 취소 재시도 (service.PgCancelProcessor)
    // enabled: 이 인스턴스에서 주기 재시도를 할지 (false여도 커밋 직후 1회 시도는 함)
    // 재시도 간격 = min(initial-backoff × 2^(실패 횟수-1), max-backoff) → 10s, 20s, 40s ... 최대 10분
    // max-attempts번 실패하면 FAILED (자동 재시도 중단, pg.cancel.failed 메트릭으로 확인)
    // lease: 작업자가 행을 가져간 뒤 다른 작업자가 가져가지 못하는 시간 (작업자가 죽으면 이 시간 뒤 다시 시도)
    public record Cancel(boolean enabled, Duration fixedDelay, int batchSize, int maxAttempts,
                         Duration initialBackoff, Duration maxBackoff, Duration lease) {

        public Cancel {
            requirePositive("cancel.fixed-delay", fixedDelay);
            requirePositive("cancel.initial-backoff", initialBackoff);
            requirePositive("cancel.max-backoff", maxBackoff);
            requirePositive("cancel.lease", lease);
            if (batchSize < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("toss.payments.cancel.batch-size, max-attempts는 1 이상이어야 합니다.");
            }
        }

        // 실패 횟수(1부터)에 따른 다음 시도까지의 간격
        public Duration backoff(int failedAttempts) {
            int exponent = Math.max(0, Math.min(failedAttempts - 1, 20));
            Duration delay = initialBackoff.multipliedBy(1L << exponent);
            return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("toss.payments." + name + "는 0보다 커야 합니다: " + value);
        }
    }

    // 설정 객체가 로그·actuator 등에 문자열로 찍혀도 시크릿 키가 남지 않도록 가림 (클라이언트 키는 공개 값이라 그대로)
    @Override
    public String toString() {
        return "TossPaymentsProperties[mode=" + mode + ", clientKey=" + clientKey
                + ", secretKey=" + (secretKey == null ? "(없음)" : "******")
                + ", baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", confirmTimeout=" + confirmTimeout + ", queryTimeout=" + queryTimeout
                + ", cancelTimeout=" + cancelTimeout + ", cancel=" + cancel + "]";
    }
}

package com.sparta.springdeliverymini.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.PaymentIdempotencyProperties;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.service.PaymentService.IdempotentPayment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// 결제 멱등성 — Idempotency-Key 헤더가 있는 결제 요청을 Redis 기록으로 한 번만 처리 (#41, docs/api.md 결제)
//
// 역할 나눔
// - 중복 결제 차단은 지금처럼 DB가 맡음 (주문 행 잠금 + Order.pay() 상태 전이 + payments.order_id UNIQUE)
// - 이 클래스는 "같은 요청의 재시도"를 알아보는 일만 함
//   같은 키 재시도 → 처음 응답(201)을 그대로 돌려줌 / 같은 키에 다른 요청 → 422 / 처리 중 → 409
//   → Redis 기록이 사라지거나 틀어져도 결제가 두 번 되는 일은 없음 (재시도가 DB 검사로 409를 받을 뿐)
//
// 트랜잭션과의 순서
// - 이 클래스에는 @Transactional이 없음: PaymentService.pay()가 반환되면 결제는 이미 커밋된 상태
//   → 커밋된 결제만 "완료"로 기록 (커밋 전에 완료로 적었다가 롤백되면 결제 없는 응답을 재전송하게 됨)
// - 결제가 실패(예외)하면 처리 중 기록을 지움 → 같은 키로 다시 시도 가능 (실패 응답은 저장하지 않음)
//
// DB 멱등성 기록 (payment_idempotency_keys, V14) — Redis 기록이 없을 때의 근거
// - 결제와 같은 트랜잭션에 저장되므로(PaymentService.pay) 커밋된 결제에는 항상 기록이 있음
// - Redis에서 완료 응답을 찾지 못하면(TTL 만료·유실·처리 중 기록만 남음·Redis 장애) DB 기록을 확인해 처음 응답을 재전송
//   → 완료 응답 보관 시간(24h)이 지났거나 커밋 직후 Redis가 내려가도 같은 키 재시도는 201을 다시 받음
//
// Redis 장애·타임아웃 (docs/api.md 결제)
// - 처리 중 기록을 남기지 못하면: DB에 기록이 있으면 재전송, 없으면 결제하지 않고 503 → 클라이언트가 같은 키로 나중에 재시도
//   (Redis 없이 새 결제를 진행하지 않음 — 같은 키 동시 요청을 걸러낼 수 없으므로)
// - 결제 후 완료 기록·실패 후 삭제가 실패하면 결제 결과는 그대로 응답하고 로그만 남김
//   → 처리 중 기록이 processing-ttl 뒤에 만료되고, 그 뒤 재시도는 DB 기록으로 재전송
// - Redis 오류는 redis.failures{operation} 카운터로 셈 (monitoring/alerts.yml)
@Slf4j
@Service
public class PaymentIdempotencyService {

    static final String KEY_PREFIX = "idempotency:payment:";

    // UUID 등 클라이언트가 만든 임의 문자열. Redis 키에 그대로 넣지 않지만(해시) 길이·문자는 제한
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    // ARGV[1] = 내 처리 중 기록, ARGV[2] = 새 값, ARGV[3] = TTL(ms)
    // 값이 내 기록일 때만 바꿈 → processing-ttl이 지나 다른 요청이 키를 잡은 뒤에는 덮어쓰지 않음
    private static final RedisScript<Long> COMPLETE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    // ARGV[1] = 내 처리 중 기록 — 내 기록일 때만 삭제
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final PaymentService paymentService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PaymentIdempotencyProperties properties;
    private final MeterRegistry meterRegistry;

    public PaymentIdempotencyService(PaymentService paymentService,
                                     StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     PaymentIdempotencyProperties properties,
                                     MeterRegistry meterRegistry) {
        this.paymentService = paymentService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        // 처음 실패하기 전에도 0으로 노출 → 알림 규칙(increase)이 첫 실패부터 잡음
        for (String operation : List.of("idempotency.acquire", "idempotency.read",
                "idempotency.complete", "idempotency.release")) {
            meterRegistry.counter("redis.failures", "operation", operation);
        }
    }

    // replayed: 이번 요청에서 결제하지 않고 저장된 처음 응답을 돌려줬는지 (응답 헤더 Idempotent-Replayed)
    public record PaymentResult(PaymentResponse response, boolean replayed) {
    }

    // Redis에 저장하는 기록
    // - PROCESSING: token으로 요청마다 값을 다르게 해, 내 기록일 때만 완료·삭제
    // - COMPLETED: 처음 응답 본문
    // fingerprint: 요청 본문의 해시 → 같은 키에 다른 요청이 오면 422
    record IdempotencyRecord(State state, String fingerprint, String token, PaymentResponse response) {

        enum State { PROCESSING, COMPLETED }

        static IdempotencyRecord processing(String fingerprint) {
            return new IdempotencyRecord(State.PROCESSING, fingerprint, UUID.randomUUID().toString(), null);
        }

        static IdempotencyRecord completed(String fingerprint, PaymentResponse response) {
            return new IdempotencyRecord(State.COMPLETED, fingerprint, null, response);
        }
    }

    public PaymentResult pay(PaymentRequest request, String username, String idempotencyKey) {
        // 헤더가 없으면 기존 동작 그대로 (Redis를 쓰지 않음 → Redis 장애와 무관)
        if (idempotencyKey == null) {
            return new PaymentResult(paymentService.pay(request, username), false);
        }
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key는 영문·숫자·'-'·'_'로 1~64자여야 합니다.");
        }

        String key = redisKey(username, idempotencyKey);
        String fingerprint = fingerprint(request);
        String processing = objectMapper.writeValueAsString(IdempotencyRecord.processing(fingerprint));

        boolean acquired;
        try {
            acquired = acquire(key, processing);
        } catch (DataAccessException e) {
            // 연결 실패·타임아웃: 처리 중 기록을 남겼는지 알 수 없으므로 새 결제는 하지 않음
            // (타임아웃이면 SET이 늦게 실행돼 처리 중 기록이 남을 수 있음 → processing-ttl 뒤에 만료)
            // 이미 커밋된 결제의 재시도라면 DB 기록으로 처음 응답을 돌려주고, 아니면 503
            redisFailure("idempotency.acquire");
            return replayFromDb(username, idempotencyKey, fingerprint).orElseThrow(() -> unavailable(e));
        }
        if (!acquired) {
            return new PaymentResult(storedResponse(key, username, idempotencyKey, fingerprint), true);
        }

        // 처리 중 기록을 새로 잡음 = Redis에 기록이 없었음
        // → 완료 기록이 만료·유실된 뒤의 재시도일 수 있으므로 DB 기록을 먼저 확인하고, 없을 때만 결제
        PaymentResponse response;
        boolean replayed;
        try {
            Optional<IdempotentPayment> committed = paymentService.findIdempotentPayment(username, idempotencyKey);
            if (committed.isPresent()) {
                requireSameRequest(committed.get().fingerprint(), fingerprint);
                response = committed.get().response();
                replayed = true;
            } else {
                response = paymentService.pay(request, username, idempotencyKey, fingerprint);
                replayed = false;
            }
        } catch (RuntimeException e) {
            // 실패한 결제는 기록을 지워 같은 키로 다시 시도할 수 있게 함
            // (재시도해도 중복 결제는 DB가 막음. 같은 실패라면 같은 오류를 다시 받음)
            release(key, processing);
            throw e;
        }

        // 재전송이었다면 Redis 완료 기록을 다시 채움 → 다음 재시도는 DB를 거치지 않음
        complete(key, processing, IdempotencyRecord.completed(fingerprint, response));
        return new PaymentResult(response, replayed);
    }

    // 처리 중 기록을 남김 (SET NX PX) — 이미 키가 있으면 false
    // 같은 키 동시 요청은 여기서 한 요청만 통과 → 나머지는 처리 중(409) 또는 완료 응답 재전송
    private boolean acquire(String key, String processing) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key, processing, properties.processingTtl()));
    }

    // 이미 있는 기록 확인 — 다른 요청이면 422, 처리 중이면 409, 완료면 처음 응답
    // Redis에서 완료 응답을 얻지 못한 경우(읽기 실패·기록 없음·처리 중)에는 DB 기록을 확인
    //   → 결제 커밋 직후 Redis 장애로 완료 기록을 못 남겨 처리 중 기록만 남은 경우에도 처음 응답을 돌려줌
    private PaymentResponse storedResponse(String key, String username, String idempotencyKey, String fingerprint) {
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            redisFailure("idempotency.read");
            return replayFromDb(username, idempotencyKey, fingerprint).orElseThrow(() -> unavailable(e)).response();
        }
        if (stored != null) {
            IdempotencyRecord record = objectMapper.readValue(stored, IdempotencyRecord.class);
            requireSameRequest(record.fingerprint(), fingerprint);
            if (record.state() == IdempotencyRecord.State.COMPLETED) {
                return record.response();
            }
        }
        // 기록 없음(SET NX 실패 직후 만료·삭제) 또는 처리 중 — 커밋된 결제가 없으면 재시도를 요청
        // 여기서 처리 중 기록을 다시 잡지 않음 → 이 경로가 결제를 실행하는 일이 없도록 단순하게 유지
        return replayFromDb(username, idempotencyKey, fingerprint).orElseThrow(PaymentIdempotencyService::inProgress)
                .response();
    }

    // DB 멱등성 기록으로 처음 응답을 재전송 — 기록이 없으면 빈 값
    private Optional<PaymentResult> replayFromDb(String username, String idempotencyKey, String fingerprint) {
        return paymentService.findIdempotentPayment(username, idempotencyKey)
                .map(committed -> {
                    requireSameRequest(committed.fingerprint(), fingerprint);
                    return new PaymentResult(committed.response(), true);
                });
    }

    private static void requireSameRequest(String storedFingerprint, String fingerprint) {
        if (!storedFingerprint.equals(fingerprint)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "같은 Idempotency-Key로 다른 결제 요청을 보낼 수 없습니다.");
        }
    }

    // 결제 커밋 후 완료 기록 — 실패해도 결제는 이미 끝났으므로 응답은 그대로 (로그만)
    private void complete(String key, String processing, IdempotencyRecord completed) {
        Long orderId = completed.response().orderId();
        try {
            Long updated = redisTemplate.execute(COMPLETE_SCRIPT, List.of(key),
                    processing, objectMapper.writeValueAsString(completed),
                    String.valueOf(properties.completedTtl().toMillis()));
            if (!Long.valueOf(1).equals(updated)) {
                // processing-ttl보다 오래 걸려 기록이 만료된 경우 → 이후 재시도는 DB 검사로 409
                log.warn("결제 멱등성 완료 기록 생략 — 처리 중 기록이 이미 만료됨 orderId={}", orderId);
            }
        } catch (DataAccessException e) {
            redisFailure("idempotency.complete");
            log.warn("결제 멱등성 완료 기록 실패 — 결제는 완료됨, 처리 중 기록은 TTL 뒤 만료 orderId={}", orderId, e);
        }
    }

    private void release(String key, String processing) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), processing);
        } catch (DataAccessException e) {
            redisFailure("idempotency.release");
            log.warn("결제 멱등성 처리 중 기록 삭제 실패 — TTL 뒤 만료", e);
        }
    }

    // 사용자별로 키 공간을 나눔 → 다른 사용자가 같은 키를 써도 서로의 기록을 보거나 막지 않음
    // 아이디를 Redis 키에 그대로 남기지 않도록 해시 (키 길이도 고정)
    static String redisKey(String username, String idempotencyKey) {
        return KEY_PREFIX + sha256(username + "\n" + idempotencyKey);
    }

    // 결제 결과를 좌우하는 요청 값만 사용 (금액은 요청으로 받지 않음)
    static String fingerprint(PaymentRequest request) {
        return sha256(request.orderId() + "\n" + request.paymentMethod());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e); // 모든 JVM에 포함된 알고리즘이라 발생하지 않음
        }
    }

    // Redis 명령 실패 횟수 — 로그 대신 메트릭·알림으로 장애를 알아채기 위함 (lock.RedisDistributedLock과 같은 이름)
    private void redisFailure(String operation) {
        meterRegistry.counter("redis.failures", "operation", operation).increment();
    }

    private static ApiException unavailable(DataAccessException e) {
        log.warn("결제 멱등성 저장소(Redis) 사용 불가 — 결제하지 않고 503", e);
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "일시적으로 결제를 처리할 수 없습니다. 잠시 후 같은 Idempotency-Key로 다시 시도해 주세요.");
    }

    private static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT,
                "같은 Idempotency-Key의 결제가 처리 중입니다. 잠시 후 다시 시도해 주세요.");
    }
}

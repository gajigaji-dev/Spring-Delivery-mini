package com.sparta.springdeliverymini.service;

import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.PaymentIdempotencyProperties;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.exception.ApiException;
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
// Redis 장애·타임아웃 (docs/api.md 결제)
// - 처리 중 기록을 남기지 못하면 결제하지 않고 503 → 클라이언트가 같은 키로 나중에 재시도
// - 결제 후 완료 기록·실패 후 삭제가 실패하면 결제 결과는 그대로 응답하고 로그만 남김
//   → 처리 중 기록이 processing-ttl 뒤에 만료되고, 그 뒤 재시도는 DB 검사로 처리됨
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

    public PaymentIdempotencyService(PaymentService paymentService,
                                     StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     PaymentIdempotencyProperties properties) {
        this.paymentService = paymentService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
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

        if (!acquire(key, processing)) {
            return new PaymentResult(storedResponse(key, fingerprint), true);
        }

        PaymentResponse response;
        try {
            response = paymentService.pay(request, username);
        } catch (RuntimeException e) {
            // 실패한 결제는 기록을 지워 같은 키로 다시 시도할 수 있게 함
            // (재시도해도 중복 결제는 DB가 막음. 같은 실패라면 같은 오류를 다시 받음)
            release(key, processing);
            throw e;
        }

        complete(key, processing, IdempotencyRecord.completed(fingerprint, response));
        return new PaymentResult(response, false);
    }

    // 처리 중 기록을 남김 (SET NX PX) — 이미 키가 있으면 false
    // 같은 키 동시 요청은 여기서 한 요청만 통과 → 나머지는 처리 중(409) 또는 완료 응답 재전송
    private boolean acquire(String key, String processing) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(key, processing, properties.processingTtl()));
        } catch (DataAccessException e) {
            // 연결 실패·타임아웃: 기록을 남겼는지 알 수 없으므로 결제하지 않음
            // (타임아웃이면 SET이 늦게 실행돼 처리 중 기록이 남을 수 있음 → processing-ttl 뒤에 만료, 그동안 재시도는 409)
            throw unavailable(e);
        }
    }

    // 이미 있는 기록 확인 — 다른 요청이면 422, 처리 중이면 409, 완료면 처음 응답
    private PaymentResponse storedResponse(String key, String fingerprint) {
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        if (stored == null) {
            // SET NX 실패 직후 기록이 만료·삭제됨 (앞 요청이 실패해 지웠거나 TTL 만료)
            // 여기서 다시 잡지 않고 재시도를 요청 → 이 경로가 결제를 실행하는 일이 없도록 단순하게 유지
            throw inProgress();
        }
        IdempotencyRecord record = objectMapper.readValue(stored, IdempotencyRecord.class);
        if (!record.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "같은 Idempotency-Key로 다른 결제 요청을 보낼 수 없습니다.");
        }
        if (record.state() == IdempotencyRecord.State.PROCESSING) {
            throw inProgress();
        }
        return record.response();
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
            log.warn("결제 멱등성 완료 기록 실패 — 결제는 완료됨, 처리 중 기록은 TTL 뒤 만료 orderId={}", orderId, e);
        }
    }

    private void release(String key, String processing) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), processing);
        } catch (DataAccessException e) {
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

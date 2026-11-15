package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.PaymentFeeProperties;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.event.OutboxEventRecorder;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentIdempotencyKeyRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final Clock clock; // 결제 시각 기준 (config.TimeConfig, Asia/Seoul)
    private final PaymentFeeProperties feeProperties; // 공통 수수료율 (application.yml payment.fee.rate, 매장 수수료율이 없을 때 사용)
    private final OutboxEventRecorder outboxEventRecorder; // 결제 완료 이벤트 (#42)
    private final PaymentIdempotencyKeyRepository idempotencyKeyRepository; // DB 멱등성 기록 (V14)

    // DB에 남은 멱등성 기록 — fingerprint로 같은 요청인지 확인하고, response(결제 직후 응답)를 재전송
    public record IdempotentPayment(String fingerprint, PaymentResponse response) {
    }

    // 같은 사용자·같은 Idempotency-Key로 이미 커밋된 결제가 있는지 확인 (PaymentIdempotencyService)
    // Redis 기록이 없거나(TTL 만료·유실) Redis가 내려가 있을 때 처음 응답을 돌려주는 근거
    public Optional<IdempotentPayment> findIdempotentPayment(String username, String idempotencyKey) {
        return userRepository.findByUsername(username)
                .flatMap(user -> idempotencyKeyRepository.findByUserIdAndKey(user.getId(), idempotencyKey))
                .map(record -> new IdempotentPayment(
                        record.getFingerprint(), PaymentResponse.atPayment(record.getPayment())));
    }

    @Transactional
    public PaymentResponse pay(PaymentRequest request, String username) {
        return pay(request, username, null, null);
    }

    // idempotencyKey가 있으면 결제와 같은 트랜잭션에 멱등성 기록을 저장 (없으면 저장하지 않음)
    @Transactional
    public PaymentResponse pay(PaymentRequest request, String username, String idempotencyKey, String fingerprint) {

        // 1. 현재 로그인한 사용자 찾기
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. CUSTOMER인지 확인
        // SecurityConfig에서 1차로 막지만, 토큰 발급 후 역할이 바뀐 경우까지 대비 → 403
        if (currentUser.getRole() != Role.CUSTOMER) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "CUSTOMER만 결제할 수 있습니다."
            );
        }

        // 3. 결제 수단 확인
        // CARD 외의 값(CASH 등)이면 → 400
        if (!PaymentMethod.CARD.name().equals(request.paymentMethod())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "결제 수단은 CARD만 가능합니다."
            );
        }

        // 4. 결제할 주문 조회 (행 잠금)
        // 주문이 존재하지 않으면 → 404
        // 같은 주문의 결제·취소가 동시에 들어와도 한 번에 하나씩 → 취소된 주문에 결제가 저장되는 일 없음
        Order order = orderRepository.findByIdForUpdate(request.orderId())
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "주문이 없습니다."
                        )
                );

        // 5. 본인 주문인지 확인
        // 다른 CUSTOMER의 주문이면 → 403
        if (!order.getUser().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "본인의 주문만 결제할 수 있습니다."
            );
        }

        // 6. 주문 상태 변경
        // ORDER_REQUEST → PAYMENT_COMPLETED (Dirty Checking으로 DB에 반영)
        // 이미 결제완료됐거나(중복 결제) 취소된 주문 등 다른 상태라면 Order가 예외 → 409
        // 아래 결제 저장이 실패하면 예외로 트랜잭션 전체가 롤백되므로 상태 변경도 함께 취소됨
        order.pay();

        // 7. 결제 내역이 이미 있는지 한 번 더 확인 (중복 결제 방지) → 409
        if (paymentRepository.existsByOrderId(order.getId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이미 결제된 주문입니다."
            );
        }

        // 8. 결제 내역 저장
        // 결제 금액은 요청값이 아닌 주문 총액을 그대로 사용, 수수료·정산액은 Payment가 수수료율로 계산
        // 수수료율: 주문 매장의 수수료율 → 없으면 공통 수수료율 (결제 시점 값이 Payment.feeRate에 스냅샷으로 남음)
        Payment payment = new Payment(
                order,
                order.getTotalPrice(),
                order.getStore().effectiveFeeRate(feeProperties.rate()),
                PaymentMethod.CARD,
                // DB 컬럼 정밀도(timestamp(6), 마이크로초)에 맞춰 자름
                // → 첫 응답의 paidAt과 DB 기록으로 다시 만든 응답(PaymentResponse.atPayment)의 paidAt이 같아짐
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        );
        try {
            // 같은 주문을 동시에 결제하면 6·7번 검사를 둘 다 통과할 수 있음
            // saveAndFlush로 INSERT를 즉시 실행해 order_id unique 제약 위반을 여기서 잡아 409로 변환
            // TODO(@ManyToOne 전환): order_id unique가 사라지면 이 catch로는 동시 결제를 막을 수 없음
            //  → 부분 유니크 인덱스(WHERE status = 'SUCCESS') 또는 주문 행 비관적 락으로 대체
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이미 결제된 주문입니다."
            );
        }

        // 8-1. 멱등성 기록을 같은 트랜잭션에 저장 (Idempotency-Key가 있을 때만)
        // 결제가 커밋되면 기록도 반드시 남음 → Redis 기록이 사라져도 같은 키 재시도에 처음 응답을 돌려줄 수 있음
        if (idempotencyKey != null) {
            try {
                idempotencyKeyRepository.saveAndFlush(new PaymentIdempotencyKey(
                        currentUser, idempotencyKey, fingerprint, payment, LocalDateTime.now(clock)));
            } catch (DataIntegrityViolationException e) {
                // 같은 키로 다른 주문을 동시에 결제한 경우 (Redis 처리 중 기록이 만료·유실돼 둘 다 통과했을 때만 발생)
                // 예외로 트랜잭션이 롤백되므로 이 결제는 저장되지 않음
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "같은 Idempotency-Key의 결제가 이미 처리되었습니다. 잠시 후 다시 시도해 주세요."
                );
            }
        }

        // 9. 결제 완료 이벤트를 같은 트랜잭션에 저장 (Transactional Outbox, #42)
        // Kafka 발행은 커밋 후 OutboxPublisher가 따로 함 → Kafka 장애여도 결제는 성공, 결제가 롤백되면 이벤트도 남지 않음
        outboxEventRecorder.paymentCompleted(payment);

        // 결제 상태 변경 로그 — 식별자·금액·상태만 기록 (회원 아이디·카드 정보 등 개인정보는 남기지 않음)
        // traceId(MDC)가 함께 찍혀 요청 로그·에러 로그와 연결됨
        log.info("결제 완료 paymentId={}, orderId={}, storeId={}, amount={}, status={}",
                payment.getId(), order.getId(), order.getStore().getId(), payment.getAmount(), payment.getStatus());

        return PaymentResponse.from(payment);
    }
}

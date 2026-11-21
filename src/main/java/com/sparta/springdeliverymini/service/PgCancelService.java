package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.PaymentRefund;
import com.sparta.springdeliverymini.entity.PgCancelRequest;
import com.sparta.springdeliverymini.entity.PgProvider;
import com.sparta.springdeliverymini.repository.PgCancelRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// PG 결제 취소 요청 기록 (V20) — 실제 토스 호출은 커밋 후 PgCancelProcessor가 함
// 기록과 동시에 PgCancelRequested 이벤트를 내고, 커밋되면 같은 요청 스레드에서 바로 1회 시도
// (커밋 전에 호출하지 않음: 롤백되면 주문은 그대로인데 돈만 돌려주게 되므로)
@Slf4j
@Service
@RequiredArgsConstructor
public class PgCancelService {

    private final PgCancelRequestRepository pgCancelRequestRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // 커밋 후 바로 시도하라는 신호 (PgCancelProcessor.onRequested)
    public record PgCancelRequested(Long requestId) {
    }

    // 주문 취소로 CANCELLED가 된 PG 결제(전액) — 업무 트랜잭션 안에서만 호출 (같이 커밋·롤백)
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestCancel(Payment payment, String reason) {
        PgCancelRequest request = pgCancelRequestRepository.save(
                PgCancelRequest.forPayment(payment, reason, LocalDateTime.now(clock)));
        log.info("PG 취소 요청 기록 requestId={}, paymentId={}, amount={}", request.getId(), payment.getId(),
                payment.getAmount());
        eventPublisher.publishEvent(new PgCancelRequested(request.getId()));
    }

    // 관리자 환불 1건(부분·전액, V21) — 환불 금액만큼 토스 부분 취소. 업무 트랜잭션 안에서만 호출
    // 환불 행이 먼저 저장(INSERT)돼 있어야 함 (payment_refund_id FK)
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRefundCancel(PaymentRefund refund) {
        PgCancelRequest request = pgCancelRequestRepository.save(
                PgCancelRequest.forRefund(refund, LocalDateTime.now(clock)));
        log.info("PG 부분 취소 요청 기록 requestId={}, refundId={}, amount={}, balanceAfter={}", request.getId(),
                refund.getId(), request.getAmount(), request.getBalanceAfter());
        eventPublisher.publishEvent(new PgCancelRequested(request.getId()));
    }

    // 보상 취소 — 토스 승인은 됐지만 결제 행을 만들지 못함(그 사이 주문 취소·다른 결제로 이미 결제됨 등)
    // 실패한 기록 트랜잭션과 따로 커밋해야 하므로 REQUIRES_NEW
    // 같은 결제 키의 요청이 이미 있으면 새로 만들지 않음 (payment_key UNIQUE)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestCompensation(PgProvider provider, String paymentKey, long amount, String reason) {
        if (pgCancelRequestRepository.findByPaymentKeyAndPaymentRefundIsNull(paymentKey).isPresent()) {
            return;
        }
        PgCancelRequest request = pgCancelRequestRepository.save(
                PgCancelRequest.compensation(provider, paymentKey, amount, reason, LocalDateTime.now(clock)));
        // 결제 행이 없어 이 로그·행이 유일한 추적 단서 → 운영자가 토스 쪽 결제를 찾을 수 있게 결제 키를 남김
        // (결제 키는 시크릿 키 없이는 아무것도 할 수 없는 식별자)
        log.warn("PG 보상 취소 요청 기록 requestId={}, amount={}, paymentKey={}", request.getId(), amount, paymentKey);
        eventPublisher.publishEvent(new PgCancelRequested(request.getId()));
    }
}

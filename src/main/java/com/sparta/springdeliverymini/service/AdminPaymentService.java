package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.dto.PaymentRefundResponse;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.PaymentRefund;
import com.sparta.springdeliverymini.entity.PaymentStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.event.OutboxEventRecorder;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.repository.PaymentRefundRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// 관리자 결제 관리 — 배달완료 주문의 전액·부분 환불 (docs/settlement-policy.md 9장, 부분 환불 V21)
// - 환불 규칙(완료·부분 환불된 결제만, 배달완료 주문만, 남은 금액 안에서만)은 Payment.refund()가 판단 (위반 시 409)
// - 정산에는 여기서 손대지 않음 → 다음에 집계되는 정산이 미차감 환불로 찾아 차감 (이미 만들어진 정산은 바뀌지 않음)
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class AdminPaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final Clock clock; // 환불 시각 기준 (config.TimeConfig, Asia/Seoul)
    private final OutboxEventRecorder outboxEventRecorder; // 환불 이벤트 (#42)
    private final PgCancelService pgCancelService; // 토스 결제 취소 요청 (V20)
    private final PaymentRefundRepository paymentRefundRepository; // 환불 1건 기록 (V21)

    // 전액 환불 — 남은 금액 전부 (부분 환불이 없던 결제면 결제 금액 전부) → REFUNDED
    // 고객 결제액·플랫폼 수수료·매장 정산액이 모두 취소됨
    @Transactional
    public PaymentResponse refund(Long paymentId, String username) {
        validateAdmin(username);
        Payment payment = lockPayment(paymentId);
        refundAndRecord(payment, payment.refundableAmount(), "관리자 환불");
        return PaymentResponse.from(payment);
    }

    // 부분 환불 (V21) — 금액 지정, 남은 금액 안에서 여러 번 가능. 남은 금액 전부면 REFUNDED
    // 수수료는 환불액 비율대로 함께 취소 (PaymentFee.refundPortion, docs/money-policy.md 부분 환불)
    @Transactional
    public PaymentRefundResponse partialRefund(Long paymentId, long amount, String reason, String username) {
        validateAdmin(username);
        Payment payment = lockPayment(paymentId);
        // 상태가 환불 가능할 때만 금액을 따짐 — 이미 끝난 결제는 엔티티가 409(이미 환불·취소)로 알려 줌
        boolean refundable = payment.getStatus() == PaymentStatus.COMPLETED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED;
        if (refundable && amount > payment.refundableAmount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REFUND_AMOUNT,
                    "환불 금액이 남은 결제 금액(" + payment.refundableAmount() + "원)을 넘습니다.");
        }
        PaymentRefund refund = refundAndRecord(payment, amount, reason);
        return PaymentRefundResponse.of(refund);
    }

    // 행 잠금으로 조회 → 같은 결제를 동시에 환불해도 남은 금액을 넘지 않음 (뒤 요청은 앞 요청 커밋 후의 누적 환불액을 봄)
    // (주문은 함께 조회하지 않음 — JOIN하면 orders 행까지 잠기므로. 배달완료 주문은 최종 상태라 잠글 필요 없음)
    private Payment lockPayment(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.PAYMENT_NOT_FOUND, "결제가 없습니다."));
    }

    // 환불 1건 기록 — 환불 행·이벤트·PG 취소 요청을 같은 트랜잭션에
    private PaymentRefund refundAndRecord(Payment payment, long amount, String reason) {
        PaymentStatus before = payment.getStatus();
        PaymentRefund refund = paymentRefundRepository.save(payment.refund(amount, reason, LocalDateTime.now(clock)));
        // 환불 이벤트를 같은 트랜잭션에 저장 (Transactional Outbox, #42) — 알림 금액은 이번 환불 금액
        outboxEventRecorder.paymentRefunded(refund);
        // 토스를 거친 결제면 이번 환불 금액만큼 토스 부분 취소도 요청 — 호출은 커밋 후 (OrderService.cancelOrder와 같은 방식)
        if (payment.isGatewayPayment()) {
            pgCancelService.requestRefundCancel(refund);
        }
        // 환불 상태 변경 로그 — 식별자·금액·상태만 기록 (관리자·고객 아이디 등 개인정보는 남기지 않음)
        log.info("결제 환불 paymentId={}, orderId={}, refundId={}, amount={}, refundedTotal={}/{}, status={}->{}",
                payment.getId(), payment.getOrder().getId(), refund.getId(), refund.getAmount(),
                payment.getRefundedAmount(), payment.getAmount(), before, payment.getStatus());
        return refund;
    }

    // SecurityConfig에서도 ADMIN만 허용하지만, 토큰 발급 후 역할이 바뀐 경우에 대비해 DB 기준으로 한 번 더 확인
    private void validateAdmin(String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 환불할 수 있습니다.");
        }
    }
}

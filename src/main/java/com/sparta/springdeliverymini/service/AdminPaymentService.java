package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// 관리자 결제 관리 — 배달완료 주문의 전액 환불 (docs/settlement-policy.md 9장)
// - 환불 규칙(완료된 결제만, 배달완료 주문만, 중복 환불 불가)은 Payment.refund()가 판단 (위반 시 409)
// - 정산에는 여기서 손대지 않음 → 다음에 집계되는 정산이 미차감 환불로 찾아 차감 (이미 만들어진 정산은 바뀌지 않음)
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class AdminPaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final Clock clock; // 환불 시각 기준 (config.TimeConfig, Asia/Seoul)

    // 전액 환불 — COMPLETED → REFUNDED, 환불 시각 기록
    // 고객 결제액·플랫폼 수수료·매장 정산액이 모두 취소됨 (차감액은 결제에 저장된 금액 그대로)
    @Transactional
    public PaymentResponse refund(Long paymentId, String username) {
        validateAdmin(username);

        // 행 잠금으로 조회 → 같은 결제를 동시에 환불해도 한 번만 성공
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "결제가 없습니다."));

        payment.refund(LocalDateTime.now(clock));
        return PaymentResponse.from(payment);
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

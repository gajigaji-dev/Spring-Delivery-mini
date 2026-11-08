package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @Transactional
    public PaymentResponse pay(PaymentRequest request, String username) {

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

        // 4. 결제할 주문 조회
        // 주문이 존재하지 않으면 → 404
        Order order = orderRepository.findById(request.orderId())
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
        // 결제 금액은 요청값이 아닌 주문 총액을 그대로 사용
        Payment payment = new Payment(order, order.getTotalPrice(), PaymentMethod.CARD);
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

        return PaymentResponse.from(payment);
    }
}

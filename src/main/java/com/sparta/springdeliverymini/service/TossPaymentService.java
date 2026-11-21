package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.dto.TossCheckoutResponse;
import com.sparta.springdeliverymini.dto.TossConfigResponse;
import com.sparta.springdeliverymini.dto.TossConfirmRequest;
import com.sparta.springdeliverymini.entity.PgProvider;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.pg.TossApiException;
import com.sparta.springdeliverymini.pg.TossPayment;
import com.sparta.springdeliverymini.pg.TossPaymentsClient;
import com.sparta.springdeliverymini.pg.TossUnknownResultException;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

// 토스페이먼츠 결제 승인 순서 조립 (테스트 모드, docs/api.md 토스페이먼츠 결제 장)
//
// 흐름: 이미 기록됐는지 확인 → 사전 확인(금액·주문번호·주문 상태) → 토스 승인 → 응답 대조 → 결제 기록
// - 이 클래스에는 @Transactional이 없음: 토스 승인(외부 호출)을 기다리는 동안 DB 커넥션·주문 행 잠금을 잡지 않음
//   앞뒤 확인·기록은 PaymentService의 짧은 트랜잭션에서
//
// 중복 금전 처리를 막는 장치
// - 승인 호출의 멱등 키 = confirm-{paymentKey}: 같은 결제 키의 승인을 다시 보내도(시간 초과 후 재시도·동시 요청)
//   토스는 처음 응답을 그대로 돌려줌. 결제 키 하나는 한 번만 승인되므로 청구도 한 번
// - payments.pg_payment_key UNIQUE + 주문당 결제 1건(order_id UNIQUE): 같은 승인이 두 번 기록되지 않음
// - 결과를 모르는 경우(시간 초과·5xx)는 실패로 단정하지 않음 → 조회 API로 확인, 그래도 모르면 503 PG_RESULT_UNKNOWN
//   (화면은 결제창을 다시 열지 않고 같은 승인 요청을 재시도 → 같은 멱등 키라 승인은 한 번)
// - 승인은 됐는데 기록할 수 없으면(그 사이 주문 취소·다른 결제 키로 이미 결제됨) 보상 취소(PgCancelService)
//   기록 실패 원인이 불분명한 경우(DB 장애 등)에는 취소하지 않음: 같은 요청 재시도 때 승인 응답이 재전송되어 기록됨
@Slf4j
@Service
@RequiredArgsConstructor
public class TossPaymentService {

    private final PaymentService paymentService;
    private final TossPaymentsClient tossPaymentsClient;
    private final PgCancelService pgCancelService;

    public TossConfigResponse config() {
        return new TossConfigResponse(tossPaymentsClient.enabled(),
                tossPaymentsClient.enabled() ? tossPaymentsClient.clientKey() : null,
                tossPaymentsClient.mode().name());
    }

    public TossCheckoutResponse checkout(Long orderId, String username) {
        requireEnabled();
        return paymentService.prepareTossCheckout(orderId, username, tossPaymentsClient.clientKey());
    }

    // idempotencyKey·fingerprint: Idempotency-Key 헤더가 있을 때 PaymentIdempotencyService가 넘김 (결제와 같은 트랜잭션에 기록)
    public PaymentResult confirm(TossConfirmRequest request, String username, String idempotencyKey,
                                 String fingerprint) {
        requireEnabled();

        // 1. 같은 결제 키가 이미 기록됨 → 처음 결과를 돌려줌 (새로 고침·응답 유실 후 재전송)
        Optional<PaymentResponse> recorded = paymentService.findTossPayment(
                request.paymentKey(), username, request.orderId());
        if (recorded.isPresent()) {
            return new PaymentResult(recorded.get(), true);
        }

        // 2. 승인 전 확인 — 금액 불일치·다른 주문번호·결제할 수 없는 주문은 토스 승인까지 가지 않음
        paymentService.validateTossConfirm(request, username);

        // 3. 토스 승인 (트랜잭션 밖)
        TossPayment approved = approve(request);

        // 4. 승인 응답이 요청과 같은지 — 다르면 기록하지 않고 보상 취소 (토스가 금액을 검증하므로 정상이라면 일어나지 않음)
        if (!request.tossOrderId().equals(approved.orderId()) || approved.totalAmount() == null
                || approved.totalAmount() != request.amount().longValue()) {
            log.error("토스 승인 응답이 요청과 다름 — 보상 취소 orderId={}, requestAmount={}, approvedAmount={}",
                    request.orderId(), request.amount(), approved.totalAmount());
            compensate(request, "승인 응답 불일치");
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "승인된 결제가 주문과 맞지 않아 취소했습니다.");
        }

        // 5. 결제 기록
        try {
            return new PaymentResult(
                    paymentService.recordTossPayment(request, username, approved, idempotencyKey, fingerprint), false);
        } catch (ApiException | InvalidOrderStatusException e) {
            // 결제 행은 롤백됨. 같은 결제 키가 동시에 먼저 기록된 경우라면 이중 청구가 아님 → 그 결과를 돌려줌
            Optional<PaymentResponse> concurrent = paymentService.findTossPayment(
                    request.paymentKey(), username, request.orderId());
            if (concurrent.isPresent()) {
                return new PaymentResult(concurrent.get(), true);
            }
            // 승인은 됐지만 이 주문에 결제를 기록할 수 없음(주문 취소·다른 결제 키로 이미 결제) → 고객 돈을 돌려줌
            log.warn("토스 승인 후 결제 기록 불가 — 보상 취소 orderId={}, reason={}", request.orderId(), e.getMessage());
            compensate(request, "주문 상태 변경으로 결제 취소");
            throw e;
        } catch (RuntimeException e) {
            // DB 장애 등 — 기록됐는지 단정할 수 없으므로 취소하지 않음. 같은 승인 요청 재시도로 기록됨
            log.error("토스 승인 후 결제 기록 실패 — 재시도 필요 orderId={}, paymentKey={}",
                    request.orderId(), request.paymentKey(), e);
            throw e;
        }
    }

    private TossPayment approve(TossConfirmRequest request) {
        try {
            return requireDone(tossPaymentsClient.confirm(request.paymentKey(), request.tossOrderId(),
                    request.amount(), confirmIdempotencyKey(request)));
        } catch (TossApiException e) {
            if (TossApiException.ALREADY_PROCESSED_PAYMENT.equals(e.code())) {
                // 멱등 키 보관 기간(15일)이 지난 재시도 등 — 실제 상태가 승인 완료면 그 결과로 기록
                return lookupAfterUncertainConfirm(request);
            }
            throw rejected(e);
        } catch (TossUnknownResultException e) {
            log.warn("토스 승인 결과 불명 — 결제 조회로 확인 orderId={}, cause={}", request.orderId(), e.getMessage());
            return lookupAfterUncertainConfirm(request);
        }
    }

    // 승인 결과를 모를 때 실제 상태 확인 — 승인 완료면 그 결과, 승인될 수 없는 상태면 거절, 그 밖에는 503(재시도)
    private TossPayment lookupAfterUncertainConfirm(TossConfirmRequest request) {
        TossPayment current;
        try {
            current = tossPaymentsClient.getPayment(request.paymentKey());
        } catch (TossApiException | TossUnknownResultException e) {
            log.warn("토스 결제 조회 실패 — 결과 불명으로 응답 orderId={}, cause={}", request.orderId(), e.getMessage());
            throw unknownResult();
        }
        if (current.isDone()) {
            return current;
        }
        if (current.isClosedWithoutApproval()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PG_PAYMENT_REJECTED,
                    "결제가 승인되지 않았습니다. 결제를 다시 시도해 주세요. (상태: " + current.status() + ")");
        }
        // READY·IN_PROGRESS 등 — 승인 요청이 아직 처리 중일 수 있음
        throw unknownResult();
    }

    private TossPayment requireDone(TossPayment payment) {
        if (!payment.isDone()) {
            // 성공 응답인데 DONE이 아님(카드 결제에서는 없어야 함) — 단정하지 않고 재시도로 확인
            log.warn("토스 승인 응답 상태가 DONE이 아님 status={}", payment.status());
            throw unknownResult();
        }
        return payment;
    }

    private void compensate(TossConfirmRequest request, String reason) {
        pgCancelService.requestCompensation(PgProvider.TOSS, request.paymentKey(), request.amount(), reason);
    }

    private void requireEnabled() {
        if (!tossPaymentsClient.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PG_NOT_CONFIGURED,
                    "토스페이먼츠 결제가 설정되지 않았습니다.");
        }
    }

    // 결제 키 하나당 하나 — 같은 결제 키의 승인 재시도는 토스가 처음 응답을 돌려줌 (토스 멱등 키 최대 300자, 결제 키 최대 200자)
    static String confirmIdempotencyKey(TossConfirmRequest request) {
        return "confirm-" + request.paymentKey();
    }

    private static ApiException rejected(TossApiException e) {
        // 시크릿 키 오류는 고객이 해결할 수 없는 설정 문제 → 503 (결제되지 않음)
        if (e.httpStatus() == 401 || e.httpStatus() == 0) {
            log.error("토스 인증 실패 — 키 설정 확인 필요 code={}", e.code());
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PG_NOT_CONFIGURED,
                    "결제 설정 오류로 결제를 진행할 수 없습니다. (결제되지 않았습니다)");
        }
        String detail = e.tossMessage() == null ? "" : " (" + e.tossMessage() + ")";
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PG_PAYMENT_REJECTED,
                "결제가 승인되지 않았습니다." + detail);
    }

    private static ApiException unknownResult() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PG_RESULT_UNKNOWN,
                "결제 결과를 확인하지 못했습니다. 결제창을 다시 열지 말고 잠시 후 같은 요청으로 다시 확인해 주세요.");
    }
}

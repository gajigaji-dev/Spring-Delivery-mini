package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.dto.TossConfirmRequest;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.PaymentMethod;
import com.sparta.springdeliverymini.entity.PaymentStatus;
import com.sparta.springdeliverymini.entity.PgProvider;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.pg.TossApiException;
import com.sparta.springdeliverymini.pg.TossPayment;
import com.sparta.springdeliverymini.pg.TossPaymentsClient;
import com.sparta.springdeliverymini.pg.TossUnknownResultException;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 토스 승인 순서의 예외 경로 — 통합 테스트(TossPaymentIntegrationTest)에서 재현하기 어려운 경우만 (V20)
// 핵심: 승인된 돈이 기록되지 않은 채 남거나, 기록됐는데 취소되거나, 두 번 처리되는 일이 없어야 함
@ExtendWith(MockitoExtension.class)
class TossPaymentServiceTest {

    private static final String PAYMENT_KEY = "pk_1";
    private static final String TOSS_ORDER_ID = "order-1-0123456789abcdef";
    private static final TossConfirmRequest REQUEST = new TossConfirmRequest(1L, PAYMENT_KEY, TOSS_ORDER_ID, 36000L);
    private static final PaymentResponse RESPONSE = new PaymentResponse(10L, 1L, 36000, PaymentMethod.CARD,
            OrderStatus.PAYMENT_COMPLETED, LocalDateTime.of(2026, 11, 21, 12, 0), PaymentStatus.COMPLETED, null, null, 0);

    @Mock private PaymentService paymentService;
    @Mock private TossPaymentsClient tossPaymentsClient;
    @Mock private PgCancelService pgCancelService;
    @InjectMocks private TossPaymentService tossPaymentService;

    @BeforeEach
    void setUp() {
        given(tossPaymentsClient.enabled()).willReturn(true);
        given(paymentService.findTossPayment(PAYMENT_KEY, "customer1", 1L)).willReturn(Optional.empty());
    }

    @Test
    void 이미_처리된_결제라는_응답이면_조회해서_승인_완료일_때_기록한다() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossApiException(400, TossApiException.ALREADY_PROCESSED_PAYMENT, "이미 처리된 결제 입니다."));
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(done(36000L));
        given(paymentService.recordTossPayment(any(), anyString(), any(), any(), any())).willReturn(RESPONSE);

        PaymentResult result = tossPaymentService.confirm(REQUEST, "customer1", null, null);

        assertThat(result.response()).isEqualTo(RESPONSE);
        verify(pgCancelService, never()).requestCompensation(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void 결과를_모를_때_조회한_결제가_중단_상태면_거절로_응답하고_기록하지_않는다() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossUnknownResultException("시간 초과"));
        given(tossPaymentsClient.getPayment(PAYMENT_KEY))
                .willReturn(new TossPayment(PAYMENT_KEY, TOSS_ORDER_ID, "ABORTED", 36000L, 36000L, "카드", null));

        assertThatThrownBy(() -> tossPaymentService.confirm(REQUEST, "customer1", null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PG_PAYMENT_REJECTED));
        verify(paymentService, never()).recordTossPayment(any(), anyString(), any(), any(), any());
    }

    @Test
    void 결과를_모를_때_조회도_실패하면_503이고_취소하지_않는다() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossUnknownResultException("시간 초과"));
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willThrow(new TossUnknownResultException("시간 초과"));

        assertThatThrownBy(() -> tossPaymentService.confirm(REQUEST, "customer1", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo(ErrorCode.PG_RESULT_UNKNOWN);
                });
        verify(pgCancelService, never()).requestCompensation(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void 시크릿_키가_거부되면_고객_탓이_아니므로_503_PG_NOT_CONFIGURED() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossApiException(401, "UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."));

        assertThatThrownBy(() -> tossPaymentService.confirm(REQUEST, "customer1", null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo(ErrorCode.PG_NOT_CONFIGURED);
                });
    }

    @Test
    void 승인_금액이_요청과_다르면_기록하지_않고_보상_취소한다() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString())).willReturn(done(1000L));

        assertThatThrownBy(() -> tossPaymentService.confirm(REQUEST, "customer1", null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
        verify(paymentService, never()).recordTossPayment(any(), anyString(), any(), any(), any());
        verify(pgCancelService).requestCompensation(PgProvider.TOSS, PAYMENT_KEY, 36000L, "승인 응답 불일치");
    }

    @Test
    void 기록_중_409인데_같은_결제_키가_먼저_기록됐으면_이중_청구가_아니므로_취소하지_않고_그_결과를_돌려준다() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString())).willReturn(done(36000L));
        given(paymentService.recordTossPayment(any(), anyString(), any(), any(), any()))
                .willThrow(new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_ALREADY_PAID, "이미 결제된 주문입니다."));
        given(paymentService.findTossPayment(PAYMENT_KEY, "customer1", 1L))
                .willReturn(Optional.empty(), Optional.of(RESPONSE)); // 처음 확인 때는 없고, 동시 요청이 먼저 기록함

        PaymentResult result = tossPaymentService.confirm(REQUEST, "customer1", null, null);

        assertThat(result.replayed()).isTrue();
        assertThat(result.response()).isEqualTo(RESPONSE);
        verify(pgCancelService, never()).requestCompensation(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void 기록이_DB_장애로_실패하면_기록_여부를_모르므로_취소하지_않는다() {
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString())).willReturn(done(36000L));
        given(paymentService.recordTossPayment(any(), anyString(), any(), any(), any()))
                .willThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> tossPaymentService.confirm(REQUEST, "customer1", null, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(pgCancelService, never()).requestCompensation(any(), anyString(), anyLong(), anyString());
    }

    private static TossPayment done(Long amount) {
        return new TossPayment(PAYMENT_KEY, TOSS_ORDER_ID, "DONE", amount, amount, "카드", "2026-11-21T12:00:00+09:00");
    }
}

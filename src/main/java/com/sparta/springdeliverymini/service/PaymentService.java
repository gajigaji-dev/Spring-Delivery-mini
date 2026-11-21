package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.PaymentFeeProperties;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.dto.TossCheckoutResponse;
import com.sparta.springdeliverymini.dto.TossConfirmRequest;
import com.sparta.springdeliverymini.pg.TossPayment;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.event.OutboxEventRecorder;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentIdempotencyKeyRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // 토스 주문번호: order-{주문 id}-{난수 16자리} (토스 규칙: 영문·숫자·-·_·= 6~64자)
    static final String TOSS_ORDER_ID_PREFIX = "order-";
    private static final Pattern TOSS_ORDER_ID_PATTERN = Pattern.compile("order-(\\d{1,19})-[0-9a-f]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();

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

    // 결제 단건 조회 (#53) — 응답을 못 받은 결제(타임아웃 등)를 재전송하기 전에 현재 결과를 확인하는 용도
    // 멱등성 재전송 응답은 결제 직후 값이지만, 이 API는 현재 결제·주문 상태를 돌려줌
    public PaymentResponse getPayment(Long paymentId, String username) {

        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // SecurityConfig에서 1차로 막지만, 토큰 발급 후 역할이 바뀐 경우까지 대비 → 403
        if (currentUser.getRole() != Role.CUSTOMER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CUSTOMER만 결제를 조회할 수 있습니다.");
        }

        Payment payment = paymentRepository.findWithOrderById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.PAYMENT_NOT_FOUND, "결제 내역이 없습니다."));

        // 결제자 = 주문자 (결제는 본인 주문만 가능) → 주문자 기준으로 본인 결제인지 확인
        // 다른 CUSTOMER의 결제면 → 403 (주문 취소·결제와 같은 기준)
        if (!payment.getOrder().getUser().getId().equals(currentUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인의 결제만 조회할 수 있습니다.");
        }

        return PaymentResponse.from(payment);
    }

    // 내 결제 목록 (#62) — CUSTOMER 본인 결제만, id 오름차순 페이지 (#40 목록 방식)
    // 응답은 단건 조회와 같은 PaymentResponse (현재 결제·주문 상태)
    public Slice<PaymentResponse> getMyPayments(String username, PageQuery pageQuery) {

        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // SecurityConfig에서 1차로 막지만, 토큰 발급 후 역할이 바뀐 경우까지 대비 → 403
        if (currentUser.getRole() != Role.CUSTOMER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CUSTOMER만 결제를 조회할 수 있습니다.");
        }

        // 결제자 = 주문자 → 주문자 id로 조회 (다른 회원의 결제는 조건에서 빠짐)
        return paymentRepository.findAllByOrderUserId(currentUser.getId(), pageQuery.toPageable())
                .map(PaymentResponse::from);
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
                    ErrorCode.UNSUPPORTED_PAYMENT_METHOD,
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
                                ErrorCode.ORDER_NOT_FOUND,
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

        // 7. 결제 내역 중복 확인은 completePayment에서 (토스 결제와 공통)

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
        // 9. 저장·멱등성 기록·결제 완료 이벤트 (토스 결제와 공통)
        completePayment(currentUser, order, payment, idempotencyKey, fingerprint);
        return PaymentResponse.from(payment);
    }

    // 결제 저장 이후 공통 처리 — 모의 결제(pay)와 토스 결제(recordTossPayment)가 같은 규칙을 씀
    // 호출하는 쪽에서 주문 행을 잠그고 order.pay()로 상태를 바꾼 뒤 부름 (같은 트랜잭션)
    private void completePayment(User currentUser, Order order, Payment payment, String idempotencyKey,
                                 String fingerprint) {
        // 결제 내역이 이미 있는지 한 번 더 확인 (중복 결제 방지) → 409
        if (paymentRepository.existsByOrderId(order.getId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.ORDER_ALREADY_PAID,
                    "이미 결제된 주문입니다."
            );
        }
        try {
            // 같은 주문을 동시에 결제하면 상태·존재 검사를 둘 다 통과할 수 있음
            // saveAndFlush로 INSERT를 즉시 실행해 order_id unique 제약 위반을 여기서 잡아 409로 변환
            // (토스 결제는 pg_payment_key unique도 같은 방식 — 같은 결제 키가 동시에 두 번 기록되지 않음)
            // TODO(@ManyToOne 전환): order_id unique가 사라지면 이 catch로는 동시 결제를 막을 수 없음
            //  → 부분 유니크 인덱스(WHERE status = 'SUCCESS') 또는 주문 행 비관적 락으로 대체
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.ORDER_ALREADY_PAID,
                    "이미 결제된 주문입니다."
            );
        }

        // 멱등성 기록을 같은 트랜잭션에 저장 (Idempotency-Key가 있을 때만)
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
                        ErrorCode.PAYMENT_IN_PROGRESS,
                        "같은 Idempotency-Key의 결제가 이미 처리되었습니다. 잠시 후 다시 시도해 주세요."
                );
            }
        }

        // 결제 완료 이벤트를 같은 트랜잭션에 저장 (Transactional Outbox, #42)
        // Kafka 발행은 커밋 후 OutboxPublisher가 따로 함 → Kafka 장애여도 결제는 성공, 결제가 롤백되면 이벤트도 남지 않음
        outboxEventRecorder.paymentCompleted(payment);

        // 결제 상태 변경 로그 — 식별자·금액·상태만 기록 (회원 아이디·카드 정보 등 개인정보는 남기지 않음)
        // traceId(MDC)가 함께 찍혀 요청 로그·에러 로그와 연결됨
        log.info("결제 완료 paymentId={}, orderId={}, storeId={}, amount={}, status={}, pg={}",
                payment.getId(), order.getId(), order.getStore().getId(), payment.getAmount(), payment.getStatus(),
                payment.getPgProvider());
    }

    // ===== 토스페이먼츠 (V20, service.TossPaymentService가 순서를 조립) =====
    // 외부 호출(토스 승인)은 트랜잭션 밖에서 하고, 앞뒤의 확인·기록만 여기서 짧은 트랜잭션으로 처리
    // → 토스 응답을 기다리는 동안 DB 커넥션·주문 행 잠금을 잡지 않음

    // 결제창을 열기 전 확인 + 결제창에 넘길 값 — 저장하는 것은 없음
    public TossCheckoutResponse prepareTossCheckout(Long orderId, String username, String clientKey) {
        User currentUser = requireCustomer(username);
        Order order = findOwnOrder(orderId, currentUser, false);
        requirePayableWithoutPayment(order);
        return new TossCheckoutResponse(
                clientKey,
                customerKey(currentUser),
                TOSS_ORDER_ID_PREFIX + order.getId() + "-" + randomHex(),
                order.getTotalPrice(),
                orderName(order));
    }

    // 승인 API를 부르기 전 확인 — 여기서 걸러진 요청은 토스 승인까지 가지 않음
    // (인증만 된 결제는 승인하지 않으면 10분 뒤 만료 → 고객에게 청구되지 않음)
    public void validateTossConfirm(TossConfirmRequest request, String username) {
        User currentUser = requireCustomer(username);
        Order order = findOwnOrder(request.orderId(), currentUser, false);

        // 이 주문에 발급한 형식의 주문번호인지 — 다른 주문의 결제를 이 주문에 붙이지 못하게 함
        Matcher matcher = TOSS_ORDER_ID_PATTERN.matcher(request.tossOrderId());
        if (!matcher.matches() || !matcher.group(1).equals(String.valueOf(order.getId()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PG_ORDER_ID,
                    "이 주문의 결제 주문번호가 아닙니다. 결제를 다시 시작해 주세요.");
        }
        // 금액 대조 — 화면(successUrl)이 보낸 금액은 조작될 수 있으므로 주문 총액과 다르면 승인하지 않음
        if (request.amount() != order.getTotalPrice()) {
            log.warn("토스 승인 금액 불일치 — 승인하지 않음 orderId={}, orderAmount={}, requestAmount={}",
                    order.getId(), order.getTotalPrice(), request.amount());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "결제 금액이 주문 금액과 다릅니다. 결제가 승인되지 않았습니다.");
        }
        requirePayableWithoutPayment(order);
    }

    // 같은 결제 키로 이미 기록된 결제 — 승인 요청 재시도(새로 고침·타임아웃 후 재전송)에 처음 결과를 돌려줌
    // 본인 결제이고 같은 주문일 때만 (다른 사람이 결제 키를 알아도 남의 결제를 볼 수 없음)
    public Optional<PaymentResponse> findTossPayment(String paymentKey, String username, Long orderId) {
        return paymentRepository.findWithOrderByPgPaymentKey(paymentKey)
                .map(payment -> {
                    User currentUser = requireCustomer(username);
                    if (!payment.getOrder().getUser().getId().equals(currentUser.getId())
                            || !payment.getOrder().getId().equals(orderId)) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "본인의 결제만 확인할 수 있습니다.");
                    }
                    return PaymentResponse.atPayment(payment);
                });
    }

    // 토스 승인 결과 기록 — 모의 결제와 같은 규칙(주문 행 잠금 → 상태 전이 → 저장·멱등성 기록·이벤트)
    // 여기서 예외가 나면 결제 행은 없음(롤백) → 호출하는 쪽이 토스 결제를 보상 취소
    @Transactional
    public PaymentResponse recordTossPayment(TossConfirmRequest request, String username, TossPayment approved,
                                             String idempotencyKey, String fingerprint) {
        User currentUser = requireCustomer(username);
        // 승인 호출 동안 고객이 주문을 취소했을 수 있음 → 잠근 뒤 다시 확인 (취소됐으면 409 → 보상 취소)
        Order order = findOwnOrder(request.orderId(), currentUser, true);
        order.pay();

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime approvedAt = approved.approvedAtIn(clock.getZone());
        Payment payment = Payment.approvedByGateway(
                order,
                order.getTotalPrice(),
                order.getStore().effectiveFeeRate(feeProperties.rate()),
                PaymentMethod.CARD,
                now,
                PgProvider.TOSS,
                approved.paymentKey(),
                approved.orderId(),
                approvedAt != null ? approvedAt : now);
        completePayment(currentUser, order, payment, idempotencyKey, fingerprint);
        return PaymentResponse.from(payment);
    }

    private User requireCustomer(String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        // SecurityConfig에서 1차로 막지만, 토큰 발급 후 역할이 바뀐 경우까지 대비 → 403
        if (currentUser.getRole() != Role.CUSTOMER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CUSTOMER만 결제할 수 있습니다.");
        }
        return currentUser;
    }

    private Order findOwnOrder(Long orderId, User currentUser, boolean forUpdate) {
        Optional<Order> found = forUpdate ? orderRepository.findByIdForUpdate(orderId) : orderRepository.findById(orderId);
        Order order = found.orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ORDER_NOT_FOUND, "주문이 없습니다."));
        if (!order.getUser().getId().equals(currentUser.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인의 주문만 결제할 수 있습니다.");
        }
        return order;
    }

    private void requirePayableWithoutPayment(Order order) {
        order.requirePayable(); // 취소됨·이미 결제됨 → 409
        if (paymentRepository.existsByOrderId(order.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_ALREADY_PAID, "이미 결제된 주문입니다.");
        }
    }

    // 결제창에 보이는 주문 이름 — "첫 메뉴 외 N건" (토스 최대 100자)
    private static String orderName(Order order) {
        List<OrderItem> items = order.getOrderItems();
        String name = items.isEmpty() ? "주문 #" + order.getId()
                : items.get(0).getMenuName() + (items.size() > 1 ? " 외 " + (items.size() - 1) + "건" : "");
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    // 토스 customerKey(22~50자) — 회원 id의 해시. 아이디·이메일 등 개인정보를 토스로 보내지 않음
    private static String customerKey(User user) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("delivery-mini-customer:" + user.getId()).getBytes(StandardCharsets.UTF_8));
            return "cust_" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e); // 모든 JVM에 포함된 알고리즘
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

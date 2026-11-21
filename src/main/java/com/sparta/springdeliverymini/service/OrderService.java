package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.dto.OrderCreateRequest;
import com.sparta.springdeliverymini.dto.OrderDetailResponse;
import com.sparta.springdeliverymini.dto.OrderItemRequest;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.dto.KeysetQuery;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.event.OutboxEventRecorder;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class OrderService {

    private final OrderRepository orderRepository;
    private final MenuRepository menuRepository;
    private final UserRepository userRepository;
    private final StoreService storeService;
    private final PaymentRepository paymentRepository;
    private final Clock clock; // 배달완료·결제 취소 시각 기준 (config.TimeConfig, Asia/Seoul)
    private final OutboxEventRecorder outboxEventRecorder; // 배달완료 이벤트 (#42)

    // 주문 목록 조회 — 페이지 단위, id 오름차순 (#40)
    public Slice<OrderResponse> getOrders(String username, PageQuery pageQuery) {
        // 1. 현재 로그인한 사용자 찾기
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. CUSTOMER는 본인의 주문만 조회
        if (currentUser.getRole() == Role.CUSTOMER) {
            return orderRepository.findAllByUserId(currentUser.getId(), pageQuery.toPageable())
                    .map(OrderResponse::from);
        }

        // 3. OWNER는 본인의 모든 매장에 들어온 주문만 조회
        if (currentUser.getRole() == Role.OWNER) {
            return orderRepository.findAllByStoreOwnerId(currentUser.getId(), pageQuery.toPageable())
                    .map(OrderResponse::from);
        }

        return new SliceImpl<>(List.of());
    }

    // 주문 목록 조회 — keyset 페이징 (#54, ?afterId=)
    // 조회 대상·권한·정렬(id 오름차순)은 getOrders와 같고, 위치만 OFFSET 대신 마지막으로 받은 id로 지정
    public Slice<OrderResponse> getOrdersAfter(String username, KeysetQuery keysetQuery) {
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        if (currentUser.getRole() == Role.CUSTOMER) {
            return orderRepository.findAllByUserIdAndIdGreaterThan(
                            currentUser.getId(), keysetQuery.afterId(), keysetQuery.toPageable())
                    .map(OrderResponse::from);
        }

        if (currentUser.getRole() == Role.OWNER) {
            return orderRepository.findAllByStoreOwnerIdAndIdGreaterThan(
                            currentUser.getId(), keysetQuery.afterId(), keysetQuery.toPageable())
                    .map(OrderResponse::from);
        }

        return new SliceImpl<>(List.of());
    }

    // 주문 단건 조회 (#62) — CUSTOMER는 본인 주문, OWNER는 본인 매장에 들어온 주문만
    // 역할·소유권은 토큰이 아니라 DB의 회원·주문 기준으로 판단 (결제 단건 조회 #53과 같은 순서: 404 → 403)
    // 쿼리: 회원 1 + 주문·매장·항목 1 + 결제 1 = 3번 (항목 수와 무관)
    public OrderDetailResponse getOrder(Long orderId, String username) {

        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // SecurityConfig에서 1차로 막지만(ADMIN 403), 토큰 발급 후 역할이 바뀐 경우까지 대비
        if (currentUser.getRole() != Role.CUSTOMER && currentUser.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CUSTOMER 또는 OWNER만 주문을 조회할 수 있습니다.");
        }

        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.ORDER_NOT_FOUND, "주문이 없습니다."));

        // CUSTOMER: 주문자 본인인지 / OWNER: 주문이 들어간 매장의 주인인지 → 아니면 403
        boolean accessible = currentUser.getRole() == Role.CUSTOMER
                ? order.getUser().getId().equals(currentUser.getId())
                : order.getStore().isOwnedBy(currentUser);
        if (!accessible) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인 주문 또는 본인 매장에 들어온 주문만 조회할 수 있습니다.");
        }

        // 결제 전·결제 없이 취소된 주문은 결제가 없음 → payment: null
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        return OrderDetailResponse.of(order, payment);
    }

    // 매장별 주문 조회 — 본인 매장의 주문만 조회 가능
    public Slice<OrderResponse> getStoreOrders(Long storeId, String username, PageQuery pageQuery) {
        // 1. 현재 로그인한 사용자 찾기
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // 2. OWNER인지 확인 (SecurityConfig에서도 막지만, 토큰 발급 후 역할 변경에 대비해 한 번 더 확인)
        if (currentUser.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER만 매장 주문을 조회할 수 있습니다.");
        }

        // 3. 매장 소유권 확인 — 매장이 없으면 404, 다른 OWNER의 매장이면 403
        Store store = storeService.getOwnedStore(storeId, currentUser);

        // 4. 해당 매장에 들어온 주문 조회 (페이지 단위, id 오름차순)
        return orderRepository.findAllByStoreId(store.getId(), pageQuery.toPageable())
                .map(OrderResponse::from);
    }

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request, String username) {

        // 1. 주문자는 요청 본문이 아니라 토큰의 아이디로 찾음
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User customer = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // 2. 같은 메뉴가 여러 항목으로 나뉘어 오면 → 400 (수량으로 합쳐서 요청)
        List<Long> menuIds = request.items().stream().map(OrderItemRequest::menuId).toList();
        Set<Long> distinctMenuIds = new HashSet<>(menuIds);
        if (distinctMenuIds.size() != menuIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.DUPLICATE_ORDER_ITEM, "같은 메뉴는 한 항목으로 합쳐서 주문해주세요.");
        }

        // 3. 주문할 메뉴를 한 번에 조회
        // 하나라도 없거나 삭제된 메뉴 → 404
        Map<Long, Menu> menus = menuRepository.findAllByIdInAndDeletedFalse(distinctMenuIds).stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));
        if (menus.size() != distinctMenuIds.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.MENU_NOT_FOUND, "메뉴가 없습니다.");
        }

        // 4. 모든 메뉴가 같은 매장의 메뉴인지 확인 → 다르면 400
        // 주문의 매장은 요청이 아니라 메뉴의 매장으로 정해짐
        Store store = menus.get(menuIds.get(0)).getStore();
        boolean sameStore = menus.values().stream()
                .allMatch(menu -> menu.getStore().getId().equals(store.getId()));
        if (!sameStore) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MIXED_STORE_ORDER, "한 주문에는 같은 매장의 메뉴만 담을 수 있습니다.");
        }

        // 5. 처음 생성되는 주문은 항상 주문요청 상태
        // 총액 = 항목별 메뉴 가격 × 수량의 합 (Order.addItem에서 계산)
        // 금액은 long(원 단위 정수)으로 계산, long 범위를 넘으면 잘못된 값이 저장되지 않도록 400
        // 배송 주소 형식(우편번호 5자리·길이·제어 문자)은 DeliveryAddress가 검증 → 위반 시 400 INVALID_ADDRESS
        DeliveryAddress address = new DeliveryAddress(request.zipCode(), request.roadAddress(), request.detailAddress());
        Order order = new Order(customer, store, address, request.deliveryRequest(), OrderStatus.ORDER_REQUEST);
        try {
            for (OrderItemRequest item : request.items()) {
                order.addItem(menus.get(item.menuId()), item.quantity());
            }
        } catch (ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.ORDER_AMOUNT_TOO_LARGE, "주문 금액이 너무 큽니다. 수량을 줄여주세요.");
        }

        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public void cancelOrder(Long id, String username) {
        // 1. 현재 로그인한 사용자 조회
        // username으로 User를 찾는다.
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. CUSTOMER인지 확인
        // OWNER는 주문 취소 불가 → 403
        if (currentUser.getRole() != Role.CUSTOMER) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "CUSTOMER만 주문을 취소할 수 있습니다."
            );
        }

        // 3. 주문 조회 (행 잠금)
        // 주문이 존재하지 않으면 → 404
        // 사장님 수락과 동시에 들어와도 한쪽은 앞 요청이 커밋된 뒤의 상태를 읽음 → 수락된 주문이 취소되는 일 없음
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                ErrorCode.ORDER_NOT_FOUND,
                                "주문이 없습니다."
                        )
                );

        // 4. 본인 주문인지 확인
        // 다른 CUSTOMER의 주문이면 → 403
        if (!order.getUser().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "본인의 주문만 취소할 수 있습니다."
            );
        }

        // 5. 주문 취소
        // ORDER_REQUEST / PAYMENT_COMPLETED → ORDER_CANCELLED
        // 주문수락 이후 등 다른 상태라면 Order가 예외 → 409
        boolean paid = order.getStatus() == OrderStatus.PAYMENT_COMPLETED;
        order.cancel();

        // 6. 결제완료 주문이면 결제도 함께 취소 (COMPLETED → CANCELLED, 고객 결제액 전액 취소)
        // 배달 전이라 정산 대상이 된 적이 없으므로 정산에는 영향 없음
        // 실패하면 트랜잭션 전체가 롤백되므로 주문 취소도 함께 취소됨
        if (paid) {
            Payment payment = paymentRepository.findByOrderId(order.getId())
                    .orElseThrow(() -> new IllegalStateException("결제완료 주문에 결제 내역이 없습니다: " + order.getId()));
            PaymentStatus before = payment.getStatus();
            payment.cancel(LocalDateTime.now(clock));
            // 결제 취소 상태 변경 로그 — 식별자·금액·상태만 기록 (회원 아이디 등 개인정보는 남기지 않음)
            log.info("결제 취소 paymentId={}, orderId={}, amount={}, status={}->{}",
                    payment.getId(), order.getId(), payment.getAmount(), before, payment.getStatus());
        }
    }

    @Transactional
    public void statusOrder(
            Long id,
            String username,
            OrderStatus newStatus
    ) {
        // 1. 현재 로그인한 사용자 조회
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. OWNER인지 확인
        // CUSTOMER는 주문 상태 변경 불가 → 403
        if (currentUser.getRole() != Role.OWNER) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "OWNER만 주문을 처리할 수 있습니다."
            );
        }

        // 3. 주문 조회 (행 잠금 — 고객 취소와 동시에 들어와도 한쪽만 성공)
        // 주문이 존재하지 않으면 → 404
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                ErrorCode.ORDER_NOT_FOUND,
                                "주문이 없습니다."
                        )
                );

        // 4. 본인 매장에 들어온 주문인지 확인
        // 다른 OWNER의 매장에 들어온 주문이면 → 403
        if (!order.getStore().isOwnedBy(currentUser)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "본인 매장에 들어온 주문만 처리할 수 있습니다."
            );
        }

        // 5. 요청한 상태에 맞는 전이 실행
        // 현재 상태에서 허용되는지는 Order가 확인 (예: 결제 전 주문 수락, 배달완료 주문 변경 → 409)
        switch (newStatus) {
            case ORDER_ACCEPTED -> order.accept();         // PAYMENT_COMPLETED → ORDER_ACCEPTED
            case DELIVERY_COMPLETED -> {
                order.complete(LocalDateTime.now(clock)); // ORDER_ACCEPTED → DELIVERY_COMPLETED, 배달완료 시각 기록
                // 배달완료 이벤트를 같은 트랜잭션에 저장 (Transactional Outbox, #42) — 상태 전이가 성공한 뒤에만
                outboxEventRecorder.deliveryCompleted(order);
            }
            // OWNER가 요청할 수 없는 상태(주문요청·주문취소·결제완료) → 409
            default -> throw new InvalidOrderStatusException("허용되지 않는 주문 상태 변경입니다.");
        }
    }
}

package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.OrderCreateRequest;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class OrderService {

    private final OrderRepository orderRepository;
    private final MenuRepository menuRepository;
    private final UserRepository userRepository;

    public List<OrderResponse> getOrders(String username) {
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
            return orderRepository.findAllByUserId(currentUser.getId())
                    .stream()
                    .map(OrderResponse::from)
                    .toList();
        }

        // 3. OWNER는 본인의 메뉴에 들어온 주문만 조회
        if (currentUser.getRole() == Role.OWNER) {
            return orderRepository.findAllByMenuOwnerId(currentUser.getId())
                    .stream()
                    .map(OrderResponse::from)
                    .toList();
        }

        return List.of();
    }

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request, String username) {

        // 1. 주문자는 요청 본문이 아니라 토큰의 아이디로 찾음
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User customer = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // 2. 주문할 메뉴 찾기
        // 존재하지 않거나 삭제된 메뉴 → 404
        Menu menu = menuRepository.findByIdAndDeletedFalse(request.menuId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "메뉴가 없습니다."));

        // 3. 총액 = 메뉴 가격 × 수량 (서버에서 계산)
        // int 범위를 넘으면 잘못된 값이 저장되지 않도록 400
        int totalPrice;
        try {
            totalPrice = Math.multiplyExact(menu.getPrice(), request.quantity());
        } catch (ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "주문 금액이 너무 큽니다. 수량을 줄여주세요.");
        }

        // 4. 처음 생성되는 주문은 항상 주문요청 상태
        Order order = new Order(
                menu,
                customer,
                request.quantity(),
                totalPrice,
                request.deliveryAddress(),
                OrderStatus.ORDER_REQUEST
        );

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

        // 3. 주문 조회
        // 주문이 존재하지 않으면 → 404
        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
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
        // ORDER_REQUEST → ORDER_CANCELLED
        // 결제완료된 주문 등 다른 상태라면 Order가 예외 → 409
        order.cancel();
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

        // 3. 주문 조회
        // 주문이 존재하지 않으면 → 404
        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "주문이 없습니다."
                        )
                );

        // 4. 본인 메뉴에 들어온 주문인지 확인
        // 다른 OWNER의 메뉴에 들어온 주문이면 → 403
        if (!order.getMenu().getOwner().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "본인 메뉴에 들어온 주문만 처리할 수 있습니다."
            );
        }

        // 5. 요청한 상태에 맞는 전이 실행
        // 현재 상태에서 허용되는지는 Order가 확인 (예: 결제 전 주문 수락, 배달완료 주문 변경 → 409)
        switch (newStatus) {
            case ORDER_ACCEPTED -> order.accept();         // PAYMENT_COMPLETED → ORDER_ACCEPTED
            case DELIVERY_COMPLETED -> order.complete();   // ORDER_ACCEPTED → DELIVERY_COMPLETED
            // OWNER가 요청할 수 없는 상태(주문요청·주문취소·결제완료) → 409
            default -> throw new InvalidOrderStatusException("허용되지 않는 주문 상태 변경입니다.");
        }
    }
}

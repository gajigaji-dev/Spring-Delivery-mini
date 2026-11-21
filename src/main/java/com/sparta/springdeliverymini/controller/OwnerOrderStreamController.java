package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.OwnerStreamProperties;
import com.sparta.springdeliverymini.event.OwnerOrderStreamRegistry;
import com.sparta.springdeliverymini.service.OwnerOrderStreamService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class OwnerOrderStreamController {

    private final OwnerOrderStreamService ownerOrderStreamService;
    private final OwnerOrderStreamRegistry registry;
    private final OwnerStreamProperties properties;

    // 사장님 실시간 주문 알림 구독 (#76) — text/event-stream
    // 이벤트: connected(연결 직후 1번) / order(새 주문·환불, 본문 OwnerOrderEventResponse) / 주석 ping(하트비트)
    // storeId 없으면 본인의 모든 매장, 있으면 그 매장만 (본인 매장이 아니면 403, 없으면 404)
    // 연결은 owner-stream.timeout 후 서버가 정상 종료 → 클라이언트가 새 토큰으로 다시 연결
    @GetMapping(value = "/api/owner/order-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam(required = false) Long storeId, Authentication authentication,
                                HttpServletResponse response) {
        Long userId = ownerOrderStreamService.authorize(authentication.getName(), storeId);
        // 프록시(nginx 등)가 응답을 모아 두지 않고 바로 흘려보내도록
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        return registry.register(userId, storeId, new SseEmitter(properties.timeout().toMillis()));
    }
}

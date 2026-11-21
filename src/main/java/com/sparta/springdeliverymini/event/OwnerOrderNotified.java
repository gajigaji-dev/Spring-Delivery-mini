package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.dto.OwnerOrderEventResponse;

// 사장님 주문 알림을 저장했다는 애플리케이션 이벤트 (#76) — NotificationService가 발행, OwnerOrderStreamRegistry가 커밋 후 전송
// ownerId: 받을 사장님(이벤트 발생 시점의 매장 주인), payload.storeId로 연결별 매장 범위를 한 번 더 거름
public record OwnerOrderNotified(Long ownerId, OwnerOrderEventResponse payload) {
}

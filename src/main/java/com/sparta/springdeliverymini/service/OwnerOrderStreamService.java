package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사장님 실시간 주문 알림(SSE, #76) 구독 전 권한 확인 — DB 기준 역할·매장 소유권
// 구독 경로는 open-in-view에서 제외되어 있어(config.OpenEntityManagerInViewConfig) 이 트랜잭션이 끝나면
// 커넥션을 바로 돌려줌 → 연결이 열려 있는 동안 DB 커넥션을 잡지 않음. 그래서 엔티티가 아니라 id만 반환
@Service
@RequiredArgsConstructor
public class OwnerOrderStreamService {

    private final UserRepository userRepository;
    private final StoreService storeService;

    // 구독할 사장님의 id — storeId가 있으면 본인 매장인지도 확인 (없으면 404, 다른 사장님 매장 403)
    @Transactional(readOnly = true)
    public Long authorize(String username, Long storeId) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        // SecurityConfig에서도 막지만, 토큰 발급 후 역할 변경에 대비해 DB 기준으로 한 번 더 확인
        if (currentUser.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER만 실시간 주문 알림을 구독할 수 있습니다.");
        }
        if (storeId != null) {
            storeService.getOwnedStore(storeId, currentUser);
        }
        return currentUser.getId();
    }
}

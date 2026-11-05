package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.StoreCreateRequest;
import com.sparta.springdeliverymini.dto.StoreResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class StoreService {

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional
    public StoreResponse createStore(StoreCreateRequest request, String username) {
        // 1. 점주는 요청 본문이 아니라 토큰의 아이디로 찾음
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User owner = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // 2. OWNER인지 확인 (SecurityConfig에서도 막지만, 토큰 발급 후 역할 변경에 대비해 한 번 더 확인)
        if (owner.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER만 매장을 생성할 수 있습니다.");
        }

        // 3. 매장 생성 후 저장
        Store store = new Store(request.name(), owner);
        return StoreResponse.from(storeRepository.save(store));
    }

    // 전체 매장 목록 (로그인 없이 조회 가능)
    public List<StoreResponse> getStores() {
        return storeRepository.findAll()
                .stream()
                .map(StoreResponse::from)
                .toList();
    }

    // 매장 상세 조회 — 존재하지 않으면 404
    public StoreResponse getStore(Long id) {
        return StoreResponse.from(findStore(id));
    }

    // 매장 조회 + 소유권 검증 — 매장 소유권이 필요한 다른 Service(메뉴 등록, 매장별 주문 조회)에서 공통으로 사용
    // 매장이 없으면 404, 다른 점주의 매장이면 403
    public Store getOwnedStore(Long storeId, User user) {
        Store store = findStore(storeId);
        if (!store.isOwnedBy(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인 매장이 아닙니다.");
        }
        return store;
    }

    private Store findStore(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "매장이 없습니다."));
    }
}

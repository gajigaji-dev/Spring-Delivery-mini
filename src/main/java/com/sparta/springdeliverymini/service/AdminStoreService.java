package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.config.PaymentFeeProperties;
import com.sparta.springdeliverymini.dto.AdminStoreFeeRateResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 관리자 매장 관리 — 매장별 수수료율 설정·해제 (docs/money-policy.md)
// - 바뀐 수수료율은 이후 결제부터 적용 (결제 시점의 수수료율이 Payment.feeRate에 스냅샷으로 저장됨)
// - 이미 저장된 결제·정산에는 손대지 않음 → 확정·지급된 정산 금액은 바뀌지 않음
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class AdminStoreService {

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final PaymentFeeProperties feeProperties; // 공통 수수료율 (응답의 실제 적용 수수료율 계산용)

    // 매장 수수료율 설정 — 범위·자릿수 검증은 요청 DTO(400)와 Store.changeFeeRate(최종 보장)
    @Transactional
    public AdminStoreFeeRateResponse changeFeeRate(Long storeId, BigDecimal feeRate, String username) {
        validateAdmin(username);
        Store store = findStore(storeId);
        store.changeFeeRate(feeRate);
        return AdminStoreFeeRateResponse.of(store, feeProperties.rate());
    }

    // 매장 수수료율 해제 → 이후 결제는 공통 수수료율 사용 (이미 해제된 매장이어도 그대로 성공)
    @Transactional
    public AdminStoreFeeRateResponse clearFeeRate(Long storeId, String username) {
        validateAdmin(username);
        Store store = findStore(storeId);
        store.clearFeeRate();
        return AdminStoreFeeRateResponse.of(store, feeProperties.rate());
    }

    private Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "매장이 없습니다."));
    }

    // SecurityConfig에서도 ADMIN만 허용하지만, 토큰 발급 후 역할이 바뀐 경우에 대비해 DB 기준으로 한 번 더 확인
    private void validateAdmin(String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 수수료율을 변경할 수 있습니다.");
        }
    }
}

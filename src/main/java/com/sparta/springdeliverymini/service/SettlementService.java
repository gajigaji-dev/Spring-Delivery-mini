package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.StoreSettlementResponse;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 사장님 정산 조회 — 정산 기준은 docs/settlement-policy.md
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final StoreService storeService;
    private final Clock clock; // 확정 여부 판단 기준 (config.TimeConfig, Asia/Seoul)

    // 매장별 기간 정산 — 본인 매장의 확정된 날짜만 조회 가능
    public StoreSettlementResponse getStoreSettlement(Long storeId, LocalDate from, LocalDate to, String username) {
        // 1. 현재 로그인한 사용자 찾기
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));

        // 2. OWNER인지 확인 (SecurityConfig에서도 막지만, 토큰 발급 후 역할 변경에 대비해 한 번 더 확인)
        if (currentUser.getRole() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER만 정산을 조회할 수 있습니다.");
        }

        // 3. 매장 소유권 확인 — 매장이 없으면 404, 다른 OWNER의 매장이면 403
        Store store = storeService.getOwnedStore(storeId, currentUser);

        // 4. 기간 검증 — 시작일 > 종료일, 31일 초과, 확정 전 날짜 포함 → 400
        SettlementPeriod period;
        try {
            period = SettlementPeriod.of(from, to, LocalDateTime.now(clock));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 5. 판매: 배달완료 시각이 [from 00:00, to+1 00:00)인 결제 (나중에 환불돼도 포함 → 조회 금액 불변)
        //    환불: 환불 시각이 같은 구간인 결제
        List<Payment> sales = paymentRepository.findSettlementTargets(store.getId(), period.start(), period.end());
        List<Payment> refunds = paymentRepository.findRefundsInPeriod(store.getId(), period.start(), period.end());
        return StoreSettlementResponse.of(store, period, sales, refunds);
    }
}

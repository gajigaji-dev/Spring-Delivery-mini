package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.dto.SettlementResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.SettlementRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 관리자 정산 관리 — 조회, 확정(PENDING → CONFIRMED), 지급(CONFIRMED → PAID)
// 상태 전이 규칙은 Settlement 엔티티가 판단 (위반 시 InvalidSettlementStatusException → 409)
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class AdminSettlementService {

    private final SettlementRepository settlementRepository;
    private final UserRepository userRepository;
    private final Clock clock; // 확정·지급 시각 기준 (config.TimeConfig, Asia/Seoul)

    // 날짜별 정산 목록 — status가 있으면 그 상태만
    public List<SettlementResponse> getSettlements(LocalDate date, SettlementStatus status, String username) {
        validateAdmin(username);
        List<Settlement> settlements = (status == null)
                ? settlementRepository.findAllBySettlementDateOrderById(date)
                : settlementRepository.findAllBySettlementDateAndStatusOrderById(date, status);
        return settlements.stream().map(SettlementResponse::from).toList();
    }

    // 정산 확정 — PENDING → CONFIRMED, 확정 시각 기록
    @Transactional
    public SettlementResponse confirm(Long settlementId, String username) {
        validateAdmin(username);
        Settlement settlement = findForUpdate(settlementId);
        SettlementStatus before = settlement.getStatus();
        settlement.confirm(LocalDateTime.now(clock));
        log.info("정산 확정 settlementId={}, storeId={}, settlementDate={}, settlementAmount={}, status={}->{}",
                settlement.getId(), settlement.getStore().getId(), settlement.getSettlementDate(),
                settlement.getSettlementAmount(), before, settlement.getStatus());
        return SettlementResponse.from(settlement);
    }

    // 정산 지급 — CONFIRMED → PAID, 지급 시각 기록
    // 이미 지급된 정산이면 409 (지급 시각은 처음 값 유지)
    @Transactional
    public SettlementResponse pay(Long settlementId, String username) {
        validateAdmin(username);
        Settlement settlement = findForUpdate(settlementId);
        SettlementStatus before = settlement.getStatus();
        settlement.markPaid(LocalDateTime.now(clock));
        // 지급 상태 변경 로그 — 송금 연동 전이라 지급 이력 추적의 1차 근거 (계좌 등 개인정보는 다루지 않음)
        log.info("정산 지급 settlementId={}, storeId={}, settlementDate={}, settlementAmount={}, status={}->{}",
                settlement.getId(), settlement.getStore().getId(), settlement.getSettlementDate(),
                settlement.getSettlementAmount(), before, settlement.getStatus());
        return SettlementResponse.from(settlement);
    }

    // SecurityConfig에서도 ADMIN만 허용하지만, 토큰 발급 후 역할이 바뀐 경우에 대비해 DB 기준으로 한 번 더 확인
    private void validateAdmin(String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 정산을 관리할 수 있습니다.");
        }
    }

    // 상태를 바꿀 정산은 행 잠금으로 조회 → 동시에 같은 정산을 확정·지급해도 한 번만 성공
    private Settlement findForUpdate(Long settlementId) {
        return settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "정산이 없습니다."));
    }
}

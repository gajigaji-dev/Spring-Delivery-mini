package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.dto.SettlementAggregationFailureResponse;
import com.sparta.springdeliverymini.dto.SettlementReaggregationResponse;
import com.sparta.springdeliverymini.dto.SettlementResponse;
import com.sparta.springdeliverymini.dto.SettlementStatusHistoryResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementAggregationFailure;
import com.sparta.springdeliverymini.entity.SettlementAggregationFailureStatus;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.entity.SettlementStatusHistory;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import com.sparta.springdeliverymini.repository.SettlementAggregationFailureRepository;
import com.sparta.springdeliverymini.repository.SettlementRepository;
import com.sparta.springdeliverymini.repository.SettlementStatusHistoryRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.service.SettlementAggregationService.Outcome;
import com.sparta.springdeliverymini.service.SettlementAggregationService.StoreResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 관리자 정산 관리 — 조회, 확정(PENDING → CONFIRMED), 지급(CONFIRMED → PAID), 누락·실패분 재집계
// 상태 전이 규칙은 Settlement 엔티티가 판단 (위반 시 InvalidSettlementStatusException → 409)
//
// 감사 기록 (#39, docs/settlement-policy.md 10장)
// - 확정·지급할 때마다 상태 변경 이력(SettlementStatusHistory)을 같은 트랜잭션에서 저장
// - 처리자는 요청 값이 아니라 validateAdmin이 DB에서 조회한 인증된 관리자 → 다른 사람 이름으로 기록할 방법이 없음
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class AdminSettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    private final SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final SettlementAggregationService settlementAggregationService;
    private final Clock clock; // 확정·지급 시각 기준 (config.TimeConfig, Asia/Seoul)

    // 날짜별 정산 목록 — status가 있으면 그 상태만
    public List<SettlementResponse> getSettlements(LocalDate date, SettlementStatus status, String username) {
        validateAdmin(username);
        List<Settlement> settlements = (status == null)
                ? settlementRepository.findAllBySettlementDateOrderById(date)
                : settlementRepository.findAllBySettlementDateAndStatusOrderById(date, status);

        // 이력은 정산마다 따로 조회하지 않고 한 번에 가져와 정산별로 묶음 (N+1 방지)
        Map<Long, List<SettlementStatusHistory>> histories = settlements.isEmpty() ? Map.of()
                : settlementStatusHistoryRepository
                .findAllWithActor(settlements.stream().map(Settlement::getId).toList()).stream()
                .collect(Collectors.groupingBy(history -> history.getSettlement().getId()));
        return settlements.stream()
                .map(settlement -> SettlementResponse.from(settlement, histories.getOrDefault(settlement.getId(), List.of())))
                .toList();
    }

    // 정산 1건의 상태 변경 이력 (기록 순) — V11 전에 일어난 전이는 이력이 없음
    public List<SettlementStatusHistoryResponse> getHistories(Long settlementId, String username) {
        validateAdmin(username);
        if (!settlementRepository.existsById(settlementId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "정산이 없습니다.");
        }
        return settlementStatusHistoryRepository.findAllWithActor(List.of(settlementId)).stream()
                .map(SettlementStatusHistoryResponse::from)
                .toList();
    }

    // 집계 실패 기록 목록 (최근 실패 순) — status가 있으면 그 상태만 (FAILED = 재집계 대상)
    public List<SettlementAggregationFailureResponse> getAggregationFailures(
            SettlementAggregationFailureStatus status, String username) {
        validateAdmin(username);
        List<SettlementAggregationFailure> failures = (status == null)
                ? settlementAggregationFailureRepository.findAllByOrderByLastFailedAtDescIdDesc()
                : settlementAggregationFailureRepository.findAllByStatusOrderByLastFailedAtDescIdDesc(status);
        return failures.stream().map(SettlementAggregationFailureResponse::from).toList();
    }

    // 정산 확정 — PENDING → CONFIRMED, 확정 시각·처리자 기록
    @Transactional
    public SettlementResponse confirm(Long settlementId, String username) {
        User admin = validateAdmin(username);
        Settlement settlement = findForUpdate(settlementId);
        SettlementStatus before = settlement.getStatus();
        settlement.confirm(LocalDateTime.now(clock));
        settlementStatusHistoryRepository.save(SettlementStatusHistory.confirmed(settlement, admin));
        log.info("정산 확정 settlementId={}, storeId={}, settlementDate={}, settlementAmount={}, status={}->{}, actorId={}",
                settlement.getId(), settlement.getStore().getId(), settlement.getSettlementDate(),
                settlement.getSettlementAmount(), before, settlement.getStatus(), admin.getId());
        return toResponse(settlement);
    }

    // 정산 지급 — CONFIRMED → PAID, 지급 시각·처리자 기록
    // 이미 지급된 정산이면 409 (지급 시각·이력은 처음 값 유지)
    @Transactional
    public SettlementResponse pay(Long settlementId, String username) {
        User admin = validateAdmin(username);
        Settlement settlement = findForUpdate(settlementId);
        SettlementStatus before = settlement.getStatus();
        settlement.markPaid(LocalDateTime.now(clock));
        settlementStatusHistoryRepository.save(SettlementStatusHistory.paid(settlement, admin));
        // 지급 상태 변경 로그 — 송금 연동 전이라 지급 이력 추적의 보조 근거 (계좌 등 개인정보는 다루지 않음)
        log.info("정산 지급 settlementId={}, storeId={}, settlementDate={}, settlementAmount={}, status={}->{}, actorId={}",
                settlement.getId(), settlement.getStore().getId(), settlement.getSettlementDate(),
                settlement.getSettlementAmount(), before, settlement.getStatus(), admin.getId());
        return toResponse(settlement);
    }

    // 누락·실패분 재집계 — 정산이 없는 매장·날짜만 새로 집계 (기존 정산은 상태와 관계없이 다시 계산하지 않음)
    // - storeId 없음: 그 날짜의 집계 대상 매장 전체. 이미 정산이 있는 매장은 건너뜀 → 여러 번 요청해도 결과가 같음
    // - storeId 있음: 그 매장만. 이미 정산이 있으면 409 (PENDING·CONFIRMED·PAID 모두 재집계 불가)
    // - 실제 집계는 스케줄러와 같은 SettlementAggregationService → 매장 행 잠금·UNIQUE로 동시 실행에도 중복 없음
    //
    // 트랜잭션을 걸지 않음(NOT_SUPPORTED): 집계는 매장마다 따로 커밋해야 하는데,
    // 여기서 트랜잭션을 열면 그 안에 합쳐져 한 매장의 실패가 전체를 롤백시키고 클래스 기본값(readOnly)도 물려받음
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SettlementReaggregationResponse reaggregate(LocalDate date, Long storeId, String username) {
        User admin = validateAdmin(username);

        LocalDate lastClosed = SettlementPeriod.lastConfirmedDate(LocalDateTime.now(clock));
        if (date.isAfter(lastClosed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "아직 집계할 수 없는 날짜입니다: " + date + " (" + lastClosed + "까지 집계 가능)");
        }
        if (storeId != null && !storeRepository.existsById(storeId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "매장이 없습니다.");
        }

        List<StoreResult> results = settlementAggregationService.reaggregate(date, storeId, admin);

        // 매장을 지정했는데 이미 정산이 있으면 잘못된 대상 → 조용히 건너뛰지 않고 현재 상태와 함께 거부
        if (storeId != null && results.stream().anyMatch(result -> result.outcome() == Outcome.ALREADY_EXISTS)) {
            String status = settlementRepository.findByStoreIdAndSettlementDate(storeId, date)
                    .map(settlement -> settlement.getStatus().name())
                    .orElse("알 수 없음");
            throw new InvalidSettlementStatusException(
                    "이미 정산이 있는 매장·날짜는 재집계할 수 없습니다. 현재 상태: " + status);
        }

        SettlementReaggregationResponse response = SettlementReaggregationResponse.of(date, results);
        log.info("정산 재집계 date={}, storeId={}, created={}, skipped={}, failed={}, actorId={}",
                date, storeId, response.created(), response.skipped(), response.failed(), admin.getId());
        return response;
    }

    // SecurityConfig에서도 ADMIN만 허용하지만, 토큰 발급 후 역할이 바뀐 경우에 대비해 DB 기준으로 한 번 더 확인
    // 확인한 관리자를 돌려줌 → 감사 기록의 처리자로 사용
    private User validateAdmin(String username) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 정산을 관리할 수 있습니다.");
        }
        return currentUser;
    }

    // 상태를 바꿀 정산은 행 잠금으로 조회 → 동시에 같은 정산을 확정·지급해도 한 번만 성공
    private Settlement findForUpdate(Long settlementId) {
        return settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "정산이 없습니다."));
    }

    // 방금 저장한 이력까지 포함해 응답 (조회 전에 자동 flush되어 새 이력도 함께 읽힘)
    private SettlementResponse toResponse(Settlement settlement) {
        return SettlementResponse.from(settlement,
                settlementStatusHistoryRepository.findAllWithActor(List.of(settlement.getId())));
    }
}

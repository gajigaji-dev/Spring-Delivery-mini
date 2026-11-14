package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.*;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

// 일 정산 집계 — 하루(D)치 정산을 매장별로 집계해 PENDING(확정 대기) 정산으로 저장 (docs/settlement-policy.md)
// 확정·지급은 관리자가 AdminSettlementService로 처리
//
// 정산 1건의 구성 (9장)
//   판매: D일에 배달완료된 결제
//   환불: D+1 00:00 전에 환불됐고 아직 어떤 정산에서도 차감되지 않은 환불 (settlement_refunds로 연결)
//   이월: D보다 앞 날짜 정산에서 넘어왔고 아직 차감되지 않은 이월분 (settlement_carryovers로 연결)
//
// 트랜잭션: 매장 1곳 = 트랜잭션 1개 (TransactionTemplate)
//   → 한 매장에서 실패해도 다른 매장의 집계는 그대로 커밋되고, 실패한 매장은 다음 실행에서 다시 시도됨
//   → 정산 행과 환불·이월 연결 기록은 함께 커밋되거나 함께 롤백됨 (정산만 남고 연결이 빠지는 일 없음)
//   (같은 클래스 안의 @Transactional 메서드를 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로 TransactionTemplate 사용)
//
// 중복 정산·중복 차감 방지
//   1. 매장 행 잠금 → 같은 매장의 집계(다른 날짜 포함)는 한 번에 하나씩, 미차감 환불·이월을 두 집계가 함께 읽지 않음
//   2. 집계 전에 이미 정산이 있는 매장·날짜인지 확인 → 건너뜀 (상태와 관계없이, 기존 정산 행은 절대 수정하지 않음)
//   3. DB UNIQUE — (store_id, settlement_date), settlement_refunds.payment_id, settlement_carryovers.from_settlement_id
//      1·2를 모두 우회해도 두 번째 저장은 거부됨
//
// 실패 기록·재집계 (#39, 10장)
//   - 매장 집계가 실패하면 롤백 후 별도 트랜잭션으로 settlement_aggregation_failures에 FAILED 기록
//     → 이후 스케줄러 재실행이든 관리자 재집계든 성공하면 같은 트랜잭션에서 RESOLVED로 바꿈
//   - 관리자 재집계(reaggregate)는 스케줄러와 같은 aggregateStore를 그대로 사용
//     → 잠금·중복 방지가 같으므로 스케줄러와 동시에 돌거나 여러 번 요청해도 정산은 매장·날짜당 1건
//   - 정산이 만들어지면 상태 이력(→ PENDING)을 같은 트랜잭션에서 저장 (처리자: 재집계한 관리자, 스케줄러면 없음)
@Slf4j
@Service
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SettlementAggregationService {

    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final StoreRepository storeRepository;
    private final SettlementRefundRepository settlementRefundRepository;
    private final SettlementCarryoverRepository settlementCarryoverRepository;
    private final SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    private final SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock; // 집계 가능 여부 판단 기준 (config.TimeConfig, Asia/Seoul)

    // 하루치 결과 — created: 새로 집계, skipped: 이미 정산이 있거나 대상이 없어 건너뜀, failed: 오류로 집계하지 못함(실패 기록에 남음)
    public record DailyResult(LocalDate date, int created, int skipped, int failed) {
    }

    // 매장 1곳의 집계 결과
    public enum Outcome {
        CREATED,        // 새 정산을 만듦
        ALREADY_EXISTS, // 이미 정산이 있어 건너뜀 (상태와 관계없이 기존 정산은 건드리지 않음)
        NO_TARGET,      // 판매·미차감 환불이 없어 만들 정산이 없음
        FAILED          // 오류로 롤백됨 (실패 기록에 남음)
    }

    // settlementId: CREATED일 때만, error: FAILED일 때만
    public record StoreResult(Long storeId, Outcome outcome, Long settlementId, String error) {
    }

    // date 하루를 매장별로 집계 (스케줄러)
    // 아직 집계 가능 시각(date+1 01:00) 전이면 IllegalArgumentException — 집계 후 금액이 바뀌는 일을 막음
    public DailyResult aggregateDaily(LocalDate date) {
        List<StoreResult> results = aggregate(date, null, null);
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (StoreResult result : results) {
            switch (result.outcome()) {
                case CREATED -> created++;
                case FAILED -> failed++;
                default -> skipped++;
            }
        }
        return new DailyResult(date, created, skipped, failed);
    }

    // 관리자 수동 재집계 — 정산이 없는 매장·날짜만 새로 집계 (실패했거나 catch-up 기간을 넘겨 누락된 경우)
    // - storeId가 null이면 그 날짜의 집계 대상 매장 전체, 있으면 그 매장만
    // - admin: 인증된 관리자 (AdminSettlementService가 DB에서 확인한 User) → 이력·실패 기록의 처리자
    // - 이미 정산이 있는 매장은 ALREADY_EXISTS로 건너뜀 → 여러 번 요청해도 결과가 같고, 확정·지급된 정산은 바뀌지 않음
    public List<StoreResult> reaggregate(LocalDate date, Long storeId, User admin) {
        if (admin == null) {
            throw new IllegalArgumentException("재집계에는 처리자가 필요합니다.");
        }
        return aggregate(date, storeId, admin);
    }

    private List<StoreResult> aggregate(LocalDate date, Long onlyStoreId, User actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        SettlementPeriod period = SettlementPeriod.of(date, date, now);

        Collection<Long> storeIds;
        if (onlyStoreId != null) {
            storeIds = List.of(onlyStoreId);
        } else {
            // 판매가 있는 매장 ∪ 미차감 환불이 있는 매장
            TreeSet<Long> targets = new TreeSet<>(paymentRepository.findSettlementStoreIds(period.start(), period.end()));
            targets.addAll(paymentRepository.findUnsettledRefundStoreIds(period.end()));
            storeIds = targets;
        }

        List<StoreResult> results = new ArrayList<>();
        for (Long storeId : storeIds) {
            try {
                results.add(aggregateStore(storeId, period, now, actor));
            } catch (RuntimeException e) {
                // 한 매장의 오류가 나머지 매장의 집계를 막지 않도록 기록만 하고 계속 진행
                log.error("정산 집계 실패 storeId={}, date={}", storeId, date, e);
                String error = describe(e);
                recordFailure(storeId, date, error, actor, now);
                results.add(new StoreResult(storeId, Outcome.FAILED, null, error));
            }
        }
        return results;
    }

    // 매장 1곳 집계 (트랜잭션 1개)
    private StoreResult aggregateStore(Long storeId, SettlementPeriod period, LocalDateTime now, User actor) {
        try {
            return transactionTemplate.execute(status -> {
                // 1. 매장 행 잠금 — 이후 조회는 앞선 같은 매장 집계가 커밋한 결과를 봄
                Store store = storeRepository.findByIdForUpdate(storeId)
                        .orElseThrow(() -> new IllegalStateException("매장이 없습니다: " + storeId));

                // 2. 이미 정산이 있으면 건너뜀 (기존 정산은 PENDING이라도 수정하지 않음 → 늦게 들어온 환불은 다음 정산에서 차감)
                if (settlementRepository.existsByStoreIdAndSettlementDate(storeId, period.from())) {
                    return new StoreResult(storeId, Outcome.ALREADY_EXISTS, null, null);
                }

                // 3. 판매·미차감 환불 — 둘 다 없으면 만들 정산이 없음 (이월분만 남은 매장은 다음 판매·환불 때 차감)
                List<Payment> sales = paymentRepository.findSettlementTargets(storeId, period.start(), period.end());
                List<Payment> refunds = paymentRepository.findUnsettledRefunds(storeId, period.end());
                if (sales.isEmpty() && refunds.isEmpty()) {
                    // 앞서 실패한 기록이 있었다면 더 집계할 것이 없음을 확인했으므로 해결 처리
                    resolveFailure(storeId, period.from(), null, actor, now);
                    return new StoreResult(storeId, Outcome.NO_TARGET, null, null);
                }

                // 4. 미차감 이월분 — 이 날짜보다 앞 날짜 정산에서 넘어온 것만
                List<Settlement> carrySources = settlementRepository.findUnsettledCarryovers(storeId, period.from());
                long carriedIn = 0;
                for (Settlement source : carrySources) {
                    carriedIn = Math.addExact(carriedIn, source.getCarriedOutAmount());
                }

                // 5. 정산 저장 (트랜잭션 안에서 INSERT를 실행해 UNIQUE 위반을 여기서 받음)
                Settlement settlement = Settlement.aggregate(store, period.from(),
                        SettlementTotals.of(sales), SettlementTotals.of(refunds), carriedIn, now);
                settlementRepository.saveAndFlush(settlement);

                // 6. 차감 연결 기록 — 정산의 환불 합계·이월받은 금액과 정확히 같은 대상
                settlementRefundRepository.saveAll(
                        refunds.stream().map(refund -> new SettlementRefund(settlement, refund)).toList());
                settlementCarryoverRepository.saveAll(
                        carrySources.stream().map(source -> new SettlementCarryover(source, settlement)).toList());

                // 7. 감사 이력(→ PENDING)과 실패 기록 해결 — 정산과 함께 커밋되거나 함께 롤백
                settlementStatusHistoryRepository.save(SettlementStatusHistory.aggregated(settlement, actor, now));
                resolveFailure(storeId, period.from(), settlement, actor, now);
                settlementRefundRepository.flush();
                return new StoreResult(storeId, Outcome.CREATED, settlement.getId(), null);
            });
        } catch (DataIntegrityViolationException e) {
            // 동시에 실행된 다른 집계가 같은 매장·날짜를 먼저 저장한 경우만 중복으로 보고 건너뜀
            // 다른 제약 위반(연결 기록 UNIQUE, 금액 CHECK 등)이면 그대로 던져 실패로 기록 → 전체 롤백 후 다음 실행에서 재시도
            if (settlementRepository.existsByStoreIdAndSettlementDate(storeId, period.from())) {
                return new StoreResult(storeId, Outcome.ALREADY_EXISTS, null, null);
            }
            throw e;
        }
    }

    // 이 매장·날짜에 미해결 실패 기록이 있으면 해결 처리 (매장 행 잠금을 잡은 집계 트랜잭션 안에서 호출)
    private void resolveFailure(Long storeId, LocalDate date, Settlement settlement, User actor, LocalDateTime now) {
        settlementAggregationFailureRepository.findByStoreIdAndSettlementDate(storeId, date)
                .filter(SettlementAggregationFailure::isFailed)
                .ifPresent(failure -> failure.resolve(settlement, actor, now));
    }

    // 실패 기록 — 집계 트랜잭션은 이미 롤백됐으므로 새 트랜잭션으로 저장
    // - 매장 행 잠금을 잡고 정산이 없는 것을 다시 확인: 그 사이 다른 실행이 집계에 성공했다면 실패로 남기지 않음
    //   (잠금 덕분에 "정산이 있는데 FAILED인 기록"이 생기지 않고, 같은 행을 두 실행이 동시에 갱신하지도 않음)
    // - 기록 자체가 실패해도(DB 장애 등) 집계 흐름은 계속 → 이 경우는 로그만 남음
    private void recordFailure(Long storeId, LocalDate date, String error, User actor, LocalDateTime now) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Store store = storeRepository.findByIdForUpdate(storeId).orElse(null);
                if (store == null || settlementRepository.existsByStoreIdAndSettlementDate(storeId, date)) {
                    return;
                }
                settlementAggregationFailureRepository.findByStoreIdAndSettlementDate(storeId, date)
                        .ifPresentOrElse(
                                failure -> failure.recordFailure(error, actor, now),
                                () -> settlementAggregationFailureRepository.save(
                                        new SettlementAggregationFailure(store, date, error, actor, now)));
            });
        } catch (RuntimeException e) {
            log.error("정산 집계 실패 기록 저장 실패 storeId={}, date={}", storeId, date, e);
        }
    }

    // 실패 원인 요약 — DB에 저장되고 관리자 응답에 나감
    // DB 오류 상세(Detail: Key (...)=(...))에는 실제 입력값이 들어 있을 수 있어 예외 종류와 제약 조건 이름만 남김
    // (GlobalExceptionHandler의 제약 위반 로그와 같은 기준). 전체 원인은 traceId로 서버 로그에서 확인
    private static String describe(RuntimeException e) {
        String type = e.getClass().getSimpleName();
        if (e instanceof DataAccessException) {
            return (e.getCause() instanceof ConstraintViolationException cve && cve.getConstraintName() != null)
                    ? type + " constraint=" + cve.getConstraintName()
                    : type;
        }
        return e.getMessage() == null ? type : type + ": " + e.getMessage();
    }
}

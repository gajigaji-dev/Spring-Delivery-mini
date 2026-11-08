package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
@Slf4j
@Service
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SettlementAggregationService {

    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final StoreRepository storeRepository;
    private final SettlementRefundRepository settlementRefundRepository;
    private final SettlementCarryoverRepository settlementCarryoverRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock; // 집계 가능 여부 판단 기준 (config.TimeConfig, Asia/Seoul)

    // 하루치 결과 — created: 새로 집계, skipped: 이미 정산이 있거나 대상이 없어 건너뜀, failed: 오류로 집계하지 못함(다음 실행에서 재시도)
    public record DailyResult(LocalDate date, int created, int skipped, int failed) {
    }

    // date 하루를 매장별로 집계
    // 아직 집계 가능 시각(date+1 01:00) 전이면 IllegalArgumentException — 집계 후 금액이 바뀌는 일을 막음
    public DailyResult aggregateDaily(LocalDate date) {
        LocalDateTime now = LocalDateTime.now(clock);
        SettlementPeriod period = SettlementPeriod.of(date, date, now);

        // 판매가 있는 매장 ∪ 미차감 환불이 있는 매장
        TreeSet<Long> storeIds = new TreeSet<>(paymentRepository.findSettlementStoreIds(period.start(), period.end()));
        storeIds.addAll(paymentRepository.findUnsettledRefundStoreIds(period.end()));

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Long storeId : storeIds) {
            try {
                if (aggregateStore(storeId, period, now)) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // 한 매장의 오류가 나머지 매장의 집계를 막지 않도록 기록만 하고 계속 진행
                log.error("정산 집계 실패 storeId={}, date={}", storeId, date, e);
                failed++;
            }
        }
        return new DailyResult(date, created, skipped, failed);
    }

    // 매장 1곳 집계 (트랜잭션 1개) — 새로 만들면 true, 이미 정산이 있거나 대상이 없으면 false
    private boolean aggregateStore(Long storeId, SettlementPeriod period, LocalDateTime now) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                // 1. 매장 행 잠금 — 이후 조회는 앞선 같은 매장 집계가 커밋한 결과를 봄
                Store store = storeRepository.findByIdForUpdate(storeId)
                        .orElseThrow(() -> new IllegalStateException("매장이 없습니다: " + storeId));

                // 2. 이미 정산이 있으면 건너뜀 (기존 정산은 PENDING이라도 수정하지 않음 → 늦게 들어온 환불은 다음 정산에서 차감)
                if (settlementRepository.existsByStoreIdAndSettlementDate(storeId, period.from())) {
                    return false;
                }

                // 3. 판매·미차감 환불 — 둘 다 없으면 만들 정산이 없음 (이월분만 남은 매장은 다음 판매·환불 때 차감)
                List<Payment> sales = paymentRepository.findSettlementTargets(storeId, period.start(), period.end());
                List<Payment> refunds = paymentRepository.findUnsettledRefunds(storeId, period.end());
                if (sales.isEmpty() && refunds.isEmpty()) {
                    return false;
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
                settlementRefundRepository.flush();
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            // 동시에 실행된 다른 집계가 같은 매장·날짜를 먼저 저장한 경우만 중복으로 보고 건너뜀
            // 다른 제약 위반(연결 기록 UNIQUE, 금액 CHECK 등)이면 그대로 던져 실패로 기록 → 전체 롤백 후 다음 실행에서 재시도
            if (settlementRepository.existsByStoreIdAndSettlementDate(storeId, period.from())) {
                return false;
            }
            throw e;
        }
    }
}

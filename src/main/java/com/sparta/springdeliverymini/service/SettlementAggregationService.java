package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.entity.SettlementTotals;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.SettlementRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 일 정산 집계 — 하루(D)치 정산 대상 결제를 매장별로 집계해 PENDING(확정 대기) 정산으로 저장 (docs/settlement-policy.md)
// 확정·지급은 관리자가 AdminSettlementService로 처리
//
// 트랜잭션: 매장 1곳 = 트랜잭션 1개 (TransactionTemplate)
//   → 한 매장에서 실패해도 다른 매장의 집계는 그대로 커밋되고, 실패한 매장은 다음 실행에서 다시 시도됨
//   (같은 클래스 안의 @Transactional 메서드를 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로 TransactionTemplate 사용)
//
// 중복 정산 방지
//   1. 집계 전에 이미 정산이 있는 매장·날짜인지 확인 → 건너뜀 (상태와 관계없이, 확정·지급된 정산도 다시 만들지 않음)
//   2. 두 실행이 동시에 1을 통과해도 DB UNIQUE(store_id, settlement_date)가 두 번째 저장을 거부 → 건너뜀
@Slf4j
@Service
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SettlementAggregationService {

    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final StoreRepository storeRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock; // 집계 가능 여부 판단 기준 (config.TimeConfig, Asia/Seoul)

    // 하루치 결과 — created: 새로 집계, skipped: 이미 정산이 있어 건너뜀, failed: 오류로 집계하지 못함(다음 실행에서 재시도)
    public record DailyResult(LocalDate date, int created, int skipped, int failed) {
    }

    // date 하루를 매장별로 집계
    // 아직 집계 가능 시각(date+1 01:00) 전이면 IllegalArgumentException — 집계 후 금액이 바뀌는 일을 막음
    public DailyResult aggregateDaily(LocalDate date) {
        LocalDateTime now = LocalDateTime.now(clock);
        SettlementPeriod period = SettlementPeriod.of(date, date, now);

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Long storeId : paymentRepository.findSettlementStoreIds(period.start(), period.end())) {
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

    // 매장 1곳 집계 (트랜잭션 1개) — 새로 만들면 true, 이미 정산이 있으면 false
    private boolean aggregateStore(Long storeId, SettlementPeriod period, LocalDateTime now) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                if (settlementRepository.existsByStoreIdAndSettlementDate(storeId, period.from())) {
                    return false;
                }
                List<Payment> payments = paymentRepository.findSettlementTargets(storeId, period.start(), period.end());
                Settlement settlement = Settlement.aggregate(
                        storeRepository.getReferenceById(storeId), period.from(), SettlementTotals.of(payments), now);
                // 트랜잭션 안에서 INSERT를 실행해 UNIQUE 위반을 여기서 받음
                settlementRepository.saveAndFlush(settlement);
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            // 동시에 실행된 다른 집계가 먼저 저장한 경우만 중복으로 보고 건너뜀
            // 다른 제약 위반(금액 CHECK 등)이면 그대로 던져 실패로 기록
            if (settlementRepository.existsByStoreIdAndSettlementDate(storeId, period.from())) {
                return false;
            }
            throw e;
        }
    }
}

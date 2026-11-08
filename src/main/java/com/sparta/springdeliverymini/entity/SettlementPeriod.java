package com.sparta.springdeliverymini.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

// 정산 조회 기간 — 기간 규칙(docs/settlement-policy.md)을 한곳에서 관리
//
// - 날짜 범위 from ~ to (둘 다 포함)를 받아 배달완료 시각의 반열림 구간 [from 00:00, to+1 00:00)으로 변환
//   → 끝을 23:59:59로 잡으면 소수점 초가 빠지고, 기간을 이어 붙일 때 경계가 겹치거나 빔
// - 확정된 날짜만 조회 가능: D일 정산은 D+1 01:00(Asia/Seoul) 이후 확정
//   → 자정을 넘겨 커밋되는 배달완료 트랜잭션을 위해 1시간 여유
// - 한 번에 조회할 수 있는 기간은 최대 31일 (건별 내역이 함께 나가므로 응답 크기 제한)
// 날짜·시각은 모두 Asia/Seoul 기준 값 (호출하는 쪽이 Clock 기준 현재 시각을 넘김)
public record SettlementPeriod(LocalDate from, LocalDate to) {

    public static final LocalTime CONFIRM_TIME = LocalTime.of(1, 0);
    public static final int MAX_DAYS = 31;

    // 규칙에 맞지 않으면 IllegalArgumentException (Service가 400으로 변환)
    public static SettlementPeriod of(LocalDate from, LocalDate to, LocalDateTime now) {
        SettlementPeriod period = range(from, to);
        LocalDate lastConfirmed = lastConfirmedDate(now);
        if (to.isAfter(lastConfirmed)) {
            throw new IllegalArgumentException(
                    "아직 확정되지 않은 날짜가 포함되어 있습니다. " + lastConfirmed + "까지 조회할 수 있습니다.");
        }
        return period;
    }

    // 시작일 ≤ 종료일, 최대 31일만 검사 (확정 여부는 보지 않음)
    // 일 정산 기록 조회용 — 기록은 집계가 끝난 날짜에만 있으므로 확정 전 날짜는 결과가 비어 있을 뿐
    public static SettlementPeriod range(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("정산 조회 기간은 최대 " + MAX_DAYS + "일입니다.");
        }
        return new SettlementPeriod(from, to);
    }

    // 지금 시점에 확정된 마지막 날짜
    // 예) 11/6 01:00 이후 → 11/5까지, 11/6 00:30 → 11/4까지
    public static LocalDate lastConfirmedDate(LocalDateTime now) {
        LocalDate yesterday = now.toLocalDate().minusDays(1);
        return now.toLocalTime().isBefore(CONFIRM_TIME) ? yesterday.minusDays(1) : yesterday;
    }

    // 구간 시작 (포함)
    public LocalDateTime start() {
        return from.atStartOfDay();
    }

    // 구간 끝 (미포함) — 종료일 다음 날 00:00
    public LocalDateTime end() {
        return to.plusDays(1).atStartOfDay();
    }
}

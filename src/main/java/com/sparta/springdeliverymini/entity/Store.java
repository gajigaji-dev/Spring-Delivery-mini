package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.math.BigDecimal;

// 매장 — 점주(OWNER) 1명이 여러 매장을 가질 수 있고, 메뉴·주문은 매장에 속함
// BaseEntity 상속 → created_at(개설 시각), updated_at(수정 시각) 자동 기록
@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class Store extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    // 매장 주인(점주)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    // 매장별 플랫폼 수수료율 (V10) — null이면 공통 수수료율(application.yml payment.fee.rate) 사용
    // 0(수수료 0%)과 null(공통 수수료율 사용)은 다른 의미
    // 결제 시점에 effectiveFeeRate()로 정한 값이 Payment.feeRate에 스냅샷으로 저장됨
    // → 이 값을 바꿔도 이미 저장된 결제·정산은 바뀌지 않고, 이후 결제부터 적용
    @Column(precision = 5, scale = 4)
    private BigDecimal feeRate;

    public Store(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }

    // 결제에 적용할 수수료율 — 매장 수수료율이 있으면 그 값, 없으면 공통 수수료율
    public BigDecimal effectiveFeeRate(BigDecimal defaultRate) {
        return feeRate != null ? feeRate : defaultRate;
    }

    // 매장 수수료율 설정 (관리자) — 범위·자릿수 규칙은 결제 수수료와 같음 (PaymentFee.validateRate)
    // DB(numeric(5,4))에 저장되는 값과 같도록 4자리로 맞춤 (0.03 → 0.0300)
    public void changeFeeRate(BigDecimal feeRate) {
        PaymentFee.validateRate(feeRate);
        this.feeRate = feeRate.setScale(PaymentFee.RATE_SCALE);
    }

    // 매장 수수료율 해제 (관리자) → 이후 결제는 공통 수수료율 사용
    public void clearFeeRate() {
        this.feeRate = null;
    }

    // 소유권 검증은 여기 한곳에서만 판단 → 메뉴 수정·삭제, 주문 처리, 매장별 주문 조회가 같은 규칙을 사용
    // (권한이 없을 때 몇 번 상태코드로 응답할지는 Service 책임)
    public boolean isOwnedBy(User user) {
        return user != null && owner.getId().equals(user.getId());
    }
}

package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDateTime;

// DB 기반 결제 멱등성 기록 (V14) — docs/architecture.md Redis 장
//
// - 결제와 같은 트랜잭션에 저장 → 결제가 커밋되면 기록도 반드시 남고, 롤백되면 남지 않음
// - Redis 기록(service.PaymentIdempotencyService)이 TTL 만료·유실됐거나 Redis가 내려가 있어도
//   같은 Idempotency-Key 재시도에 처음 응답을 돌려주는 근거
// - 응답 본문은 저장하지 않음: 결제 직후 응답은 결제 행의 불변 값으로 다시 만듦 (dto.PaymentResponse.atPayment)
// - 생성 후 변경하지 않음. 보관 기간이 지나면 삭제 (maintenance.RetentionCleaner)
@Entity
@Table(name = "payment_idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_idempotency_keys_user_key", columnNames = {"user_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    // 요청 본문(orderId·paymentMethod)의 SHA-256 — 같은 키에 다른 요청이면 422
    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false, unique = true)
    private Payment payment;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PaymentIdempotencyKey(User user, String idempotencyKey, String fingerprint, Payment payment,
                                 LocalDateTime createdAt) {
        if (user == null || idempotencyKey == null || idempotencyKey.isBlank() || fingerprint == null
                || payment == null || createdAt == null) {
            throw new IllegalArgumentException("결제 멱등성 기록에 필요한 값이 없습니다.");
        }
        this.user = user;
        this.idempotencyKey = idempotencyKey;
        this.fingerprint = fingerprint;
        this.payment = payment;
        this.createdAt = createdAt;
    }
}

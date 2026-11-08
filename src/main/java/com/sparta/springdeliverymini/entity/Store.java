package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

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

    public Store(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }

    // 소유권 검증은 여기 한곳에서만 판단 → 메뉴 수정·삭제, 주문 처리, 매장별 주문 조회가 같은 규칙을 사용
    // (권한이 없을 때 몇 번 상태코드로 응답할지는 Service 책임)
    public boolean isOwnedBy(User user) {
        return user != null && owner.getId().equals(user.getId());
    }
}

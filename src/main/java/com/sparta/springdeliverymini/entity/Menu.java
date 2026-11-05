package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

// BaseEntity 상속 → created_at(생성 시각), updated_at(수정 시각) 자동 기록
@Entity
@Table(name = "menus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class Menu extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(length = 255)
    private String description;

    private boolean deleted = false;

    // 메뉴를 판매하는 매장
    // 메뉴의 주인(점주)은 store.owner로 판단 (V4에서 menus.owner_id 제거)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    public Menu(String name, long price, String description, Store store) {
        this.name = name;
        this.price = price;
        this.description = description;
        this.store = store;
    }

    // 소프트 삭제 처리 메서드
    public void delete() {
        this.deleted = true;
    }

    // 메뉴 정보 수정 메서드
    // 요청 값 검증은 Controller의 @Valid(MenuUpdateRequest)에서 이미 끝난 상태로 호출됨
    public void update(String name, long price, String description) {
        this.name = name;
        this.price = price;
        this.description = description;
    }
}
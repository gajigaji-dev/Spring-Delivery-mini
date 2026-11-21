package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidOwnerApplicationStatusException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

// BaseEntity 상속 → created_at(가입 시각), updated_at(정보 수정 시각) 자동 기록
@Entity
@Table(name = "users") // user는 PostgreSQL 예약어라 테이블명을 users로 지정
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String username;

    // BCrypt 해시. 소셜 전용 회원은 null (V22) — 아이디·비밀번호 로그인 불가
    @Column
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    public User(String username, String password, Role role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    // 소셜 로그인 첫 가입 (V22) — 가입 경로와 같이 항상 CUSTOMER, 비밀번호 없음
    public static User socialCustomer(String username) {
        return new User(username, null, Role.CUSTOMER);
    }

    public boolean hasPassword() {
        return password != null;
    }

    // 사장님 전환 — 관리자 승인(OwnerApplication.approve)에서만 호출 (V17 승인제)
    // 이미 발급된 Access Token의 role은 그대로라 /api/users/refresh 이후부터 OWNER로 인가됨
    // (그 사이에도 Service가 DB 역할을 다시 확인하므로 고객 권한으로 남는 일 없음)
    public void promoteToOwner() {
        if (role != Role.CUSTOMER) {
            throw new InvalidOwnerApplicationStatusException("고객 계정만 사장님으로 전환할 수 있습니다. 현재 역할: " + role);
        }
        this.role = Role.OWNER;
    }
}
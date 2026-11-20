package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;

import java.time.LocalDateTime;
import java.util.List;

// 내 정보 조회 응답 (#62) — 토큰 값이 아니라 DB의 현재 회원 정보
// 토큰 발급 뒤 역할이 바뀌었으면 role은 바뀐 값 (화면 메뉴 구성은 이 값을 기준으로)
public record MyInfoResponse(
        Long id,
        String username,
        Role role,
        LocalDateTime createdAt, // 가입 시각 — 별도 표시 이름(닉네임) 컬럼이 없어 화면 표시는 username 사용
        List<Long> storeIds) {   // OWNER의 매장 id (id 오름차순), 그 밖의 역할은 빈 배열

    public static MyInfoResponse of(User user, List<Long> storeIds) {
        return new MyInfoResponse(user.getId(), user.getUsername(), user.getRole(), user.getCreatedAt(), storeIds);
    }
}

package com.sparta.springdeliverymini.controller;

import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;

import java.util.List;

// 페이지 조회 결과를 응답으로 변환 (#40)
// 본문은 기존과 같은 JSON 배열 그대로 두고, 페이지 정보는 헤더로 전달 → 배열을 읽던 기존 클라이언트가 깨지지 않음
// 전체 개수(count 쿼리)는 제공하지 않음: 목록을 볼 때마다 조건에 맞는 행을 모두 세는 비용을 피하고,
//   다음 페이지가 있는지만 size + 1건 조회로 판단 (Slice)
final class SliceResponse {

    static final String PAGE = "X-Page";
    static final String SIZE = "X-Size";
    static final String HAS_NEXT = "X-Has-Next";

    private SliceResponse() {
    }

    static <T> ResponseEntity<List<T>> of(Slice<T> slice) {
        return ResponseEntity.ok()
                .header(PAGE, String.valueOf(slice.getNumber()))
                .header(SIZE, String.valueOf(slice.getSize()))
                .header(HAS_NEXT, String.valueOf(slice.hasNext()))
                .body(slice.getContent());
    }
}

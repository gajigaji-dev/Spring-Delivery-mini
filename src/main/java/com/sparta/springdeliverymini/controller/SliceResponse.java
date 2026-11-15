package com.sparta.springdeliverymini.controller;

import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.function.ToLongFunction;

// 페이지 조회 결과를 응답으로 변환 (#40)
// 본문은 기존과 같은 JSON 배열 그대로 두고, 페이지 정보는 헤더로 전달 → 배열을 읽던 기존 클라이언트가 깨지지 않음
// 전체 개수(count 쿼리)는 제공하지 않음: 목록을 볼 때마다 조건에 맞는 행을 모두 세는 비용을 피하고,
//   다음 페이지가 있는지만 size + 1건 조회로 판단 (Slice)
final class SliceResponse {

    static final String PAGE = "X-Page";
    static final String SIZE = "X-Size";
    static final String HAS_NEXT = "X-Has-Next";
    // keyset 페이징(#54)의 다음 요청 위치 — 이번 페이지 마지막 행의 id, 다음 페이지가 있을 때만 응답
    static final String NEXT_AFTER_ID = "X-Next-After-Id";

    private SliceResponse() {
    }

    static <T> ResponseEntity<List<T>> of(Slice<T> slice) {
        return ResponseEntity.ok()
                .header(PAGE, String.valueOf(slice.getNumber()))
                .header(SIZE, String.valueOf(slice.getSize()))
                .header(HAS_NEXT, String.valueOf(slice.hasNext()))
                .body(slice.getContent());
    }

    // OFFSET 페이지 응답 + 다음 위치(X-Next-After-Id) — 파라미터 없이 첫 페이지를 받은 클라이언트가 바로 keyset으로 이어갈 수 있도록
    static <T> ResponseEntity<List<T>> of(Slice<T> slice, ToLongFunction<T> idOf) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(PAGE, String.valueOf(slice.getNumber()))
                .header(SIZE, String.valueOf(slice.getSize()))
                .header(HAS_NEXT, String.valueOf(slice.hasNext()));
        return withNextAfterId(builder, slice, idOf).body(slice.getContent());
    }

    // keyset 페이지 응답 — 페이지 번호가 없으므로 X-Page는 보내지 않음 (항상 0이라 오해의 소지)
    static <T> ResponseEntity<List<T>> ofKeyset(Slice<T> slice, ToLongFunction<T> idOf) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(SIZE, String.valueOf(slice.getSize()))
                .header(HAS_NEXT, String.valueOf(slice.hasNext()));
        return withNextAfterId(builder, slice, idOf).body(slice.getContent());
    }

    private static <T> ResponseEntity.BodyBuilder withNextAfterId(
            ResponseEntity.BodyBuilder builder, Slice<T> slice, ToLongFunction<T> idOf) {
        if (slice.hasNext()) {
            List<T> content = slice.getContent();
            builder.header(NEXT_AFTER_ID, String.valueOf(idOf.applyAsLong(content.get(content.size() - 1))));
        }
        return builder;
    }
}

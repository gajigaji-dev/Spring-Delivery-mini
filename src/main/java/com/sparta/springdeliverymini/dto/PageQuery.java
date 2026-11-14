package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

// 목록 조회의 페이지 요청 (#40) — ?page=0&size=20
// 전체 조회를 막기 위해 한 번에 가져올 수 있는 개수에 상한을 둠 (데이터가 쌓여도 응답 크기·쿼리 수가 일정)
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    // 컨트롤러 @RequestParam의 기본값 (애노테이션에는 문자열 상수만 쓸 수 있음)
    public static final String DEFAULT_PAGE_PARAM = "0";
    public static final String DEFAULT_SIZE_PARAM = "20";

    // 범위를 벗어난 값은 조용히 보정하지 않고 400 → 클라이언트가 잘못된 요청을 바로 알 수 있도록
    public static PageQuery of(int page, int size) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "page: 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "size: 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        return new PageQuery(page, size);
    }

    // 정렬은 항상 id 오름차순으로 고정
    // - 기존 전체 조회가 돌려주던 순서(등록순)를 유지
    // - 정렬 기준이 고유해야 페이지 사이에 같은 행이 중복·누락되지 않음
    public Pageable toPageable() {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
    }
}

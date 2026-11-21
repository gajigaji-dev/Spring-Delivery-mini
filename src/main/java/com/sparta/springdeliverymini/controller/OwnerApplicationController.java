package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.OwnerApplicationRequest;
import com.sparta.springdeliverymini.dto.OwnerApplicationResponse;
import com.sparta.springdeliverymini.service.OwnerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// 사장님 전환 신청 (V17 승인제) — 신청은 CUSTOMER만 (SecurityConfig + Service에서 DB 역할 확인)
@RestController
@RequestMapping("/api/owner-applications")
@RequiredArgsConstructor
public class OwnerApplicationController {

    private final OwnerApplicationService ownerApplicationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OwnerApplicationResponse apply(@Valid @RequestBody OwnerApplicationRequest request,
                                          Authentication authentication) {
        return ownerApplicationService.apply(authentication.getName(), request.businessName());
    }

    // 내 최근 신청 — 승인 후 OWNER가 된 뒤에도 결과를 볼 수 있도록 역할 제한 없음 (없으면 404)
    @GetMapping("/me")
    public OwnerApplicationResponse getMine(Authentication authentication) {
        return ownerApplicationService.getMyLatest(authentication.getName());
    }
}

package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.MenuCreateRequest;
import com.sparta.springdeliverymini.dto.MenuResponse;
import com.sparta.springdeliverymini.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class MenuController {

    private final MenuService menuService;

    // OWNER 권한 확인은 SecurityConfig에서 토큰의 role로 처리 (CUSTOMER → 403)
    @PostMapping("/menus")
    public ResponseEntity<MenuResponse> addMenu(
            @Valid @RequestBody MenuCreateRequest request,
            Authentication authentication) {

        // 현재 로그인한 OWNER의 정보를 Service에 전달하여 메뉴 생성
        MenuResponse menu = menuService.createMenu(
                request,
                authentication.getName()
        );

        // 메뉴 생성 성공 시 201 Created와 생성된 메뉴 정보 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(menu);
    }

    // 삭제되지 않은 전체 메뉴 목록 조회
    @GetMapping("/menus")
    public List<MenuResponse> getMenus() {
        return menuService.getMenus();
    }

    // 메뉴 ID로 특정 메뉴 조회
    // 존재하지 않거나 삭제된 메뉴는 Service에서 404 처리
    @GetMapping("/menus/{id}")
    public MenuResponse getMenu(@PathVariable Long id) {
        return menuService.getMenu(id);
    }

    // 메뉴 수정
    // 요청 Body의 JSON을 MenuCreateRequest로 변환하고 유효성 검증
    @PutMapping("/menus/{id}")
    public ResponseEntity<MenuResponse> putMenu(
            @PathVariable Long id,
            @Valid @RequestBody MenuCreateRequest request,
            Authentication authentication) {

        // JWT 인증을 통해 현재 로그인한 사용자의 정보를 Service에 전달
        // Service에서 해당 사용자가 메뉴의 OWNER인지 확인
        MenuResponse menu = menuService.putMenu(
                id,
                request,
                authentication.getName()
        );

        // 수정 성공 시 200 OK와 수정된 메뉴 정보 반환
        return ResponseEntity.status(HttpStatus.OK).body(menu);
    }

    // 메뉴 삭제
    // 실제 DB 데이터를 삭제하지 않고 deleted 값을 변경하는 Soft Delete 방식 사용
    @DeleteMapping("/menus/{id}")
    public ResponseEntity<Void> deleteMenu(
            @PathVariable Long id,
            Authentication authentication
    ) {
        // 현재 로그인한 사용자의 정보와 메뉴 ID를 Service에 전달
        // Service에서 메뉴 존재 여부와 본인 메뉴인지 확인
        menuService.deleteMenu(id, authentication.getName());

        // 삭제 성공 시 응답할 데이터가 없으므로 204 No Content 반환
        return ResponseEntity.noContent().build();
    }
}

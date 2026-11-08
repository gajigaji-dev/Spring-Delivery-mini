package com.sparta.springdeliverymini.service;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.MenuCreateRequest;
import com.sparta.springdeliverymini.dto.MenuResponse;
import com.sparta.springdeliverymini.entity.Menu;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class MenuService {

    private final MenuRepository menuRepository;
    private final UserRepository userRepository;

    @Transactional
    public MenuResponse createMenu(MenuCreateRequest request, String username) {

        // 1. 현재 로그인한 사용자 찾기
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User owner = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. 요청받은 정보와 현재 사용자를 이용해 메뉴 생성
        Menu menu = new Menu(
                request.name(),
                request.price(),
                request.description(),
                owner
        );

        // 3. 생성한 메뉴를 DB에 저장하고 응답 DTO로 변환
        return MenuResponse.from(menuRepository.save(menu));
    }

    public List<MenuResponse> getMenus() {

        // 삭제되지 않은 메뉴만 조회
        // deleted = true인 메뉴는 목록에서 제외
        return menuRepository.findAllByDeletedFalse()
                .stream()
                .map(MenuResponse::from)
                .toList();
    }

    public MenuResponse getMenu(Long id) {

        // 메뉴가 존재하지 않거나 삭제된 경우 → 404
        Menu menu = menuRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "메뉴가 없습니다."
                        )
                );

        // 조회한 Entity를 응답 DTO로 변환
        return MenuResponse.from(menu);
    }

    @Transactional
    public MenuResponse putMenu(
            Long id,
            MenuCreateRequest request,
            String username
    ) {

        // 1. 현재 로그인한 사용자 찾기
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. 수정할 메뉴 찾기
        // 존재하지 않거나 삭제된 메뉴 → 404
        Menu menu = menuRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "메뉴가 없습니다."
                        )
                );

        // 3. 현재 사용자가 메뉴의 OWNER인지 확인
        // 다른 OWNER의 메뉴라면 수정할 수 없음 → 403
        if (!menu.getOwner().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "다른 사장님의 메뉴입니다."
            );
        }

        // 4. 기존 메뉴의 값을 요청받은 값으로 변경
        menu.update(
                request.name(),
                request.price(),
                request.description()
        );

        // 5. 변경 내용을 DB에 즉시 반영
        // Dirty Checking의 UPDATE는 원래 트랜잭션 커밋 시점에 실행되는데,
        // updatedAt(@LastModifiedDate)도 그 UPDATE 직전에 채워짐
        // → flush 없이 응답을 만들면 updatedAt이 수정 전 시각으로 나가므로 먼저 flush
        menuRepository.flush();

        // 6. 변경된 Entity를 DTO로 변환
        return MenuResponse.from(menu);
    }

    @Transactional
    public void deleteMenu(Long id, String username) {

        // 1. 현재 로그인한 사용자 찾기
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.UNAUTHORIZED,
                                "존재하지 않는 회원입니다."
                        )
                );

        // 2. 삭제할 메뉴 찾기
        // 존재하지 않거나 이미 삭제된 메뉴 → 404
        Menu menu = menuRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "메뉴가 없습니다."
                        )
                );

        // 3. 현재 사용자가 메뉴의 OWNER인지 확인
        // 다른 OWNER의 메뉴라면 삭제할 수 없음 → 403
        if (!menu.getOwner().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "다른 사장님의 메뉴입니다."
            );
        }

        // 4. DB에서 실제 메뉴를 삭제하지 않고 deleted 값을 true로 변경
        // 기존 주문의 menu_id 외래키 관계를 유지하기 위해 Soft Delete 사용
        menu.delete();
    }
}

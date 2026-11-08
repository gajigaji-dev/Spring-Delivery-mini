package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.MenuCreateRequest;
import com.sparta.springdeliverymini.dto.MenuResponse;
import com.sparta.springdeliverymini.dto.MenuUpdateRequest;
import com.sparta.springdeliverymini.entity.Menu;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 메뉴가 매장에 등록되고, 메뉴 수정·삭제 권한을 매장 소유권으로 판단하는지 검증
@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private MenuRepository menuRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StoreService storeService;

    @InjectMocks
    private MenuService menuService;

    private final User owner = user(1L, "owner1", Role.OWNER);
    private final User otherOwner = user(2L, "owner2", Role.OWNER);
    private final Store store = store(10L, owner);

    private static User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Store store(Long id, User owner) {
        Store store = new Store("치킨집", owner);
        ReflectionTestUtils.setField(store, "id", id);
        return store;
    }

    @Test
    void 본인_매장에_메뉴를_등록한다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(10L, owner)).willReturn(store);
        given(menuRepository.save(any(Menu.class))).willAnswer(invocation -> invocation.getArgument(0));

        MenuResponse response = menuService.createMenu(
                new MenuCreateRequest(10L, "후라이드 치킨", 18000L, "바삭한 치킨"), "owner1");

        assertThat(response.storeId()).isEqualTo(10L);
    }

    @Test
    void 다른_OWNER의_매장에는_메뉴를_등록할_수_없다() {
        given(userRepository.findByUsername("owner2")).willReturn(Optional.of(otherOwner));
        given(storeService.getOwnedStore(10L, otherOwner))
                .willThrow(new ApiException(HttpStatus.FORBIDDEN, "본인 매장이 아닙니다."));

        assertThatThrownBy(() -> menuService.createMenu(
                new MenuCreateRequest(10L, "후라이드 치킨", 18000L, "바삭한 치킨"), "owner2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(menuRepository, never()).save(any());
    }

    @Test
    void 다른_OWNER_매장의_메뉴는_수정할_수_없다() {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        given(userRepository.findByUsername("owner2")).willReturn(Optional.of(otherOwner));
        given(menuRepository.findByIdAndDeletedFalse(5L)).willReturn(Optional.of(menu));

        assertThatThrownBy(() -> menuService.putMenu(5L, new MenuUpdateRequest("양념 치킨", 19000L, null), "owner2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(menu.getName()).isEqualTo("후라이드 치킨");
    }

    @Test
    void 다른_OWNER_매장의_메뉴는_삭제할_수_없다() {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        given(userRepository.findByUsername("owner2")).willReturn(Optional.of(otherOwner));
        given(menuRepository.findByIdAndDeletedFalse(5L)).willReturn(Optional.of(menu));

        assertThatThrownBy(() -> menuService.deleteMenu(5L, "owner2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(menu.isDeleted()).isFalse();
    }

    @Test
    void 본인_매장의_메뉴는_삭제한다() {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(menuRepository.findByIdAndDeletedFalse(5L)).willReturn(Optional.of(menu));

        menuService.deleteMenu(5L, "owner1");

        assertThat(menu.isDeleted()).isTrue();
    }
}

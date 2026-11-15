package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Menu;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    // ID로 메뉴 조회, 메뉴가 없거나 deleted = true이면 Optional.empty() 반환
    Optional<Menu> findByIdAndDeletedFalse(Long id);

    // 삭제되지 않은 메뉴를 페이지 단위로 조회, deleted = true인 메뉴는 목록에서 제외 (#40)
    // Slice: 전체 개수를 세지 않고 size + 1건만 읽어 다음 페이지 여부를 판단
    Slice<Menu> findAllByDeletedFalse(Pageable pageable);

    // 주문 항목의 메뉴들을 한 번에 조회, 없거나 삭제된 메뉴는 결과에서 빠짐 (요청 개수와 비교해 404 판단)
    List<Menu> findAllByIdInAndDeletedFalse(Collection<Long> ids);
}

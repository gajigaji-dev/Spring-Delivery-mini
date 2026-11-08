package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    // ID로 메뉴 조회, 메뉴가 없거나 deleted = true이면 Optional.empty() 반환
    Optional<Menu> findByIdAndDeletedFalse(Long id);

    // 삭제되지 않은 모든 메뉴를 조회, deleted = true인 메뉴는 목록에서 제외
    List<Menu> findAllByDeletedFalse();
}

package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {
}

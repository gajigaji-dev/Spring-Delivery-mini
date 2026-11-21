package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    // 소셜 로그인 — uk_social_accounts_provider_user
    Optional<SocialAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    // 연결 해제·중복 연결 확인 — uk_social_accounts_user_provider
    Optional<SocialAccount> findByUserIdAndProvider(Long userId, String provider);

    List<SocialAccount> findAllByUserIdOrderByIdAsc(Long userId);

    long countByUserId(Long userId);
}

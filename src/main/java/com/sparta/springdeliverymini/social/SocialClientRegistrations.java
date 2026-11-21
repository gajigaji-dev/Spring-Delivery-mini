package com.sparta.springdeliverymini.social;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 키가 설정된 공급자만 담는 ClientRegistrationRepository (config.SocialLoginConfig에서 생성)
// Spring Boot 기본(spring.security.oauth2.client.registration.*)을 쓰지 않는 이유:
//   키가 비어 있으면 기동이 실패함 → 키 없이도 서버가 떠야 하는 로컬·CI·테스트에서 공급자를 끄는 방법이 없음 (토스 결제와 같은 방식)
// 비어 있어도 됨 — 그때 소셜 로그인은 모두 404, 프론트는 버튼을 숨김
public class SocialClientRegistrations implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private final Map<String, ClientRegistration> registrations;
    private final Map<String, SocialProvider> providers;

    public SocialClientRegistrations(Map<SocialProvider, ClientRegistration> enabled) {
        Map<String, ClientRegistration> regs = new LinkedHashMap<>();
        Map<String, SocialProvider> provs = new LinkedHashMap<>();
        enabled.forEach((provider, registration) -> {
            regs.put(provider.registrationId(), registration);
            provs.put(provider.registrationId(), provider);
        });
        this.registrations = Map.copyOf(regs);
        this.providers = Map.copyOf(provs);
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        return registrationId == null ? null : registrations.get(registrationId);
    }

    // 켜진 공급자만 (키 없는 공급자는 구현이 있어도 empty)
    public Optional<SocialProvider> provider(String registrationId) {
        return Optional.ofNullable(registrationId == null ? null : providers.get(registrationId));
    }

    // 로그인 화면 버튼 순서 — 이름순으로 고정 (Map 순서에 기대지 않음)
    public List<String> enabledIds() {
        return registrations.keySet().stream().sorted().toList();
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return registrations.values().iterator();
    }
}

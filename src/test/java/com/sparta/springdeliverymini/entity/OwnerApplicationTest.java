package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidOwnerApplicationStatusException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 사장님 전환 신청의 상태 전이(V17) — REQUESTED에서만 승인·거절, 승인 시 신청자 역할 전환 (DB 없이)
class OwnerApplicationTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 21, 10, 0);
    private static final LocalDateTime DECIDED_AT = REQUESTED_AT.plusHours(1);

    private final User admin = new User("admin", "pw", Role.ADMIN);

    private static OwnerApplication application(User user) {
        return new OwnerApplication(user, "  치킨집  ", REQUESTED_AT);
    }

    @Test
    void 신청하면_승인_대기_상태이고_상호는_앞뒤_공백을_뺀다() {
        OwnerApplication application = application(new User("cus", "pw", Role.CUSTOMER));

        assertThat(application.getStatus()).isEqualTo(OwnerApplicationStatus.REQUESTED);
        assertThat(application.getBusinessName()).isEqualTo("치킨집");
        assertThat(application.getDecidedAt()).isNull();
        assertThat(application.getDecidedBy()).isNull();
    }

    @Test
    void 승인하면_신청자가_OWNER가_되고_처리자와_시각이_기록된다() {
        User customer = new User("cus", "pw", Role.CUSTOMER);
        OwnerApplication application = application(customer);

        application.approve(admin, DECIDED_AT);

        assertThat(application.getStatus()).isEqualTo(OwnerApplicationStatus.APPROVED);
        assertThat(customer.getRole()).isEqualTo(Role.OWNER);
        assertThat(application.getDecidedBy()).isSameAs(admin);
        assertThat(application.getDecidedAt()).isEqualTo(DECIDED_AT);
        assertThat(application.getRejectReason()).isNull();
    }

    @Test
    void 거절하면_사유가_남고_신청자_역할은_그대로다() {
        User customer = new User("cus", "pw", Role.CUSTOMER);
        OwnerApplication application = application(customer);

        application.reject(admin, " 상호 확인 불가 ", DECIDED_AT);

        assertThat(application.getStatus()).isEqualTo(OwnerApplicationStatus.REJECTED);
        assertThat(application.getRejectReason()).isEqualTo("상호 확인 불가");
        assertThat(customer.getRole()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void 이미_처리된_신청은_다시_승인하거나_거절할_수_없다() {
        OwnerApplication approved = application(new User("a", "pw", Role.CUSTOMER));
        approved.approve(admin, DECIDED_AT);
        OwnerApplication rejected = application(new User("b", "pw", Role.CUSTOMER));
        rejected.reject(admin, "사유", DECIDED_AT);

        assertThatThrownBy(() -> approved.approve(admin, DECIDED_AT))
                .isInstanceOf(InvalidOwnerApplicationStatusException.class);
        assertThatThrownBy(() -> approved.reject(admin, "사유", DECIDED_AT))
                .isInstanceOf(InvalidOwnerApplicationStatusException.class);
        assertThatThrownBy(() -> rejected.approve(admin, DECIDED_AT))
                .isInstanceOf(InvalidOwnerApplicationStatusException.class);
    }

    @Test
    void 고객이_아닌_회원의_신청은_승인되지_않고_상태도_바뀌지_않는다() {
        // 신청 뒤 운영자가 DB에서 역할을 바꾼 경우 등 — 역할 전환이 실패하면 신청도 대기 상태로 남음
        User owner = new User("own", "pw", Role.OWNER);
        OwnerApplication application = application(owner);

        assertThatThrownBy(() -> application.approve(admin, DECIDED_AT))
                .isInstanceOf(InvalidOwnerApplicationStatusException.class);
        assertThat(application.getStatus()).isEqualTo(OwnerApplicationStatus.REQUESTED);
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    void 거절_사유와_처리자는_필수다() {
        OwnerApplication application = application(new User("cus", "pw", Role.CUSTOMER));

        assertThatThrownBy(() -> application.reject(admin, " ", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> application.approve(null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(application.getStatus()).isEqualTo(OwnerApplicationStatus.REQUESTED);
    }
}

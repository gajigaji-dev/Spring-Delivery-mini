package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.OwnerApplicationResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.OwnerApplication;
import com.sparta.springdeliverymini.entity.OwnerApplicationStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.OwnerApplicationRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

// 사장님 전환 신청·승인 (V17 승인제) — docs/api.md 사장님 전환 신청
//
// - 가입은 모두 CUSTOMER. 사장님은 신청 → 관리자 승인으로만 OWNER가 됨 (가입 요청의 role로 OWNER가 되던 구멍을 막음)
// - 역할은 토큰이 아니라 DB 기준으로 확인 (토큰 발급 뒤 역할이 바뀌었을 수 있음)
// - 진행 중 주문이 있으면 신청·승인 모두 409: 전환 뒤에는 고객 API가 403이 되어 그 주문을 취소·조회할 수 없게 되므로
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OwnerApplicationService {

    // 배달완료·취소가 아닌 주문 = 고객이 아직 다뤄야 하는 주문
    static final Set<OrderStatus> IN_PROGRESS_ORDER_STATUSES =
            EnumSet.of(OrderStatus.ORDER_REQUEST, OrderStatus.PAYMENT_COMPLETED, OrderStatus.ORDER_ACCEPTED);

    private final OwnerApplicationRepository ownerApplicationRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    @Transactional
    public OwnerApplicationResponse apply(String username, String businessName) {
        User user = findUser(username);
        if (user.getRole() != Role.CUSTOMER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "고객 계정만 사장님 전환을 신청할 수 있습니다.");
        }
        if (ownerApplicationRepository.existsByUserIdAndStatus(user.getId(), OwnerApplicationStatus.REQUESTED)) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 승인 대기 중인 신청이 있습니다.");
        }
        requireNoInProgressOrders(user);

        OwnerApplication application = new OwnerApplication(user, businessName, LocalDateTime.now(clock));
        // 동시에 두 번 신청하면 사전 검사를 둘 다 통과할 수 있음 → 부분 유니크 인덱스 위반을 즉시 확인해 409로 변환
        try {
            ownerApplicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 승인 대기 중인 신청이 있습니다.");
        }
        log.info("사장님 전환 신청 applicationId={}, userId={}", application.getId(), user.getId());
        return OwnerApplicationResponse.from(application);
    }

    // 내 최근 신청 — 역할과 무관하게 본인 것만 (승인 후 OWNER가 돼도 결과 확인 가능)
    public OwnerApplicationResponse getMyLatest(String username) {
        User user = findUser(username);
        return ownerApplicationRepository.findFirstByUserIdOrderByIdDesc(user.getId())
                .map(OwnerApplicationResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사장님 전환 신청 내역이 없습니다."));
    }

    public Slice<OwnerApplicationResponse> getApplications(OwnerApplicationStatus status, PageQuery pageQuery,
                                                           String adminUsername) {
        validateAdmin(adminUsername);
        return ownerApplicationRepository.findAllByStatus(status, pageQuery.toPageable())
                .map(OwnerApplicationResponse::from);
    }

    @Transactional
    public OwnerApplicationResponse approve(Long applicationId, String adminUsername) {
        User admin = validateAdmin(adminUsername);
        OwnerApplication application = findForUpdate(applicationId);
        // 신청 뒤 새로 주문했을 수 있으므로 승인 시점에 다시 확인
        // (상태 검사보다 뒤에 두면 이미 처리된 신청에 주문 오류가 나오므로 대기 중일 때만)
        if (application.getStatus() == OwnerApplicationStatus.REQUESTED) {
            requireNoInProgressOrders(application.getUser());
        }
        application.approve(admin, LocalDateTime.now(clock));
        log.info("사장님 전환 승인 applicationId={}, userId={}, actorId={}",
                application.getId(), application.getUser().getId(), admin.getId());
        return OwnerApplicationResponse.from(application);
    }

    @Transactional
    public OwnerApplicationResponse reject(Long applicationId, String reason, String adminUsername) {
        User admin = validateAdmin(adminUsername);
        OwnerApplication application = findForUpdate(applicationId);
        application.reject(admin, reason, LocalDateTime.now(clock));
        log.info("사장님 전환 거절 applicationId={}, userId={}, actorId={}",
                application.getId(), application.getUser().getId(), admin.getId());
        return OwnerApplicationResponse.from(application);
    }

    private void requireNoInProgressOrders(User user) {
        if (orderRepository.existsByUserIdAndStatusIn(user.getId(), IN_PROGRESS_ORDER_STATUSES)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "진행 중인 주문이 있어 사장님으로 전환할 수 없습니다. 주문이 배달완료되거나 취소된 뒤 다시 시도해주세요.");
        }
    }

    private OwnerApplication findForUpdate(Long applicationId) {
        return ownerApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "사장님 전환 신청이 없습니다."));
    }

    // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401 (다른 API와 같은 기준)
    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
    }

    // SecurityConfig에서도 ADMIN만 허용하지만, 토큰 발급 후 역할이 바뀐 경우에 대비해 DB 기준으로 한 번 더 확인
    private User validateAdmin(String username) {
        User user = findUser(username);
        if (user.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 사장님 전환 신청을 처리할 수 있습니다.");
        }
        return user;
    }
}

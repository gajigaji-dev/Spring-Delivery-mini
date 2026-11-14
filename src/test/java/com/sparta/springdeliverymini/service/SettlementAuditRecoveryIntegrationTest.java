package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.scheduler.SettlementScheduler;
import com.sparta.springdeliverymini.service.SettlementAggregationService.Outcome;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정산 감사 이력·집계 실패 기록·관리자 재집계(#39)를 실제 PostgreSQL로 검증
// - 누락·실패분만 재집계되고, 이미 있는 정산은 상태와 관계없이 바뀌지 않는지
// - 중복 요청, 스케줄러와의 동시 실행에도 정산·이력이 매장·날짜당 1건인지
// - 확정·지급의 처리자·시각이 인증된 관리자 기준으로 기록되는지
// SettlementIntegrationTest와 같은 설정(@Import)을 써서 Spring 컨텍스트·DB 컨테이너를 함께 사용
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class SettlementAuditRecoveryIntegrationTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 6, 9, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    // 집계를 일부러 실패시키는 임시 제약 — 11/5 정산 INSERT를 DB가 거부
    private static final String BLOCK_CONSTRAINT = "test_block_aggregation";

    @Autowired private MutableClock clock;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementScheduler settlementScheduler;
    @Autowired private AdminSettlementService adminSettlementService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    @Autowired private SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    @Autowired private JdbcTemplate jdbc;

    private StoreResponse chickenStore;
    private StoreResponse snackStore;
    private Long adminId;
    private Long admin2Id;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 11, 4, 12, 0));
        adminId = userRepository.save(new User("admin", "encoded-password", Role.ADMIN)).getId();
        admin2Id = userRepository.save(new User("admin2", "encoded-password", Role.ADMIN)).getId();
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        chickenStore = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        snackStore = storeService.createStore(new StoreCreateRequest("분식집"), "owner1");
    }

    @AfterEach
    void tearDown() {
        unblockAggregation();
        // FK 순서대로 삭제: 이력·실패 기록 → settlements → payments → orders(order_items는 CASCADE) → menus → stores → users
        settlementStatusHistoryRepository.deleteAllInBatch();
        settlementAggregationFailureRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // 주문 → 결제(수수료 5%) → 수락 → deliveredAt 시각에 배달완료
    private void deliveredOrder(StoreResponse store, long price, LocalDateTime deliveredAt) {
        clock.set(deliveredAt.minusHours(1));
        MenuResponse menu = menuService.createMenu(new MenuCreateRequest(store.id(), "메뉴", price, null), "owner1");
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
        orderService.statusOrder(order.id(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(order.id(), "owner1", OrderStatus.DELIVERY_COMPLETED);
    }

    // 11/5 정산 INSERT가 CHECK 위반으로 실패하게 만듦 (NOT VALID: 기존 행은 검사하지 않음)
    private void blockAggregation() {
        jdbc.execute("ALTER TABLE settlements ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (settlement_date <> DATE '2026-11-05') NOT VALID");
    }

    private void unblockAggregation() {
        jdbc.execute("ALTER TABLE settlements DROP CONSTRAINT IF EXISTS " + BLOCK_CONSTRAINT);
    }

    // 11/5 배달완료 1건 → 11/6 01:10 스케줄러 집계 → PENDING 정산 id
    private Long scheduledSettlementId() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        clock.set(AGGREGATED_AT);
        settlementScheduler.aggregateDailySettlements();
        return settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), NOV_5).orElseThrow().getId();
    }

    private SettlementAggregationFailureResponse singleFailure() {
        List<SettlementAggregationFailureResponse> failures = adminSettlementService.getAggregationFailures(null, "admin");
        assertThat(failures).hasSize(1);
        return failures.get(0);
    }

    // ---- 재집계 ----

    @Test
    void 스케줄러가_놓친_날짜를_관리자가_재집계하면_정산이_만들어지고_집계_이력의_처리자는_그_관리자다() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));

        // 11/20 실행 → catch-up 7일(11/13~11/19) 밖이라 스케줄러는 11/5를 집계하지 않음
        LocalDateTime now = LocalDateTime.of(2026, 11, 20, 10, 0);
        clock.set(now);
        settlementScheduler.aggregateDailySettlements();
        assertThat(settlementRepository.count()).isZero();

        SettlementReaggregationResponse response = adminSettlementService.reaggregate(NOV_5, null, "admin");

        assertThat(response.created()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        Settlement settlement = settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), NOV_5).orElseThrow();
        assertThat(response.results()).singleElement().satisfies(item -> {
            assertThat(item.storeId()).isEqualTo(chickenStore.id());
            assertThat(item.outcome()).isEqualTo(Outcome.CREATED);
            assertThat(item.settlementId()).isEqualTo(settlement.getId());
        });
        // 금액 계산은 스케줄러 집계와 같음 (수수료 5%)
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getTotalAmount()).isEqualTo(10000L);
        assertThat(settlement.getSettlementAmount()).isEqualTo(9500L);

        assertThat(adminSettlementService.getHistories(settlement.getId(), "admin")).singleElement().satisfies(h -> {
            assertThat(h.fromStatus()).isNull();
            assertThat(h.toStatus()).isEqualTo(SettlementStatus.PENDING);
            assertThat(h.actor()).isEqualTo(new SettlementActorResponse(adminId, "admin"));
            assertThat(h.changedAt()).isEqualTo(now);
        });
    }

    @Test
    void 같은_재집계를_다시_요청하면_건너뛰고_정산과_이력은_그대로다() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        clock.set(AGGREGATED_AT);
        adminSettlementService.reaggregate(NOV_5, null, "admin");
        Settlement first = settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), NOV_5).orElseThrow();

        clock.set(AGGREGATED_AT.plusHours(1));
        SettlementReaggregationResponse again = adminSettlementService.reaggregate(NOV_5, null, "admin2");

        assertThat(again.created()).isZero();
        assertThat(again.results()).extracting(SettlementReaggregationResponse.StoreItem::outcome)
                .containsExactly(Outcome.ALREADY_EXISTS);
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(settlementStatusHistoryRepository.count()).isEqualTo(1);
        Settlement reloaded = settlementRepository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(first.getUpdatedAt()); // 기존 정산 행은 UPDATE되지 않음
    }

    @Test
    void 실패한_집계는_실패_기록으로_남고_관리자_재집계로_복구된다() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        blockAggregation();

        // 1. 스케줄러 집계 실패 → 정산은 없고 실패 기록만 남음 (처리자 없음)
        clock.set(AGGREGATED_AT);
        settlementScheduler.aggregateDailySettlements();

        assertThat(settlementRepository.count()).isZero();
        assertThat(settlementStatusHistoryRepository.count()).isZero(); // 정산과 함께 롤백
        SettlementAggregationFailureResponse failed = singleFailure();
        assertThat(failed.storeId()).isEqualTo(chickenStore.id());
        assertThat(failed.storeName()).isEqualTo("치킨집");
        assertThat(failed.settlementDate()).isEqualTo(NOV_5);
        assertThat(failed.status()).isEqualTo(SettlementAggregationFailureStatus.FAILED);
        assertThat(failed.failureCount()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("DataIntegrityViolationException constraint=" + BLOCK_CONSTRAINT);
        assertThat(failed.firstFailedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(failed.lastFailedBy()).isNull();
        assertThat(failed.settlementId()).isNull();

        // 2. 원인이 그대로인 채 관리자가 재집계 → 다시 실패, 같은 기록의 횟수·처리자만 갱신
        LocalDateTime retryAt = AGGREGATED_AT.plusHours(8);
        clock.set(retryAt);
        SettlementReaggregationResponse retry = adminSettlementService.reaggregate(NOV_5, chickenStore.id(), "admin");

        assertThat(retry.failed()).isEqualTo(1);
        assertThat(retry.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(item.error()).contains(BLOCK_CONSTRAINT);
        });
        SettlementAggregationFailureResponse failedAgain = singleFailure();
        assertThat(failedAgain.id()).isEqualTo(failed.id());
        assertThat(failedAgain.failureCount()).isEqualTo(2);
        assertThat(failedAgain.firstFailedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(failedAgain.lastFailedAt()).isEqualTo(retryAt);
        assertThat(failedAgain.lastFailedBy()).isEqualTo(new SettlementActorResponse(adminId, "admin"));

        // 3. 원인 해결 후 재집계 → 정산 생성, 실패 기록은 해결 처리 (처리자 = 재집계한 관리자)
        unblockAggregation();
        LocalDateTime recoveredAt = AGGREGATED_AT.plusHours(9);
        clock.set(recoveredAt);
        SettlementReaggregationResponse recovered = adminSettlementService.reaggregate(NOV_5, chickenStore.id(), "admin2");

        assertThat(recovered.created()).isEqualTo(1);
        Settlement settlement = settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), NOV_5).orElseThrow();
        assertThat(settlement.getSettlementAmount()).isEqualTo(9500L);
        SettlementAggregationFailureResponse resolved = singleFailure();
        assertThat(resolved.status()).isEqualTo(SettlementAggregationFailureStatus.RESOLVED);
        assertThat(resolved.failureCount()).isEqualTo(2);
        assertThat(resolved.resolvedAt()).isEqualTo(recoveredAt);
        assertThat(resolved.resolvedBy()).isEqualTo(new SettlementActorResponse(admin2Id, "admin2"));
        assertThat(resolved.settlementId()).isEqualTo(settlement.getId());
        assertThat(adminSettlementService.getAggregationFailures(SettlementAggregationFailureStatus.FAILED, "admin"))
                .isEmpty();
    }

    @Test
    void 실패한_집계가_다음_스케줄러_실행에서_성공하면_실패_기록이_해결_처리된다() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        blockAggregation();
        clock.set(AGGREGATED_AT);
        settlementScheduler.aggregateDailySettlements();
        assertThat(singleFailure().status()).isEqualTo(SettlementAggregationFailureStatus.FAILED);

        unblockAggregation();
        clock.set(AGGREGATED_AT.plusDays(1));
        settlementScheduler.aggregateDailySettlements();

        SettlementAggregationFailureResponse resolved = singleFailure();
        assertThat(resolved.status()).isEqualTo(SettlementAggregationFailureStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(AGGREGATED_AT.plusDays(1));
        assertThat(resolved.resolvedBy()).isNull(); // 스케줄러가 해결
        assertThat(resolved.settlementId()).isNotNull();
        assertThat(settlementRepository.count()).isEqualTo(1);
    }

    @Test
    void 한_매장이_실패해도_다른_날짜의_정산은_만들어지고_실패한_날짜만_기록된다() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 4, 12, 0));
        deliveredOrder(snackStore, 7000, LocalDateTime.of(2026, 11, 5, 12, 0));
        blockAggregation(); // 11/5만 실패

        clock.set(AGGREGATED_AT);
        settlementScheduler.aggregateDailySettlements();

        assertThat(settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), LocalDate.of(2026, 11, 4)))
                .isPresent();
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(singleFailure().storeId()).isEqualTo(snackStore.id());
    }

    // ---- 동시 실행 ----

    @Test
    void 같은_재집계를_동시에_여러_번_요청해도_정산과_이력은_매장당_1건이다() throws Exception {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        deliveredOrder(snackStore, 7000, LocalDateTime.of(2026, 11, 5, 13, 0));
        clock.set(AGGREGATED_AT);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String admin = (i % 2 == 0) ? "admin" : "admin2";
            tasks.add(() -> adminSettlementService.reaggregate(NOV_5, null, admin).created());
        }
        int created = runConcurrently(tasks).stream().mapToInt(Integer::intValue).sum();

        assertThat(created).isEqualTo(2); // 매장 2곳 × 한 번씩만 성공
        assertSingleSettlementPerStore(2);
    }

    @Test
    void 스케줄러와_수동_재집계가_동시에_실행돼도_정산과_이력은_매장당_1건이다() throws Exception {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        deliveredOrder(snackStore, 7000, LocalDateTime.of(2026, 11, 5, 13, 0));
        clock.set(AGGREGATED_AT);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                settlementScheduler.aggregateDailySettlements();
                return 0;
            });
            tasks.add(() -> adminSettlementService.reaggregate(NOV_5, null, "admin").failed());
        }
        int failed = runConcurrently(tasks).stream().mapToInt(Integer::intValue).sum();

        assertThat(failed).isZero(); // 경합에서 진 쪽은 실패가 아니라 "이미 있음"으로 건너뜀
        assertSingleSettlementPerStore(2);
    }

    // 모든 작업을 동시에 시작해 결과를 모음 — 예외가 나면 테스트 실패
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertSingleSettlementPerStore(int stores) {
        assertThat(settlementRepository.count()).isEqualTo(stores);
        assertThat(settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), NOV_5)).isPresent();
        assertThat(settlementRepository.findByStoreIdAndSettlementDate(snackStore.id(), NOV_5)).isPresent();
        // 집계 이력(→ PENDING)도 정산마다 정확히 1건, 실패 기록은 없음
        assertThat(settlementStatusHistoryRepository.count()).isEqualTo(stores);
        assertThat(settlementAggregationFailureRepository.count()).isZero();
    }

    // ---- 재집계 대상 제한 ----

    @Test
    void 이미_정산이_있는_매장은_PENDING_CONFIRMED_PAID_모두_재집계가_막히고_정산은_바뀌지_않는다() {
        Long id = scheduledSettlementId();

        clock.set(CONFIRMED_AT.minusHours(1));
        assertReaggregationRejected("PENDING");

        clock.set(CONFIRMED_AT);
        adminSettlementService.confirm(id, "admin");
        assertReaggregationRejected("CONFIRMED");

        clock.set(PAID_AT);
        adminSettlementService.pay(id, "admin");
        Settlement paid = settlementRepository.findById(id).orElseThrow();
        clock.set(PAID_AT.plusDays(1));
        assertReaggregationRejected("PAID");

        // 날짜 전체 재집계는 건너뛰기만 함
        assertThat(adminSettlementService.reaggregate(NOV_5, null, "admin").created()).isZero();

        // 지급완료 정산은 그대로 — 금액·지급 시각·이력 모두 변화 없음 (중복 지급 없음)
        Settlement reloaded = settlementRepository.findById(id).orElseThrow();
        assertThat(settlementRepository.count()).isEqualTo(1);
        assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(reloaded.getSettlementAmount()).isEqualTo(9500L);
        assertThat(reloaded.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(paid.getUpdatedAt());
        assertThat(settlementStatusHistoryRepository.count()).isEqualTo(3);
        assertThat(settlementAggregationFailureRepository.count()).isZero();
    }

    private void assertReaggregationRejected(String status) {
        assertThatThrownBy(() -> adminSettlementService.reaggregate(NOV_5, chickenStore.id(), "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class)
                .hasMessageContaining("현재 상태: " + status);
    }

    @Test
    void 아직_집계할_수_없는_날짜와_없는_매장은_재집계할_수_없다() {
        deliveredOrder(chickenStore, 10000, LocalDateTime.of(2026, 11, 5, 12, 0));

        // 11/6 00:30 → 11/5는 01:00부터 집계 가능
        clock.set(LocalDateTime.of(2026, 11, 6, 0, 30));
        assertThatThrownBy(() -> adminSettlementService.reaggregate(NOV_5, null, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        clock.set(AGGREGATED_AT);
        assertThatThrownBy(() -> adminSettlementService.reaggregate(NOV_5, Long.MAX_VALUE, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(settlementRepository.count()).isZero();
        assertThat(settlementAggregationFailureRepository.count()).isZero();
    }

    @Test
    void 집계할_것이_없는_매장을_재집계하면_정산을_만들지_않는다() {
        clock.set(AGGREGATED_AT);

        SettlementReaggregationResponse response = adminSettlementService.reaggregate(NOV_5, chickenStore.id(), "admin");

        assertThat(response.results()).extracting(SettlementReaggregationResponse.StoreItem::outcome)
                .containsExactly(Outcome.NO_TARGET);
        assertThat(settlementRepository.count()).isZero();
    }

    @Test
    void 관리자가_아니면_재집계와_감사_기록_조회를_할_수_없다() {
        Long id = scheduledSettlementId();
        deliveredOrder(snackStore, 7000, LocalDateTime.of(2026, 11, 5, 13, 0)); // 아직 집계 안 된 매장
        clock.set(AGGREGATED_AT.plusHours(1));

        for (String username : new String[]{"owner1", "customer1"}) {
            assertThatThrownBy(() -> adminSettlementService.reaggregate(NOV_5, null, username))
                    .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
            assertThatThrownBy(() -> adminSettlementService.getHistories(id, username))
                    .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
            assertThatThrownBy(() -> adminSettlementService.getAggregationFailures(null, username))
                    .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(settlementRepository.findByStoreIdAndSettlementDate(snackStore.id(), NOV_5)).isEmpty();
    }

    // ---- 감사 이력 ----

    @Test
    void 확정과_지급의_처리자와_시각이_이력에_기록되고_인증된_관리자와_일치한다() {
        Long id = scheduledSettlementId();

        clock.set(CONFIRMED_AT);
        SettlementResponse confirmed = adminSettlementService.confirm(id, "admin");
        assertThat(confirmed.confirmedBy()).isEqualTo(new SettlementActorResponse(adminId, "admin"));
        assertThat(confirmed.paidBy()).isNull();

        // 지급은 다른 관리자가 처리
        clock.set(PAID_AT);
        SettlementResponse paid = adminSettlementService.pay(id, "admin2");
        assertThat(paid.confirmedBy()).isEqualTo(new SettlementActorResponse(adminId, "admin"));
        assertThat(paid.paidBy()).isEqualTo(new SettlementActorResponse(admin2Id, "admin2"));

        // 이력 조회 — 집계(스케줄러) → 확정(admin) → 지급(admin2), 시각은 정산의 확정·지급 시각과 같음
        List<SettlementStatusHistoryResponse> histories = adminSettlementService.getHistories(id, "admin");
        assertThat(histories).extracting(SettlementStatusHistoryResponse::fromStatus)
                .containsExactly(null, SettlementStatus.PENDING, SettlementStatus.CONFIRMED);
        assertThat(histories).extracting(SettlementStatusHistoryResponse::toStatus)
                .containsExactly(SettlementStatus.PENDING, SettlementStatus.CONFIRMED, SettlementStatus.PAID);
        assertThat(histories).extracting(SettlementStatusHistoryResponse::actor)
                .containsExactly(null, new SettlementActorResponse(adminId, "admin"),
                        new SettlementActorResponse(admin2Id, "admin2"));
        assertThat(histories).extracting(SettlementStatusHistoryResponse::changedAt)
                .containsExactly(AGGREGATED_AT, CONFIRMED_AT, PAID_AT);

        // DB에 저장된 처리자 id가 인증된 관리자의 id와 같음
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM settlement_status_histories "
                + "WHERE settlement_id = ? AND to_status = 'CONFIRMED'", Long.class, id)).isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM settlement_status_histories "
                + "WHERE settlement_id = ? AND to_status = 'PAID'", Long.class, id)).isEqualTo(admin2Id);

        // 기존 목록 조회 응답에서도 확인 가능
        assertThat(adminSettlementService.getSettlements(NOV_5, null, "admin2")).singleElement().satisfies(s -> {
            assertThat(s.confirmedAt()).isEqualTo(CONFIRMED_AT);
            assertThat(s.paidAt()).isEqualTo(PAID_AT);
            assertThat(s.confirmedBy().username()).isEqualTo("admin");
            assertThat(s.paidBy().username()).isEqualTo("admin2");
        });
    }

    @Test
    void 거부된_전이와_중복_지급은_이력을_남기지_않고_처음_처리자가_유지된다() {
        Long id = scheduledSettlementId();

        // 확정 전 지급 → 409, 이력은 집계 1건뿐
        clock.set(CONFIRMED_AT);
        assertThatThrownBy(() -> adminSettlementService.pay(id, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(settlementStatusHistoryRepository.count()).isEqualTo(1);

        adminSettlementService.confirm(id, "admin");
        clock.set(PAID_AT);
        adminSettlementService.pay(id, "admin");

        // 다른 관리자의 중복 확정·지급 → 409, 이력·처리자 그대로
        clock.set(PAID_AT.plusDays(1));
        assertThatThrownBy(() -> adminSettlementService.confirm(id, "admin2"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThatThrownBy(() -> adminSettlementService.pay(id, "admin2"))
                .isInstanceOf(InvalidSettlementStatusException.class);

        List<SettlementStatusHistoryResponse> histories = adminSettlementService.getHistories(id, "admin");
        assertThat(histories).hasSize(3);
        assertThat(histories.get(2).actor().username()).isEqualTo("admin");
        assertThat(histories.get(2).changedAt()).isEqualTo(PAID_AT);
    }

    @Test
    void 같은_정산을_두_관리자가_동시에_지급해도_지급_이력은_1건이다() throws Exception {
        Long id = scheduledSettlementId();
        clock.set(CONFIRMED_AT);
        adminSettlementService.confirm(id, "admin");
        clock.set(PAID_AT);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String admin = (i % 2 == 0) ? "admin" : "admin2";
            tasks.add(() -> {
                try {
                    return adminSettlementService.pay(id, admin).paidBy().username();
                } catch (InvalidSettlementStatusException e) {
                    return null; // 행 잠금으로 기다린 뒤 PAID를 읽고 거부됨
                }
            });
        }
        List<String> winners = runConcurrently(tasks).stream().filter(java.util.Objects::nonNull).toList();

        assertThat(winners).hasSize(1);
        List<SettlementStatusHistoryResponse> histories = adminSettlementService.getHistories(id, "admin");
        assertThat(histories).hasSize(3);
        // 이력의 지급자 = 실제로 지급에 성공한 관리자
        assertThat(histories.get(2).actor().username()).isEqualTo(winners.get(0));
    }

    @Test
    void 없는_정산의_이력_조회는_404() {
        assertThatThrownBy(() -> adminSettlementService.getHistories(Long.MAX_VALUE, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}

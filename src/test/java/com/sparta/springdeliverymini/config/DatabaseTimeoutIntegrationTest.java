package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 커넥션 풀·DB 타임아웃 설정(application.yml spring.datasource.hikari)이 실제 PostgreSQL 커넥션에 적용되는지 확인
// - 설정값: 풀에서 꺼낸 커넥션의 lock_timeout·statement_timeout (pgjdbc options로 전달)
// - 동작: 다른 트랜잭션이 잡은 행 잠금을 기다리다 시간이 넘으면 무한 대기 대신 예외
//   → GlobalExceptionHandler가 503으로 바꾸는 예외 종류와 같은지 확인 (응답 형식은 ErrorResponseFormatTest)
// 동작 테스트는 기다리는 시간을 줄이려고 그 트랜잭션 안에서만 SET LOCAL로 타임아웃을 짧게 지정 (다른 테스트·커넥션에 영향 없음)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseTimeoutIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StoreRepository storeRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // FK 순서대로 삭제: stores → users
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void 풀의_커넥션에_잠금_대기와_문장_실행_타임아웃이_적용된다() {
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
        assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
    }

    @Test
    void 커넥션_풀_크기와_대기_시간이_설정값과_같다() {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000);
    }

    @Test
    void 다른_트랜잭션이_잡은_행_잠금을_lock_timeout보다_오래_기다리면_실패한다() throws Exception {
        Long storeId = saveStore();

        try (HeldLock ignored = holdStoreLock(storeId)) {
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery("SET LOCAL lock_timeout = '300ms'").executeUpdate();
                storeRepository.findByIdForUpdate(storeId);
            })).isInstanceOf(PessimisticLockingFailureException.class);
        }

        // 잠금이 풀린 뒤에는 정상적으로 잠금을 얻음 (실패한 트랜잭션이 커넥션·잠금을 남기지 않음)
        transactionTemplate.executeWithoutResult(status ->
                assertThat(storeRepository.findByIdForUpdate(storeId)).isPresent());
    }

    @Test
    void 문장_실행이_statement_timeout을_넘으면_실패한다() throws Exception {
        Long storeId = saveStore();

        // 잠금 대기 시간도 문장 실행 시간에 포함됨 → lock_timeout을 끄고 statement_timeout만으로 끊기는지 확인
        try (HeldLock ignored = holdStoreLock(storeId)) {
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery("SET LOCAL lock_timeout = 0").executeUpdate();
                entityManager.createNativeQuery("SET LOCAL statement_timeout = '300ms'").executeUpdate();
                storeRepository.findByIdForUpdate(storeId);
            })).isInstanceOf(QueryTimeoutException.class);
        }
    }

    private Long saveStore() {
        User owner = userRepository.save(new User("timeout-owner", "encoded-password", Role.OWNER));
        return storeRepository.save(new Store("타임아웃 테스트 매장", owner)).getId();
    }

    // 다른 스레드의 트랜잭션에서 매장 행 잠금을 잡고, close() 때까지 유지
    private HeldLock holdStoreLock(Long storeId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            storeRepository.findByIdForUpdate(storeId);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("잠금을 잡는 트랜잭션이 시작됨").isTrue();
        return () -> {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS); // 잠금을 잡은 트랜잭션이 커밋될 때까지 기다림
        };
    }

    private interface HeldLock extends AutoCloseable {
    }
}

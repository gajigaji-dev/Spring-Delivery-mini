package com.sparta.springdeliverymini.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V18(배송 주소 분리)이 기존 주문 데이터를 잃지 않고 적용되는지 검증 (#77)
// V17까지 적용한 DB에 한 줄 주소만 있는 주문을 넣고 → 최신 버전 적용 후 원래 값 유지·새 컬럼 NULL 확인
class OrderAddressMigrationTest {

    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // 1. V17까지 적용 (주소는 delivery_address 한 줄)
        flyway(dataSource, "17").migrate();

        // 2. 기존 주문
        jdbc.update("INSERT INTO users (id, password, role, username) VALUES "
                + "(1, 'pw', 'OWNER', 'owner1'), (2, 'pw', 'CUSTOMER', 'customer1')");
        jdbc.update("INSERT INTO stores (id, name, owner_id) VALUES (1, '치킨집', 1)");
        jdbc.update("INSERT INTO orders (id, delivery_address, status, total_price, user_id, store_id) VALUES "
                + "(1, '서울시 강남구 어딘가 3층', 'ORDER_REQUEST', 18000, 2, 1)");

        // 3. 최신 버전까지 적용
        flyway(dataSource, "latest").migrate();
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void 기존_주문의_한_줄_주소는_그대로_남고_나눈_주소는_NULL이다() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT delivery_address, zip_code, road_address, detail_address FROM orders WHERE id = 1");

        assertThat(row.get("delivery_address")).isEqualTo("서울시 강남구 어딘가 3층");
        assertThat(row.get("zip_code")).isNull();
        assertThat(row.get("road_address")).isNull();
        assertThat(row.get("detail_address")).isNull();
    }

    @Test
    void 우편번호와_도로명이_있으면_상세_주소_없이도_저장된다() {
        jdbc.update("INSERT INTO orders (id, delivery_address, zip_code, road_address, status, total_price, user_id, store_id) "
                + "VALUES (2, '서울 강남구 테헤란로 152', '06236', '서울 강남구 테헤란로 152', 'ORDER_REQUEST', 18000, 2, 1)");

        assertThat(jdbc.queryForObject("SELECT zip_code FROM orders WHERE id = 2", String.class)).isEqualTo("06236");
    }

    @Test
    void 우편번호가_숫자_5자리가_아니면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET zip_code = '1234', road_address = '서울' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("orders_address_parts_check");
    }

    @Test
    void 우편번호만_있고_도로명이_없으면_DB가_거부한다() {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET zip_code = '06236' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("orders_address_parts_check");
    }
}

package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.event.DltReprocessor.Result;
import com.sparta.springdeliverymini.lock.DistributedLock;
import com.sparta.springdeliverymini.lock.LockUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

// DLT 재처리 CLI (#52, 사용법은 docs/architecture.md Kafka 장 "DLT 재처리")
// dlt-reprocess 프로필로 띄우면 웹 서버·Consumer·주기 작업 없이 재처리만 하고 종료 (application-dlt-reprocess.yml)
//
//   --all                          아직 처리하지 않은 DLT 메시지 전체
//   --partition=N --offset=M       DLT 파티션 N의 오프셋 M 한 건
//
// 종료 코드: 0 성공 / 1 재처리 실패·이미 실행 중·Redis 장애 / 2 인자 오류
// - 관리자 API로 두지 않은 이유: 원인을 고친 뒤 운영자가 드물게 실행하는 작업이라 상시 노출할 필요가 없음
// - 분산 락: 동시에 두 번 실행되면 같은 메시지를 두 번 보내고 진행 위치 기록이 섞임 → 하나만 실행
//   Redis 장애로 락을 확인할 수 없으면 실행하지 않음 (스케줄러와 같은 원칙)
@Slf4j
@Component
@Profile(DltReprocessRunner.PROFILE)
public class DltReprocessRunner implements ApplicationRunner {

    public static final String PROFILE = "dlt-reprocess";
    static final String LOCK_NAME = "dlt-reprocess";
    // DLT는 평소 비어 있어 수 분이면 끝남 → 넉넉히 잡음 (넘기면 RedisDistributedLock이 경고 로그)
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(30);

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    private static final String USAGE = "사용법: --all | --partition=<번호> --offset=<오프셋>";

    private final DltReprocessor reprocessor;
    private final DistributedLock lock;
    private final ConfigurableApplicationContext context;

    public DltReprocessRunner(DltReprocessor reprocessor, DistributedLock lock, ConfigurableApplicationContext context) {
        this.reprocessor = reprocessor;
        this.lock = lock;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute(args);
        // 종료 코드를 셸에 넘기고 끝냄 (Kafka·Redis 클라이언트 스레드가 남아 프로세스가 끝나지 않는 것도 막음)
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    // 종료 코드만 돌려줌 (테스트에서 프로세스를 끝내지 않고 확인)
    int execute(ApplicationArguments args) {
        Command command;
        try {
            command = Command.parse(args);
        } catch (IllegalArgumentException e) {
            log.error("{} — {}", e.getMessage(), USAGE);
            return EXIT_USAGE;
        }

        AtomicReference<Result> result = new AtomicReference<>();
        try {
            boolean acquired = lock.executeIfAcquired(LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO,
                    () -> result.set(command.all()
                            ? reprocessor.reprocessAll()
                            : reprocessor.reprocessOne(command.partition(), command.offset())));
            if (!acquired) {
                log.error("DLT 재처리가 이미 실행 중입니다 — 끝난 뒤 다시 실행하세요.");
                return EXIT_FAILED;
            }
        } catch (LockUnavailableException e) {
            log.error("Redis 장애로 실행 락을 확인할 수 없어 재처리하지 않습니다.", e);
            return EXIT_FAILED;
        } catch (IllegalArgumentException e) {
            log.error("{} — {}", e.getMessage(), USAGE);
            return EXIT_USAGE;
        } catch (RuntimeException e) {
            // DLT 토픽 없음·Kafka 접속 실패 등 — 보낸 건까지만 진행 위치가 기록돼 있어 다시 실행하면 이어서 처리
            log.error("DLT 재처리 실패", e);
            return EXIT_FAILED;
        }

        Result done = result.get();
        if (done.succeeded()) {
            log.info("DLT 재처리 완료 — 재발행 {}건, 건너뜀 {}건", done.republished(), done.skipped());
            return EXIT_OK;
        }
        log.error("DLT 재처리 중단 — 재발행 {}건, 건너뜀 {}건, 실패 partition={}, offset={}, 원인: {} (원인 해결 후 다시 실행하면 이 건부터 이어서 처리)",
                done.republished(), done.skipped(), done.failure().partition(), done.failure().offset(),
                done.failure().reason());
        return EXIT_FAILED;
    }

    record Command(boolean all, int partition, long offset) {

        static Command parse(ApplicationArguments args) {
            if (!args.getNonOptionArgs().isEmpty()) {
                throw new IllegalArgumentException("알 수 없는 인자: " + args.getNonOptionArgs());
            }
            boolean all = args.containsOption("all");
            boolean partition = args.containsOption("partition");
            boolean offset = args.containsOption("offset");
            if (all && (partition || offset)) {
                throw new IllegalArgumentException("--all과 --partition·--offset은 함께 쓸 수 없습니다.");
            }
            if (all) {
                return new Command(true, 0, 0);
            }
            if (!partition || !offset) {
                throw new IllegalArgumentException("--all 또는 --partition과 --offset을 함께 지정하세요.");
            }
            int partitionNumber = (int) number(args, "partition");
            long offsetNumber = number(args, "offset");
            return new Command(false, partitionNumber, offsetNumber);
        }

        private static long number(ApplicationArguments args, String name) {
            List<String> values = args.getOptionValues(name);
            if (values.size() != 1) {
                throw new IllegalArgumentException("--" + name + " 값을 하나만 지정하세요.");
            }
            long value;
            try {
                value = Long.parseLong(values.getFirst().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + "는 숫자여야 합니다: " + values.getFirst());
            }
            if (value < 0 || ("partition".equals(name) && value > Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("--" + name + "는 0 이상이어야 합니다: " + value);
            }
            return value;
        }
    }
}

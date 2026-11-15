package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.event.DltReprocessor.Failure;
import com.sparta.springdeliverymini.event.DltReprocessor.Result;
import com.sparta.springdeliverymini.lock.DistributedLock;
import com.sparta.springdeliverymini.lock.LockUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// DLT 재처리 CLI의 인자 해석·락·종료 코드 (#52)
// 실제 재발행 동작은 DltReprocessIntegrationTest
@ExtendWith(MockitoExtension.class)
class DltReprocessRunnerTest {

    @Mock private DltReprocessor reprocessor;
    @Mock private DistributedLock lock;
    @Mock private ConfigurableApplicationContext context;

    private int execute(String... args) {
        return new DltReprocessRunner(reprocessor, lock, context).execute(new DefaultApplicationArguments(args));
    }

    // 락을 잡은 것처럼 작업을 바로 실행
    private void lockAcquired() {
        given(lock.executeIfAcquired(eq(DltReprocessRunner.LOCK_NAME), eq(DltReprocessRunner.LOCK_AT_MOST_FOR),
                eq(Duration.ZERO), any())).willAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return true;
        });
    }

    @Test
    void 전체_재처리가_성공하면_종료_코드_0() {
        lockAcquired();
        given(reprocessor.reprocessAll()).willReturn(new Result(3, 1, null));

        assertThat(execute("--all")).isEqualTo(DltReprocessRunner.EXIT_OK);
        verify(reprocessor, never()).reprocessOne(anyInt(), anyLong());
    }

    @Test
    void 파티션과_오프셋을_지정하면_그_한_건만_재처리한다() {
        lockAcquired();
        given(reprocessor.reprocessOne(2, 15L)).willReturn(new Result(1, 0, null));

        assertThat(execute("--partition=2", "--offset=15")).isEqualTo(DltReprocessRunner.EXIT_OK);
        verify(reprocessor, never()).reprocessAll();
    }

    @Test
    void 재처리가_중간에_실패하면_종료_코드_1() {
        lockAcquired();
        given(reprocessor.reprocessAll()).willReturn(new Result(1, 0, new Failure(0, 5L, "재발행 실패")));

        assertThat(execute("--all")).isEqualTo(DltReprocessRunner.EXIT_FAILED);
    }

    @Test
    void 지정한_오프셋이_DLT에_없으면_종료_코드_2() {
        lockAcquired();
        given(reprocessor.reprocessOne(0, 99L)).willThrow(new IllegalArgumentException("DLT 파티션 0에 없는 오프셋입니다: 99"));

        assertThat(execute("--partition=0", "--offset=99")).isEqualTo(DltReprocessRunner.EXIT_USAGE);
    }

    @Test
    void 이미_다른_재처리가_실행_중이면_재처리하지_않고_종료_코드_1() {
        given(lock.executeIfAcquired(anyString(), any(), any(), any())).willReturn(false);

        assertThat(execute("--all")).isEqualTo(DltReprocessRunner.EXIT_FAILED);
        verifyNoInteractions(reprocessor);
    }

    @Test
    void Redis_장애로_락을_확인할_수_없으면_재처리하지_않고_종료_코드_1() {
        given(lock.executeIfAcquired(anyString(), any(), any(), any()))
                .willThrow(new LockUnavailableException("분산 락 상태를 확인할 수 없습니다", new QueryTimeoutException("timeout")));

        assertThat(execute("--all")).isEqualTo(DltReprocessRunner.EXIT_FAILED);
        verifyNoInteractions(reprocessor);
    }

    @Test
    void DLT_토픽이_없는_등_Kafka_오류면_종료_코드_1() {
        lockAcquired();
        given(reprocessor.reprocessAll()).willThrow(new IllegalStateException("DLT 토픽을 찾을 수 없습니다"));

        assertThat(execute("--all")).isEqualTo(DltReprocessRunner.EXIT_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "--all --partition=0", "--partition=0", "--offset=3", "--partition=a --offset=1",
            "--partition=-1 --offset=0", "--partition=0 --offset=1 --offset=2", "all"})
    void 인자가_잘못되면_재처리하지_않고_종료_코드_2(String args) {
        assertThat(execute(args.isEmpty() ? new String[0] : args.split(" "))).isEqualTo(DltReprocessRunner.EXIT_USAGE);
        verifyNoInteractions(lock, reprocessor);
    }
}

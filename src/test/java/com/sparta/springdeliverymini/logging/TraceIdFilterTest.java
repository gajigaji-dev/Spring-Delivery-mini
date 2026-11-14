package com.sparta.springdeliverymini.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// TraceIdFilter — traceId 생성·전달, 잘못된 외부 값 거부, 요청 후 MDC 정리 (Spring 컨텍스트 없이)
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    // 체인 안(컨트롤러·서비스 실행 시점)에서 MDC에 들어 있던 traceId를 기록
    private String runAndCaptureMdc(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(TraceIdFilter.MDC_KEY));
            }
        });
        return seen.get();
    }

    @Test
    void 헤더가_없으면_traceId를_새로_만들어_MDC와_응답_헤더에_같은_값을_넣는다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inChain = runAndCaptureMdc(new MockHttpServletRequest("GET", "/api/stores"), response);

        assertThat(inChain).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(inChain);
    }

    @Test
    void 형식이_올바른_외부_traceId는_그대로_이어서_쓴다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stores");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "gateway-trace-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inChain = runAndCaptureMdc(request, response);

        assertThat(inChain).isEqualTo("gateway-trace-1234");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("gateway-trace-1234");
    }

    @Test
    void 줄바꿈이나_너무_긴_외부_traceId는_무시하고_새로_만든다() {
        // 로그 위조(가짜 로그 줄 삽입)·과도한 길이 방지
        assertThat(TraceIdFilter.resolveTraceId("abc12345\nFAKE LOG")).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolveTraceId("a".repeat(65))).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolveTraceId("short")).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolveTraceId("")).matches("[0-9a-f]{32}");
    }

    @Test
    void 요청이_끝나면_MDC에서_traceId를_지운다() throws Exception {
        runAndCaptureMdc(new MockHttpServletRequest("GET", "/api/stores"), new MockHttpServletResponse());

        // 스레드 재사용 시 다음 요청 로그에 이전 traceId가 섞이지 않아야 함
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 요청마다_다른_traceId가_만들어진다() throws Exception {
        String first = runAndCaptureMdc(new MockHttpServletRequest("GET", "/a"), new MockHttpServletResponse());
        String second = runAndCaptureMdc(new MockHttpServletRequest("GET", "/a"), new MockHttpServletResponse());

        assertThat(first).isNotEqualTo(second);
    }
}

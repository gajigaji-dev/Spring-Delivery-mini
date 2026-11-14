package com.sparta.springdeliverymini.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 traceId를 정해 MDC에 넣고, 응답 헤더(X-Trace-Id)로 돌려준다.
 * - 로그 패턴(application.yml logging.pattern.correlation)이 MDC의 traceId를 출력 → 한 요청의 로그를 traceId로 묶어 볼 수 있음
 * - 에러 응답 본문(ApiErrorResponse)에도 같은 traceId가 들어가 사용자 문의 → 서버 로그 추적이 가능
 * - Spring Security 필터보다 먼저 실행(HIGHEST_PRECEDENCE)해야 401/403 응답과 그 로그에도 traceId가 붙음
 * 외부 추적 시스템(micrometer-tracing 등)은 쓰지 않음: 단일 서비스라 요청 단위 식별자만으로 충분하고 의존성을 늘리지 않기 위해
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    // 앞단(게이트웨이·클라이언트)이 보낸 traceId는 이어서 쓰되, 형식이 맞을 때만 사용
    // 줄바꿈 등 임의 문자열을 그대로 로그에 쓰면 로그 위조(log injection)가 가능하므로 영문·숫자·하이픈 8~64자만 허용
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(MDC_KEY, traceId);
        // 체인 실행 전에 넣어야 Security가 바로 응답을 커밋하는 401/403에도 헤더가 붙음
        response.setHeader(TRACE_ID_HEADER, traceId);

        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!isMonitoringRequest(request)) {
                // 쿼리 문자열·헤더·본문은 남기지 않음 (토큰·개인정보가 섞일 수 있어 경로와 결과만 기록)
                log.info("HTTP {} {} status={} durationMs={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(),
                        (System.nanoTime() - start) / 1_000_000);
            }
            // 스레드 풀 재사용 시 다른 요청 로그에 traceId가 남지 않도록 반드시 제거
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveTraceId(String incoming) {
        if (incoming != null && VALID_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return newTraceId();
    }

    // HTTP 요청 밖의 작업(스케줄러 등)도 같은 형식의 traceId를 쓰도록 공개
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // Prometheus가 수 초마다 긁는 요청까지 남기면 업무 로그가 묻히므로 Actuator 요청은 요청 로그에서 제외
    private boolean isMonitoringRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}

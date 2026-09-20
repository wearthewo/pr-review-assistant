package io.prreviewassistant.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsOnlyBoundedSafeInboundValueAndClearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "request_123.safe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                inside.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(inside).hasValue("request_123.safe");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("request_123.safe");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesControlBearingOrOversizedInputWithGeneratedValue() throws Exception {
        for (String invalid : new String[] {"bad\r\nheader", "x".repeat(65), " contains-space"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CorrelationIdFilter.HEADER, invalid);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

            String generated = response.getHeader(CorrelationIdFilter.HEADER);
            assertThat(generated).matches("[0-9a-f-]{36}").isNotEqualTo(invalid);
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        }
    }

    @Test
    void restoresPreexistingContextRatherThanLeakingRequestValue() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotEqualTo("outer"));

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer");
    }
}

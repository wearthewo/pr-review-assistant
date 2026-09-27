package io.prreviewassistant.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.prreviewassistant.identity.DashboardSessionFailureException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DashboardSessionControllerAdviceTest {
    @Test
    void boundedStageDiagnosticExcludesCauseAndIdentityData() {
        Logger logger = (Logger) LoggerFactory.getLogger(DashboardSessionControllerAdvice.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var exception = new DashboardSessionFailureException(
                    DashboardSessionFailureException.Stage.APPLICATION_USER_PROVISIONING,
                    new IllegalStateException("jdbc:postgresql://user:password@host/database sensitive-subject"));

            var response = new DashboardSessionControllerAdvice().handleSessionFailure(exception);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).isNull();
            String rendered = appender.list.stream()
                    .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(rendered)
                    .contains("dashboard_session_failed", "APPLICATION_USER_PROVISIONING")
                    .doesNotContain("jdbc:postgresql", "password", "sensitive-subject", "Authorization", "Bearer");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

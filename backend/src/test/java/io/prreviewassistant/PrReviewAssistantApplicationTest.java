package io.prreviewassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://invalid:5432/invalid",
        "DB_USERNAME=test",
        "DB_PASSWORD=test"
})
@Import(PostgreSqlTestConfiguration.class)
class PrReviewAssistantApplicationTest {

    @Test
    void contextLoads() {
    }
}

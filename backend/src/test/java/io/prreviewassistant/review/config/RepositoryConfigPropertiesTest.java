package io.prreviewassistant.review.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryConfigPropertiesTest {
    @Test
    void permitsConfiguredLimitOnlyInsideHardCeiling() {
        assertThat(new RepositoryConfigProperties(DataSize.ofKilobytes(32)).maxBytes())
                .isEqualTo(32 * 1024);
        assertThat(new RepositoryConfigProperties(DataSize.ofKilobytes(64)).maxBytes())
                .isEqualTo(64 * 1024);
        assertThatThrownBy(() -> new RepositoryConfigProperties(DataSize.ofBytes(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryConfigProperties(DataSize.ofKilobytes(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

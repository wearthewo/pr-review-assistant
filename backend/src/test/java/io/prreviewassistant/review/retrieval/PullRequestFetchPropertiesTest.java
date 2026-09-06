package io.prreviewassistant.review.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class PullRequestFetchPropertiesTest {

    @Test
    void exposesValidatedByteLimits() {
        PullRequestFetchProperties properties = new PullRequestFetchProperties(
                1000, DataSize.ofKilobytes(256), DataSize.ofMegabytes(5), 10, DataSize.ofMegabytes(8));

        assertThat(properties.maxPatchBytesPerFile()).isEqualTo(256 * 1024);
        assertThat(properties.maxTotalPatchBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(properties.maxPageResponseBytes()).isEqualTo(8 * 1024 * 1024);
    }

    @Test
    void rejectsInvalidOrInconsistentLimits() {
        assertThatThrownBy(() -> new PullRequestFetchProperties(
                0, DataSize.ofBytes(1), DataSize.ofBytes(1), 1, DataSize.ofBytes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PullRequestFetchProperties(
                1, DataSize.ofBytes(2), DataSize.ofBytes(1), 1, DataSize.ofBytes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PullRequestFetchProperties(
                1, null, DataSize.ofBytes(1), 1, DataSize.ofBytes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

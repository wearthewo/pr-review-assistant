package io.prreviewassistant.review.context;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ReviewContextPropertiesTest {
    @Test void acceptsConservativeBoundedConfiguration() {
        var p = new ReviewContextProperties(true, 12, DataSize.ofKilobytes(128),
                DataSize.ofKilobytes(512), DataSize.ofKilobytes(128), 200, 100, 20);
        assertThat(p.maxFileBytes()).isEqualTo(128 * 1024);
        assertThat(p.maxTotalBytes()).isEqualTo(512 * 1024);
    }
    @Test void rejectsUnlimitedOrInconsistentConfiguration() {
        assertThatThrownBy(() -> new ReviewContextProperties(true, 21, DataSize.ofBytes(1), DataSize.ofBytes(1), DataSize.ofBytes(1), 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewContextProperties(true, 1, DataSize.ofKilobytes(257), DataSize.ofKilobytes(512), DataSize.ofBytes(1), 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewContextProperties(true, 1, DataSize.ofBytes(2), DataSize.ofBytes(1), DataSize.ofBytes(1), 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}

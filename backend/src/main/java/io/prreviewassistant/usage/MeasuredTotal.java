package io.prreviewassistant.usage;

import java.util.OptionalLong;

public record MeasuredTotal(OptionalLong knownValue, boolean complete) {
    public MeasuredTotal {
        knownValue = knownValue == null ? OptionalLong.empty() : knownValue;
        if (knownValue.stream().anyMatch(value -> value < 0) || (complete && knownValue.isEmpty())) {
            throw new IllegalArgumentException("measured total is invalid");
        }
    }

    static MeasuredTotal from(long eventCount, long measuredCount, Long sum) {
        return new MeasuredTotal(sum == null ? OptionalLong.empty() : OptionalLong.of(sum),
                eventCount > 0 && eventCount == measuredCount);
    }
}

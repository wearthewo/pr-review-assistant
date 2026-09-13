package io.prreviewassistant.usage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

public record UsagePeriod(Instant start, Instant end) {
    public UsagePeriod {
        Objects.requireNonNull(start, "start is required");
        Objects.requireNonNull(end, "end is required");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("usage period must be non-empty");
        }
    }

    public static UsagePeriod utcMonthContaining(Instant instant) {
        Objects.requireNonNull(instant, "instant is required");
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        ZonedDateTime start = utc.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC);
        return new UsagePeriod(start.toInstant(), start.plusMonths(1).toInstant());
    }
}

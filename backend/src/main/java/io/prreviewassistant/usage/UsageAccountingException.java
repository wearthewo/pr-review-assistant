package io.prreviewassistant.usage;

import java.util.Objects;

public final class UsageAccountingException extends RuntimeException {
    private final UsageAccountingError error;

    public UsageAccountingException(UsageAccountingError error) {
        super(Objects.requireNonNull(error, "error is required").name());
        this.error = error;
    }

    public UsageAccountingError error() {
        return error;
    }
}

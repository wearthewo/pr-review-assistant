package io.prreviewassistant.review.job;

import java.util.Objects;
import java.util.regex.Pattern;

public record ReviewJobErrorCode(String value) {

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public ReviewJobErrorCode {
        Objects.requireNonNull(value, "value must not be null");
        if (!SAFE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("error code must be a bounded safe identifier");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

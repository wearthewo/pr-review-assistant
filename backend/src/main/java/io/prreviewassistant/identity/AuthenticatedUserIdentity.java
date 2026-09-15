package io.prreviewassistant.identity;

import java.util.Objects;

public record AuthenticatedUserIdentity(String issuer, String subject) {
    public static final int MAX_ISSUER_LENGTH = 2048;
    public static final int MAX_SUBJECT_LENGTH = 512;

    public AuthenticatedUserIdentity {
        issuer = requireBounded(issuer, MAX_ISSUER_LENGTH, "issuer");
        subject = requireBounded(subject, MAX_SUBJECT_LENGTH, "subject");
    }

    private static String requireBounded(String value, int maxLength, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank() || value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("authenticated identity is invalid");
        }
        return value;
    }

    @Override
    public String toString() {
        return "AuthenticatedUserIdentity[externalIdentity=<redacted>]";
    }
}

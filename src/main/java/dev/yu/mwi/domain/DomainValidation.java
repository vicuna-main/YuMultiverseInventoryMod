package dev.yu.mwi.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class DomainValidation {
    private static final Pattern SHARE_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]*(?::[a-z0-9][a-z0-9_./-]*)?");
    private static final Pattern GROUP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private DomainValidation() {
    }

    static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(fieldName + " must not contain a NUL character");
        }
        return normalized;
    }

    static String requireShareId(String value, String fieldName) {
        String normalized = requireText(value, fieldName).toLowerCase(Locale.ROOT);
        if (!SHARE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid share id: " + value);
        }
        return normalized;
    }

    static String requireGroupId(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!GROUP_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " is not a valid group id: " + value);
        }
        return normalized;
    }
}

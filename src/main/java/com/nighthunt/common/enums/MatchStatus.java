package com.nighthunt.common.enums;

/**
 * Type-safe enum for match status.
 */
public enum MatchStatus {
    LOBBY("LOBBY"),
    IN_GAME("IN_GAME"),
    FINISHED("FINISHED");

    private final String value;

    MatchStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MatchStatus fromString(String status) {
        if (status == null) return null;
        for (MatchStatus ms : values()) {
            if (ms.value.equalsIgnoreCase(status.trim())) {
                return ms;
            }
        }
        throw new IllegalArgumentException("Invalid match status: " + status);
    }

    @Override
    public String toString() {
        return value;
    }
}

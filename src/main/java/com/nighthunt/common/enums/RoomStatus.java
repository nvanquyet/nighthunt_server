package com.nighthunt.common.enums;

/**
 * Type-safe enum for room status.
 */
public enum RoomStatus {
    WAITING("WAITING"),
    IN_GAME("IN_GAME"),
    CLOSED("CLOSED"),
    FINISHED("FINISHED");

    private final String value;

    RoomStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean isActive() {
        return this == WAITING || this == IN_GAME;
    }

    public boolean isJoinable() {
        return this == WAITING;
    }

    public boolean isTerminal() {
        return this == CLOSED || this == FINISHED;
    }

    public static RoomStatus fromString(String status) {
        if (status == null) return null;
        for (RoomStatus rs : values()) {
            if (rs.value.equalsIgnoreCase(status.trim())) {
                return rs;
            }
        }
        throw new IllegalArgumentException("Invalid room status: " + status);
    }

    @Override
    public String toString() {
        return value;
    }
}

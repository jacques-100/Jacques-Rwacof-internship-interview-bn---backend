package com.rwacof.cherrytrack.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Delivery lifecycle: RECEIVED -> GRADED -> PAID, or RECEIVED -> REJECTED.
 * PAID and REJECTED are terminal.
 */
public enum DeliveryStatus {
    RECEIVED,
    GRADED,
    PAID,
    REJECTED;

    public Set<DeliveryStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(GRADED, REJECTED);
            case GRADED -> EnumSet.of(PAID);
            case PAID, REJECTED -> EnumSet.noneOf(DeliveryStatus.class);
        };
    }

    public boolean canTransitionTo(DeliveryStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** Rejected deliveries do not count toward daily capacity. */
    public boolean countsTowardCapacity() {
        return this != REJECTED;
    }
}

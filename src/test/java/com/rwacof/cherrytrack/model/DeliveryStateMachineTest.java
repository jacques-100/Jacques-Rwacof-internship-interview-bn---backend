package com.rwacof.cherrytrack.model;

import com.rwacof.cherrytrack.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private final JobRole clerkRole = new JobRole("Clerk", null, Role.CLERK, NOW);
    private final User user = new User("clerk1", "x", "Clerk One", clerkRole, NOW);
    private final Station station = new Station("TST", "Test Station", null, "Africa/Kigali",
            new BigDecimal("5000"), new BigDecimal("500"), new BigDecimal("500"), NOW);
    private final Farmer farmer = new Farmer("Farmer", "0788000000", "TST-0001", NOW);

    private Delivery received(String kg) {
        return Delivery.receive("DLV-TST-20261001-00001", station, farmer, LocalDate.of(2026, 10, 1), new BigDecimal(kg), user, NOW);
    }

    private Delivery graded() {
        Delivery d = received("350.00");
        d.grade("A", new BigDecimal("1200.00"), null, null, user, NOW);
        return d;
    }

    private Delivery paid() {
        Delivery d = graded();
        d.markPaid(user, NOW);
        return d;
    }

    private Delivery rejected() {
        Delivery d = received("100.00");
        d.reject("Unripe", user, NOW);
        return d;
    }

    @Test
    void newDeliveryIsReceivedWithNoGradeOrAmount() {
        Delivery d = received("120.50");
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.RECEIVED);
        assertThat(d.getGrade()).isNull();
        assertThat(d.getAmountOwed()).isNull();
        assertThat(d.getStation()).isSameAs(station);
        assertThat(d.getPricePerKg()).isNull();
    }

    @Test
    void gradingComputesAmountFromWeightAndPriceSnapshot() {
        Delivery d = graded();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.GRADED);
        assertThat(d.getAmountOwed()).isEqualByComparingTo("420000.00");
        assertThat(d.getPricePerKg()).isEqualByComparingTo("1200.00");
        assertThat(d.getGradedBy()).isSameAs(user);
    }

    @Test
    void amountIsRoundedHalfUpToTwoDecimals() {
        Delivery d = received("33.33");
        d.grade("B", new BigDecimal("0.15"), null, null, user, NOW);   // 4.9995 -> 5.00
        assertThat(d.getAmountOwed()).isEqualByComparingTo("5.00");
    }

    @Test
    void happyPathReceivedGradedPaid() {
        Delivery d = paid();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PAID);
        assertThat(d.getPaidBy()).isSameAs(user);
        assertThat(d.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void rejectionPathRecordsReason() {
        Delivery d = rejected();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
        assertThat(d.getRejectReason()).isEqualTo("Unripe");
        assertThat(d.getGrade()).isNull();
    }

    @Test
    void weightCanBeCorrectedWhileReceived() {
        Delivery d = received("300.00");
        d.correctWeight(new BigDecimal("350.00"), NOW);
        assertThat(d.getWeightKg()).isEqualByComparingTo("350.00");
    }

    @Test
    void weightCannotBeCorrectedAfterGrading() {
        assertThatThrownBy(() -> graded().correctWeight(new BigDecimal("10"), NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void paidDeliveryIsImmutable() {
        Delivery d = paid();
        assertThatThrownBy(() -> d.correctWeight(new BigDecimal("1"), NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.grade("B", BigDecimal.ONE, null, null, user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.markPaid(user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.reject("x", user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectedDeliveryIsImmutable() {
        Delivery d = rejected();
        assertThatThrownBy(() -> d.correctWeight(new BigDecimal("1"), NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.grade("A", BigDecimal.ONE, null, null, user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.markPaid(user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.reject("again", user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannotPayBeforeGrading() {
        assertThatThrownBy(() -> received("10").markPaid(user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannotRejectAfterGrading() {
        assertThatThrownBy(() -> graded().reject("late", user, NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannotGradeTwice() {
        assertThatThrownBy(() -> graded().grade("B", BigDecimal.ONE, null, null, user, NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectsNonPositiveWeights() {
        assertThatThrownBy(() -> received("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> received("-5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> received("10").correctWeight(BigDecimal.ZERO, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    // The status enum encodes the same machine; make sure the two stay consistent.
    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void terminalStatusesAllowNothing(DeliveryStatus status) {
        if (status == DeliveryStatus.PAID || status == DeliveryStatus.REJECTED) {
            assertThat(status.isTerminal()).isTrue();
            assertThat(status.allowedNext()).isEmpty();
        } else {
            assertThat(status.isTerminal()).isFalse();
        }
    }

    @Test
    void allowedTransitionsMatchTheSpecExactly() {
        assertThat(DeliveryStatus.RECEIVED.allowedNext()).containsExactlyInAnyOrder(DeliveryStatus.GRADED, DeliveryStatus.REJECTED);
        assertThat(DeliveryStatus.GRADED.allowedNext()).containsExactly(DeliveryStatus.PAID);
    }

    @Test
    void onlyRejectedStopsCountingTowardCapacity() {
        assertThat(DeliveryStatus.REJECTED.countsTowardCapacity()).isFalse();
        assertThat(DeliveryStatus.RECEIVED.countsTowardCapacity()).isTrue();
        assertThat(DeliveryStatus.GRADED.countsTowardCapacity()).isTrue();
        assertThat(DeliveryStatus.PAID.countsTowardCapacity()).isTrue();
    }
}

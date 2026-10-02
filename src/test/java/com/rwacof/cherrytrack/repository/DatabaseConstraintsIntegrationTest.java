package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The database itself refuses data that breaks the invariants, even if application code is bypassed. */
class DatabaseConstraintsIntegrationTest extends AbstractIntegrationTest {

    private long farmerId;
    private long userId;
    private long stationId;

    @BeforeEach
    void setUp() {
        User u = user(Role.CLERK);
        Farmer f = farmer();
        userId = u.getId();
        farmerId = f.getId();
        stationId = station.getId();
    }

    private void insert(String reference, String weight, String status, String grade, String price, String amount) {
        jdbc.update("""
                INSERT INTO deliveries (reference, station_id, farmer_id, delivery_date, weight_kg, grade, price_per_kg, amount_owed,
                                        status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_DATE, ?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                """, reference, stationId, farmerId, weight, grade, price, amount, status, userId);
    }

    @Test
    void acceptsAValidReceivedDelivery() {
        insert("REF-OK", "100.00", "RECEIVED", null, null, null);
    }

    @Test
    void rejectsZeroAndNegativeWeights() {
        assertThatThrownBy(() -> insert("R1", "0", "RECEIVED", null, null, null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert("R2", "-5", "RECEIVED", null, null, null)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsUnknownStatusAndUnconfiguredGrade() {
        assertThatThrownBy(() -> insert("R4", "10", "DONE", null, null, null)).isInstanceOf(DataAccessException.class);
        // grades are data now: 'C' is refused until someone configures it
        assertThatThrownBy(() -> insert("R5", "10", "GRADED", "C", "100", "1000")).isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO grades (code, name, active, sort_order, created_at, updated_at) VALUES ('C', 'Grade C', TRUE, 3, NOW(3), NOW(3))");
        insert("R5b", "10", "GRADED", "C", "100.00", "1000.00");
    }

    @Test
    void gradedAndPaidRowsMustCarryGradePriceAndAmount() {
        assertThatThrownBy(() -> insert("R6", "10", "GRADED", null, null, null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert("R7", "10", "PAID", "A", "100", null)).isInstanceOf(DataAccessException.class);
        insert("R8", "10", "GRADED", "A", "100.00", "1000.00");
    }

    @Test
    void receivedAndRejectedRowsMustNotCarryAnAmount() {
        assertThatThrownBy(() -> insert("R9", "10", "RECEIVED", "A", "100", "1000")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insert("R10", "10", "REJECTED", null, null, "5")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void referencesAreUnique() {
        insert("DUP", "10", "RECEIVED", null, null, null);
        assertThatThrownBy(() -> insert("DUP", "20", "RECEIVED", null, null, null)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void deliveriesRequireAnExistingStationFarmerAndCreator() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deliveries (reference, station_id, farmer_id, delivery_date, weight_kg, status, created_by, created_at, updated_at)
                VALUES ('FK0', 999999, ?, CURRENT_DATE, 10, 'RECEIVED', ?, NOW(3), NOW(3))""", farmerId, userId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deliveries (reference, station_id, farmer_id, delivery_date, weight_kg, status, created_by, created_at, updated_at)
                VALUES ('FK1', ?, 999999, CURRENT_DATE, 10, 'RECEIVED', ?, NOW(3), NOW(3))""", stationId, userId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deliveries (reference, station_id, farmer_id, delivery_date, weight_kg, status, created_by, created_at, updated_at)
                VALUES ('FK2', ?, ?, CURRENT_DATE, 10, 'RECEIVED', 999999, NOW(3), NOW(3))""", stationId, farmerId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void moistureMustBeAPercentage() {
        insert("M1", "10", "RECEIVED", null, null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE deliveries SET moisture_percent = 120 WHERE reference = 'M1'")).isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE deliveries SET moisture_percent = 11.5 WHERE reference = 'M1'");
    }

    @Test
    void farmerWithDeliveriesCannotBeRemoved() {
        insert("KEEP", "10", "RECEIVED", null, null, null);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM farmers WHERE id = ?", farmerId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void stationWithDeliveriesCannotBeRemoved() {
        insert("KEEP2", "10", "RECEIVED", null, null, null);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM stations WHERE id = ?", stationId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void dailyCapacityCannotExceedItsOwnLimitEvenIfWrittenDirectly() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO daily_capacity (station_id, delivery_date, accepted_kg, limit_kg, delivery_seq, updated_at) VALUES (?, CURRENT_DATE, 5000.01, 5000, 0, NOW(3))", stationId))
                .isInstanceOf(DataAccessException.class);
        // a day whose limit was raised can hold more
        jdbc.update("INSERT INTO daily_capacity (station_id, delivery_date, accepted_kg, limit_kg, delivery_seq, updated_at) VALUES (?, CURRENT_DATE, 6000, 6500, 0, NOW(3))", stationId);
    }

    @Test
    void capacityRowsAreUniquePerStationAndDay() {
        jdbc.update("INSERT INTO daily_capacity (station_id, delivery_date, accepted_kg, limit_kg, delivery_seq, updated_at) VALUES (?, CURRENT_DATE, 0, 5000, 0, NOW(3))", stationId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_capacity (station_id, delivery_date, accepted_kg, limit_kg, delivery_seq, updated_at) VALUES (?, CURRENT_DATE, 0, 5000, 0, NOW(3))", stationId))
                .isInstanceOf(DataAccessException.class);
        long other = station("OTH", "Other Station", "3000", "300").getId();
        jdbc.update("INSERT INTO daily_capacity (station_id, delivery_date, accepted_kg, limit_kg, delivery_seq, updated_at) VALUES (?, CURRENT_DATE, 0, 3000, 0, NOW(3))", other);
    }

    @Test
    void stationCodesNamesAndLimitsAreConstrained() {
        assertThatThrownBy(() -> station("TST", "Another Name", "5000", "500")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> station("ZZZ", "Test Station", "5000", "500")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> station("NEG", "Negative Capacity", "-1", "500")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void cooperativeNumbersUsernamesAndEmailsAreUnique() {
        Farmer existing = farmerRepository.findById(farmerId).orElseThrow();
        assertThatThrownBy(() -> farmerRepository.saveAndFlush(
                new Farmer("Someone Else", "0788999999", existing.getCooperativeNumber(), Instant.now())))
                .isInstanceOf(DataAccessException.class);
        User u = userRepository.findById(userId).orElseThrow();
        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User(u.getUsername(), "x", "Dup", systemRole(Role.CLERK), Instant.now())))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE users SET email = 'dup@example.com' WHERE id = ?", userId);
        User other = user(Role.CLERK);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET email = 'dup@example.com' WHERE id = ?", other.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void priceMustBePositiveAndUniquePerGradeAndInstant() {
        jdbc.update("INSERT INTO grade_prices (grade, price_per_kg, effective_from, created_at) VALUES ('A', 100, '2030-01-01 00:00:00.000', NOW(3))");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_prices (grade, price_per_kg, effective_from, created_at) VALUES ('A', 200, '2030-01-01 00:00:00.000', NOW(3))"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_prices (grade, price_per_kg, effective_from, created_at) VALUES ('B', 0, '2030-01-01 00:00:00.000', NOW(3))"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_prices (grade, price_per_kg, effective_from, created_at) VALUES ('ZZ', 10, '2030-01-01 00:00:00.000', NOW(3))"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void employmentDatesMustBeOrderedAndTypesValid() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO employments (user_id, job_title, employment_type, start_date, end_date, created_at, updated_at) VALUES (?, 'x', 'FULL_TIME', '2026-05-01', '2026-04-01', NOW(3), NOW(3))", userId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO employments (user_id, job_title, employment_type, start_date, created_at, updated_at) VALUES (?, 'x', 'VOLUNTEER', '2026-05-01', NOW(3), NOW(3))", userId))
                .isInstanceOf(DataAccessException.class);
    }
}

package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.DeliveryDtos.CreateDeliveryRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.CorrectWeightRequest;
import com.rwacof.cherrytrack.exception.CapacityExceededException;
import com.rwacof.cherrytrack.model.Delivery;
import com.rwacof.cherrytrack.model.DeliveryStatus;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.DeliveryRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the central invariant: accepted weight per day never exceeds the limit,
 * however many clerks submit at the same moment.
 */
class CapacityConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired DeliveryService deliveryService;
    @Autowired DeliveryRepository deliveryRepository;

    private AuthUser clerk;
    private Farmer farmer;

    private void arrange() {
        User u = user(Role.CLERK, station);
        clerk = AuthUser.withTemplate(u);
        farmer = farmer();
    }

    private BigDecimal acceptedByCounter(LocalDate day) {
        return jdbc.queryForObject("SELECT accepted_kg FROM daily_capacity WHERE station_id = ? AND delivery_date = ?", BigDecimal.class, station.getId(), day);
    }

    private BigDecimal acceptedBySum(LocalDate day) {
        return deliveryRepository.sumAcceptedWeight(station.getId(), day, DeliveryStatus.REJECTED);
    }

    /** Runs all tasks at (nearly) the same instant. */
    private <T> List<Future<T>> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        for (Future<T> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
                // outcomes are inspected by the caller
            }
        }
        pool.shutdown();
        return futures;
    }

    private Callable<Boolean> submit(String kg, LocalDate day) {
        return () -> {
            AtomicInteger ok = new AtomicInteger();
            CurrentUser.runAs(clerk, () -> {
                try {
                    deliveryService.create(station, new CreateDeliveryRequest(farmer.getId(), day, new BigDecimal(kg)));
                    ok.incrementAndGet();
                } catch (CapacityExceededException expected) {
                    // the loser of the race
                }
            });
            return ok.get() == 1;
        };
    }

    @Test
    void twoClerksRacingForTheLastCapacityNeverProduce5100Kg() throws Exception {
        arrange();
        LocalDate day = today();
        // 4,700 kg already accepted
        CurrentUser.runAs(clerk, () -> {
            for (int i = 0; i < 9; i++) {
                deliveryService.create(station, new CreateDeliveryRequest(farmer.getId(), day, new BigDecimal("500")));
            }
            deliveryService.create(station, new CreateDeliveryRequest(farmer.getId(), day, new BigDecimal("200")));
        });
        assertThat(acceptedByCounter(day)).isEqualByComparingTo("4700");

        // Clerk A and Clerk B each submit 200 kg at once: only one fits (4,900); two would be 5,100.
        List<Future<Boolean>> results = race(List.of(submit("200", day), submit("200", day)));
        long successes = results.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();

        assertThat(successes).isEqualTo(1);
        assertThat(acceptedByCounter(day)).isEqualByComparingTo("4900");
        assertThat(acceptedBySum(day)).isEqualByComparingTo("4900");
    }

    @RepeatedTest(3)
    void manyConcurrentSubmissionsNeverExceedTheLimitAndCounterMatchesRows() throws Exception {
        arrange();
        LocalDate day = today().minusDays(1);
        int threads = 24;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(submit("450", day));   // 24 x 450 = 10,800 kg offered, only 11 fit (4,950)
        }
        List<Future<Boolean>> results = race(tasks);
        long successes = results.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();

        assertThat(successes).isEqualTo(11);
        assertThat(acceptedByCounter(day)).isEqualByComparingTo("4950");
        assertThat(acceptedBySum(day)).isEqualByComparingTo("4950");
        assertThat(acceptedBySum(day)).isLessThanOrEqualTo(new BigDecimal("5000"));
        // references are unique and gapless: the sequence is allocated under the same lock
        List<String> refs = jdbc.queryForList("SELECT reference FROM deliveries WHERE station_id = ? AND delivery_date = ? ORDER BY id", String.class, station.getId(), day);
        assertThat(refs).doesNotHaveDuplicates().hasSize(11);
    }

    @Test
    void concurrentCreatesCorrectionsAndRejectsKeepCounterConsistent() throws Exception {
        arrange();
        LocalDate day = today();
        List<Long> ids = new ArrayList<>();
        CurrentUser.runAs(clerk, () -> {
            for (int i = 0; i < 8; i++) {
                ids.add(deliveryService.create(station, new CreateDeliveryRequest(farmer.getId(), day, new BigDecimal("400"))).id());
            }
        });   // 3,200 kg

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(submit("300", day));
        }
        for (int i = 0; i < 4; i++) {
            long id = ids.get(i);
            tasks.add(() -> {
                CurrentUser.runAs(clerk, () -> {
                    try {
                        deliveryService.correctWeight(id, new CorrectWeightRequest(new BigDecimal("500"), "recheck"));
                    } catch (CapacityExceededException ignored) { }
                });
                return true;
            });
        }
        for (int i = 4; i < 7; i++) {
            long id = ids.get(i);
            tasks.add(() -> {
                CurrentUser.runAs(clerk, () -> deliveryService.reject(id, "unripe"));
                return true;
            });
        }
        race(tasks);

        BigDecimal byRows = acceptedBySum(day);
        assertThat(acceptedByCounter(day)).isEqualByComparingTo(byRows);
        assertThat(byRows).isLessThanOrEqualTo(new BigDecimal("5000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deliveries WHERE status = 'REJECTED' AND station_id = ? AND delivery_date = ?",
                Integer.class, station.getId(), day)).isEqualTo(3);
    }

    @Test
    void doubleSubmissionOfTheSameActionOnOneDeliveryOnlyWinsOnce() throws Exception {
        arrange();
        standardPrices();
        long id = CurrentUser.callAs(clerk, () ->
                deliveryService.create(station, new CreateDeliveryRequest(farmer.getId(), today(), new BigDecimal("100"))).id());

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> {
                try {
                    CurrentUser.runAs(clerk, () -> deliveryService.grade(id, new com.rwacof.cherrytrack.dto.DeliveryDtos.GradeRequest("A", null, null)));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> results = race(tasks);
        long wins = results.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();

        assertThat(wins).isEqualTo(1);
        Delivery d = deliveryRepository.findById(id).orElseThrow();
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.GRADED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'DELIVERY_GRADED' AND entity_id = ?",
                Integer.class, id)).isEqualTo(1);
    }
}

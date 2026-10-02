package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.config.AppProperties;
import com.rwacof.cherrytrack.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter on failed logins, keyed by client address + username.
 * Single-instance only (documented trade-off); swap for a shared store when scaling out.
 */
@Component
public class LoginRateLimiter {

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long windowMillis;
    private final Clock clock;

    public LoginRateLimiter(AppProperties props, Clock clock) {
        this.maxAttempts = props.security().loginMaxAttempts();
        this.windowMillis = props.security().loginWindowSeconds() * 1000L;
        this.clock = clock;
    }

    public void checkAllowed(String key) {
        Deque<Long> q = failures.get(key);
        if (q == null) {
            return;
        }
        synchronized (q) {
            prune(q);
            if (q.size() >= maxAttempts) {
                throw new TooManyRequestsException("Too many failed login attempts. Try again in a minute.");
            }
        }
    }

    public void recordFailure(String key) {
        Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.millis());
        }
        if (failures.size() > 10_000) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue());
                    return e.getValue().isEmpty();
                }
            });
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Long> q) {
        long cutoff = clock.millis() - windowMillis;
        while (!q.isEmpty() && q.peekFirst() < cutoff) {
            q.pollFirst();
        }
    }
}

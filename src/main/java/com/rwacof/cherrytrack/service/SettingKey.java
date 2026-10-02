package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The settings the system understands, how each is validated and what it is when never changed.
 * Values are stored in the database and edited in the application; only this catalogue lives in code.
 */
public enum SettingKey {
    ORGANIZATION_NAME("organization.name", "Organisation name", "Shown in the sidebar and on exports.", Type.TEXT, "Organisation", "CherryTrack"),
    CURRENCY_CODE("currency.code", "Currency", "Three-letter code shown with every amount, e.g. RWF.", Type.CURRENCY, "Organisation", "RWF"),
    STATION_DEFAULT_TIMEZONE("station.default-timezone", "Default time zone", "Suggested when registering a station.", Type.TIMEZONE, "New station defaults", "Africa/Kigali"),
    STATION_DEFAULT_DAILY_CAPACITY("station.default-daily-capacity-kg", "Daily capacity (kg)", "Suggested daily intake limit for a new station.", Type.DECIMAL, "New station defaults", "5000"),
    STATION_DEFAULT_MAX_DELIVERY("station.default-max-delivery-kg", "Largest single delivery (kg)", "Suggested per-delivery limit for a new station.", Type.DECIMAL, "New station defaults", "500"),
    STATION_DEFAULT_LOW_THRESHOLD("station.default-low-threshold-kg", "Low-capacity warning (kg)", "Suggested warning level for a new station.", Type.DECIMAL, "New station defaults", "500"),
    REPORTS_MAX_RANGE_DAYS("reports.max-range-days", "Longest report range (days)", "A report cannot span more days than this.", Type.INTEGER, "Reports", "366"),
    REJECTION_REASONS("delivery.rejection-reasons", "Rejection reasons", "Quick choices offered when rejecting a delivery. One per line.", Type.TEXT_LIST, "Deliveries",
            "Unripe cherries\nExcess moisture and debris\nOver-fermented\nForeign matter");

    public enum Type { TEXT, CURRENCY, TIMEZONE, DECIMAL, INTEGER, TEXT_LIST }

    private final String key;
    private final String label;
    private final String description;
    private final Type type;
    private final String group;
    private final String defaultValue;

    SettingKey(String key, String label, String description, Type type, String group, String defaultValue) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.type = type;
        this.group = group;
        this.defaultValue = defaultValue;
    }

    public String key() { return key; }

    public String label() { return label; }

    public String description() { return description; }

    public Type type() { return type; }

    public String group() { return group; }

    public String defaultValue() { return defaultValue; }

    public static Optional<SettingKey> byKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /** Returns the normalised value to store, or throws a 422 explaining what is wrong. */
    public String normalize(String raw) {
        String v = raw == null ? "" : raw.trim();
        switch (type) {
            case TEXT -> {
                if (v.isEmpty() || v.length() > 80) throw invalid("must be 1 to 80 characters.");
                return v;
            }
            case CURRENCY -> {
                String code = v.toUpperCase();
                if (!code.matches("[A-Z]{3}")) throw invalid("must be a three-letter code such as RWF.");
                return code;
            }
            case TIMEZONE -> {
                try {
                    ZoneId.of(v);
                } catch (DateTimeException e) {
                    throw invalid("must be a valid time zone such as Africa/Kigali.");
                }
                return v;
            }
            case DECIMAL -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(v);
                } catch (NumberFormatException e) {
                    throw invalid("must be a number.");
                }
                if (n.signum() <= 0 || n.compareTo(new BigDecimal("9999999.99")) > 0 || n.scale() > 2) {
                    throw invalid("must be greater than 0, at most 9,999,999.99, with at most 2 decimals.");
                }
                return n.stripTrailingZeros().toPlainString();
            }
            case INTEGER -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw invalid("must be a whole number.");
                }
                if (n < 1 || n > 3660) throw invalid("must be between 1 and 3660.");
                return String.valueOf(n);
            }
            case TEXT_LIST -> {
                Set<String> lines = new LinkedHashSet<>();
                for (String line : v.split("\\R")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.length() > 100) throw invalid("each line must be at most 100 characters.");
                    lines.add(t);
                }
                if (lines.size() > 30) throw invalid("can have at most 30 lines.");
                return String.join("\n", lines);
            }
        }
        throw new IllegalStateException("Unhandled setting type " + type);
    }

    private BusinessRuleException invalid(String problem) {
        return new BusinessRuleException("INVALID_SETTING", label + " " + problem);
    }
}

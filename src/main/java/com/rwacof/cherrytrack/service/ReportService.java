package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.ReportDtos.Column;
import com.rwacof.cherrytrack.dto.ReportDtos.ReportResponse;
import com.rwacof.cherrytrack.dto.ReportDtos.ReportType;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.model.Station;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Management reports, computed in SQL from the authoritative tables. Exports reuse the same
 * result so a downloaded file always matches what the screen shows.
 */
@Service
public class ReportService {

    private static final String ACCEPTED = "SUM(CASE WHEN d.status <> 'REJECTED' THEN d.weight_kg ELSE 0 END)";
    private static final String REJECTED = "SUM(CASE WHEN d.status = 'REJECTED' THEN d.weight_kg ELSE 0 END)";
    private static final String OWED = "SUM(CASE WHEN d.status IN ('GRADED','PAID') THEN d.amount_owed ELSE 0 END)";
    private static final String PAID = "SUM(CASE WHEN d.status = 'PAID' THEN d.amount_owed ELSE 0 END)";

    private final JdbcTemplate jdbc;
    private final StationAccessService stationAccess;
    private final SettingsService settings;

    public ReportService(JdbcTemplate jdbc, StationAccessService stationAccess, SettingsService settings) {
        this.jdbc = jdbc;
        this.stationAccess = stationAccess;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ReportResponse run(Station station, ReportType type, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? stationAccess.today(station) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BusinessRuleException("INVALID_RANGE", "The start date must not be after the end date.");
        }
        int maxDays = settings.maxReportRangeDays();
        if (ChronoUnit.DAYS.between(start, end) > maxDays) {
            throw new BusinessRuleException("RANGE_TOO_LARGE", "Reports are limited to " + maxDays + " days.");
        }
        return switch (type) {
            case DAILY_INTAKE -> intake(station, type, start, end, "d.delivery_date", "Date", "date");
            case WEEKLY_INTAKE -> intake(station, type, start, end,
                    "DATE_SUB(d.delivery_date, INTERVAL WEEKDAY(d.delivery_date) DAY)", "Week starting", "date");
            case MONTHLY_INTAKE -> intake(station, type, start, end, "DATE_FORMAT(d.delivery_date, '%Y-%m')", "Month", "text");
            case FARMER_DELIVERY -> farmers(station, start, end);
            case GRADE_DISTRIBUTION -> grades(station, start, end);
            case PAYMENT -> payments(station, start, end);
            case REJECTED_DELIVERIES -> rejected(station, start, end);
            case CAPACITY_UTILIZATION -> utilization(station, start, end);
        };
    }

    // ------------------------------------------------------------------ reports

    private ReportResponse intake(Station station, ReportType type, LocalDate from, LocalDate to, String periodExpr, String label, String periodType) {
        List<Map<String, Object>> rows = query("""
                SELECT %s AS period, COUNT(*) AS deliveries, %s AS accepted_kg, %s AS rejected_kg, %s AS amount_owed, %s AS amount_paid
                FROM deliveries d WHERE d.station_id = ? AND d.delivery_date BETWEEN ? AND ?
                GROUP BY period ORDER BY period
                """.formatted(periodExpr, ACCEPTED, REJECTED, OWED, PAID), station, from, to);
        List<Column> cols = List.of(
                new Column("period", label, periodType), new Column("deliveries", "Deliveries", "number"),
                new Column("accepted_kg", "Accepted (kg)", "number"), new Column("rejected_kg", "Rejected (kg)", "number"),
                new Column("amount_owed", "Amount owed (" + settings.currency() + ")", "money"), new Column("amount_paid", "Amount paid (" + settings.currency() + ")", "money"));
        return response(type, from, to, cols, rows, List.of("deliveries", "accepted_kg", "rejected_kg", "amount_owed", "amount_paid"));
    }

    private ReportResponse farmers(Station station, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = query("""
                SELECT f.cooperative_number AS cooperative_number, f.full_name AS farmer, COUNT(d.id) AS deliveries,
                       %s AS accepted_kg, %s AS amount_owed, %s AS amount_paid
                FROM farmers f JOIN deliveries d ON d.farmer_id = f.id AND d.station_id = ? AND d.delivery_date BETWEEN ? AND ?
                GROUP BY f.id, f.cooperative_number, f.full_name ORDER BY accepted_kg DESC, f.full_name
                """.formatted(ACCEPTED, OWED, PAID), station, from, to);
        List<Column> cols = List.of(
                new Column("cooperative_number", "Cooperative no.", "text"), new Column("farmer", "Farmer", "text"),
                new Column("deliveries", "Deliveries", "number"), new Column("accepted_kg", "Accepted (kg)", "number"),
                new Column("amount_owed", "Amount owed (" + settings.currency() + ")", "money"), new Column("amount_paid", "Amount paid (" + settings.currency() + ")", "money"));
        return response(ReportType.FARMER_DELIVERY, from, to, cols, rows, List.of("deliveries", "accepted_kg", "amount_owed", "amount_paid"));
    }

    private ReportResponse grades(Station station, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = query("""
                SELECT d.grade AS grade, COUNT(*) AS deliveries, SUM(d.weight_kg) AS weight_kg, SUM(d.amount_owed) AS amount
                FROM deliveries d WHERE d.station_id = ? AND d.delivery_date BETWEEN ? AND ? AND d.status IN ('GRADED','PAID')
                GROUP BY d.grade ORDER BY d.grade
                """, station, from, to);
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r.get("weight_kg")).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Map<String, Object> r : rows) {
            BigDecimal w = (BigDecimal) r.get("weight_kg");
            r.put("share_percent", total.signum() == 0 ? BigDecimal.ZERO
                    : w.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP));
        }
        List<Column> cols = List.of(
                new Column("grade", "Grade", "text"), new Column("deliveries", "Deliveries", "number"),
                new Column("weight_kg", "Weight (kg)", "number"), new Column("share_percent", "Share of weight", "percent"),
                new Column("amount", "Amount (" + settings.currency() + ")", "money"));
        return response(ReportType.GRADE_DISTRIBUTION, from, to, cols, rows, List.of("deliveries", "weight_kg", "amount"));
    }

    private ReportResponse payments(Station station, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = query("""
                SELECT d.reference AS reference, d.delivery_date AS delivery_date, f.full_name AS farmer, d.grade AS grade,
                       d.weight_kg AS weight_kg, d.price_per_kg AS price_per_kg, d.amount_owed AS amount,
                       d.paid_at AS paid_at, u.username AS paid_by
                FROM deliveries d JOIN farmers f ON f.id = d.farmer_id LEFT JOIN users u ON u.id = d.paid_by
                WHERE d.station_id = ? AND d.delivery_date BETWEEN ? AND ? AND d.status = 'PAID'
                ORDER BY d.paid_at DESC, d.id DESC LIMIT 5000
                """, station, from, to);
        List<Column> cols = List.of(
                new Column("reference", "Reference", "text"), new Column("delivery_date", "Delivery date", "date"),
                new Column("farmer", "Farmer", "text"), new Column("grade", "Grade", "text"),
                new Column("weight_kg", "Weight (kg)", "number"), new Column("price_per_kg", "Price/kg", "money"),
                new Column("amount", "Amount (" + settings.currency() + ")", "money"), new Column("paid_at", "Paid at", "datetime"),
                new Column("paid_by", "Paid by", "text"));
        return response(ReportType.PAYMENT, from, to, cols, rows, List.of("weight_kg", "amount"));
    }

    private ReportResponse rejected(Station station, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = query("""
                SELECT d.reference AS reference, d.delivery_date AS delivery_date, f.full_name AS farmer,
                       d.weight_kg AS weight_kg, d.reject_reason AS reason, d.rejected_at AS rejected_at, u.username AS rejected_by
                FROM deliveries d JOIN farmers f ON f.id = d.farmer_id LEFT JOIN users u ON u.id = d.rejected_by
                WHERE d.station_id = ? AND d.delivery_date BETWEEN ? AND ? AND d.status = 'REJECTED'
                ORDER BY d.rejected_at DESC, d.id DESC LIMIT 5000
                """, station, from, to);
        List<Column> cols = List.of(
                new Column("reference", "Reference", "text"), new Column("delivery_date", "Delivery date", "date"),
                new Column("farmer", "Farmer", "text"), new Column("weight_kg", "Weight (kg)", "number"),
                new Column("reason", "Reason", "text"), new Column("rejected_at", "Rejected at", "datetime"),
                new Column("rejected_by", "Rejected by", "text"));
        return response(ReportType.REJECTED_DELIVERIES, from, to, cols, rows, List.of("weight_kg"));
    }

    private ReportResponse utilization(Station station, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = query("""
                SELECT c.delivery_date AS date, c.accepted_kg AS accepted_kg, c.limit_kg AS limit_kg,
                       (SELECT COUNT(*) FROM deliveries d WHERE d.station_id = c.station_id AND d.delivery_date = c.delivery_date) AS deliveries
                FROM daily_capacity c WHERE c.station_id = ? AND c.delivery_date BETWEEN ? AND ? ORDER BY c.delivery_date
                """, station, from, to);
        for (Map<String, Object> r : rows) {
            BigDecimal accepted = (BigDecimal) r.get("accepted_kg");
            BigDecimal limit = (BigDecimal) r.get("limit_kg");
            r.put("utilization_percent", accepted.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP));
        }
        List<Column> cols = List.of(
                new Column("date", "Date", "date"), new Column("deliveries", "Deliveries", "number"),
                new Column("accepted_kg", "Accepted (kg)", "number"), new Column("limit_kg", "Limit (kg)", "number"),
                new Column("utilization_percent", "Utilization", "percent"));
        return response(ReportType.CAPACITY_UTILIZATION, from, to, cols, rows, List.of("deliveries", "accepted_kg"));
    }

    // ------------------------------------------------------------------ export

    /** CSV with RFC 4180 quoting and a guard against spreadsheet formula injection. */
    public byte[] toCsv(ReportResponse report) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", report.columns().stream().map(c -> csv(c.label())).toList())).append("\r\n");
        for (Map<String, Object> row : report.rows()) {
            List<String> cells = new ArrayList<>();
            for (Column c : report.columns()) {
                cells.add(csv(row.get(c.key())));
            }
            sb.append(String.join(",", cells)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = value instanceof BigDecimal b ? b.toPlainString() : value.toString();
        if (!(value instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> query(String sql, Station station, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.queryForList(sql, station.getId(), java.sql.Date.valueOf(from), java.sql.Date.valueOf(to))) {
            Map<String, Object> row = new LinkedHashMap<>();
            raw.forEach((k, v) -> row.put(k.toLowerCase(), convert(v)));
            rows.add(row);
        }
        return rows;
    }

    private Object convert(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof Timestamp t) {
            // DATETIME columns hold UTC (see hibernate.jdbc.time_zone and connectionTimeZone).
            return t.toLocalDateTime().atZone(java.time.ZoneOffset.UTC).toInstant();
        }
        if (v instanceof Long || v instanceof Integer) {
            return ((Number) v).longValue();
        }
        return v;
    }

    private ReportResponse response(ReportType type, LocalDate from, LocalDate to, List<Column> cols,
                                    List<Map<String, Object>> rows, List<String> totalKeys) {
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String key : totalKeys) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) {
                Object v = r.get(key);
                if (v instanceof BigDecimal b) {
                    sum = sum.add(b);
                } else if (v instanceof Number n) {
                    sum = sum.add(BigDecimal.valueOf(n.longValue()));
                }
            }
            totals.put(key, sum);
        }
        return new ReportResponse(type, from, to, cols, rows, totals);
    }
}

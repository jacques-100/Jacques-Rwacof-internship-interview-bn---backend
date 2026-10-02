package com.rwacof.cherrytrack.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ReportDtos {

    private ReportDtos() {}

    public enum ReportType {
        DAILY_INTAKE,
        WEEKLY_INTAKE,
        MONTHLY_INTAKE,
        FARMER_DELIVERY,
        GRADE_DISTRIBUTION,
        PAYMENT,
        REJECTED_DELIVERIES,
        CAPACITY_UTILIZATION
    }

    /** {@code type} is one of: text, number, money, percent, date. */
    public record Column(String key, String label, String type) {}

    public record ReportResponse(ReportType reportType, LocalDate from, LocalDate to, List<Column> columns,
                                 List<Map<String, Object>> rows, Map<String, Object> totals) {}
}

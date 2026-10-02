package com.rwacof.cherrytrack.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Everything a role can be allowed to do. The list of capabilities is fixed by the code (each one is
 * enforced at a specific endpoint); which role holds which is data, edited on the Permissions tab.
 * Viewing operational data (dashboard, deliveries, farmers, prices) needs no permission, only a station.
 */
public enum Permission {
    DELIVERY_CREATE("Deliveries", "Receive deliveries", "Record a new delivery and its weight."),
    DELIVERY_CORRECT_WEIGHT("Deliveries", "Correct weight", "Correct the weight of a delivery that has not been graded."),
    DELIVERY_GRADE("Deliveries", "Grade deliveries", "Award a grade; the amount owed is calculated from the current price."),
    DELIVERY_REJECT("Deliveries", "Reject deliveries", "Reject a delivery, releasing its weight from capacity."),
    DELIVERY_PAY("Deliveries", "Mark as paid", "Confirm payment to the farmer. Also gives access to the Payments queue."),
    FARMER_MANAGE("Farmers", "Register and edit farmers", "Create farmers and change their details."),
    GRADE_MANAGE("Prices & grades", "Manage grades", "Add, edit and switch grades on or off."),
    PRICE_MANAGE("Prices & grades", "Set prices", "Set the price per kg for each grade."),
    CAPACITY_ADJUST("Capacity", "Adjust daily capacity", "Raise or lower the intake limit for a day, with a reason."),
    REPORT_VIEW("Insight", "View and export reports", "Open management reports and download them."),
    AUDIT_VIEW("Insight", "View audit logs", "Read the audit trail of the stations they manage."),
    STATION_MANAGE("Administration", "Manage stations", "Register stations, set their limits and assign staff."),
    USER_MANAGE("Administration", "Manage users and staff", "Create users, assign roles and stations, manage departments and employments."),
    PERMISSION_MANAGE("Administration", "Manage permissions", "Change which permissions each role holds."),
    SETTINGS_MANAGE("Administration", "Manage system settings", "Edit the organisation name, currency, defaults and lists.");

    private final String group;
    private final String label;
    private final String description;

    Permission(String group, String label, String description) {
        this.group = group;
        this.label = label;
        this.description = description;
    }

    public String group() { return group; }

    public String label() { return label; }

    public String description() { return description; }

    /** What a brand-new role of this access level starts with (it can be edited afterwards). */
    public static Set<Permission> template(Role level) {
        Set<Permission> clerk = EnumSet.of(DELIVERY_CREATE, DELIVERY_CORRECT_WEIGHT, DELIVERY_GRADE, DELIVERY_REJECT, FARMER_MANAGE);
        Set<Permission> supervisor = EnumSet.copyOf(clerk);
        supervisor.addAll(EnumSet.of(DELIVERY_PAY, GRADE_MANAGE, PRICE_MANAGE, CAPACITY_ADJUST, REPORT_VIEW, AUDIT_VIEW));
        return switch (level) {
            case CLERK -> clerk;
            case SUPERVISOR -> supervisor;
            case ADMIN -> EnumSet.allOf(Permission.class);
        };
    }
}

package com.rwacof.cherrytrack.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class DailyCapacityId implements Serializable {

    private Long stationId;
    private LocalDate deliveryDate;

    public DailyCapacityId() {}

    public DailyCapacityId(Long stationId, LocalDate deliveryDate) {
        this.stationId = stationId;
        this.deliveryDate = deliveryDate;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DailyCapacityId other
                && Objects.equals(stationId, other.stationId)
                && Objects.equals(deliveryDate, other.deliveryDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stationId, deliveryDate);
    }
}

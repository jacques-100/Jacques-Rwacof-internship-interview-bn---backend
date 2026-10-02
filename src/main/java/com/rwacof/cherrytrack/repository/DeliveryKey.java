package com.rwacof.cherrytrack.repository;

import java.time.LocalDate;

/** Station and date of a delivery: all that is needed to take the right capacity lock. */
public record DeliveryKey(Long stationId, LocalDate deliveryDate) {}

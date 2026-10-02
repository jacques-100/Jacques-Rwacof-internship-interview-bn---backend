package com.rwacof.cherrytrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Configuration of the station the request is scoped to, plus that station's current date. */
public record SettingsDto(Long stationId, String stationCode, String stationName, String timezone, LocalDate today,
                          BigDecimal dailyLimitKg, BigDecimal singleDeliveryMaxKg, BigDecimal lowThresholdKg) {}

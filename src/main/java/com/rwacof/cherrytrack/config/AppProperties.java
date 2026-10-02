package com.rwacof.cherrytrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "cherrytrack")
public record AppProperties(Station station, Capacity capacity, Security security, Bootstrap bootstrap, Seed seed) {

    public record Station(String name, String timezone) {}

    public record Capacity(BigDecimal dailyLimitKg, BigDecimal singleDeliveryMaxKg, BigDecimal lowThresholdKg) {}

    public record Security(String jwtSecret,
                           int accessTokenMinutes,
                           int refreshTokenDays,
                           boolean refreshCookieSecure,
                           List<String> corsAllowedOrigins,
                           int loginMaxAttempts,
                           int loginWindowSeconds) {}

    public record Bootstrap(String adminPassword) {}

    public record Seed(boolean enabled, String demoPassword) {}
}

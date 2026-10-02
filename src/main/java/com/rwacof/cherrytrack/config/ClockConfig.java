package com.rwacof.cherrytrack.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * A single injectable clock in the station's time zone, so "today" is well defined
 * and tests can substitute a fixed clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.station().timezone()));
    }
}

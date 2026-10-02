package com.rwacof.cherrytrack.config;

import com.rwacof.cherrytrack.security.CurrentStationResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentStationResolver currentStationResolver;

    public WebConfig(CurrentStationResolver currentStationResolver) {
        this.currentStationResolver = currentStationResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentStationResolver);
    }
}

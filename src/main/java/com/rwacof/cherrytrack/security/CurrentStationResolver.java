package com.rwacof.cherrytrack.security;

import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.service.StationAccessService;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentStationResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Station-Id";

    private final StationAccessService stationAccess;

    public CurrentStationResolver(StationAccessService stationAccess) {
        this.stationAccess = stationAccess;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentStation.class) && Station.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HEADER);
        Long requested = null;
        if (header != null && !header.isBlank()) {
            try {
                requested = Long.valueOf(header.trim());
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("INVALID_STATION", "The selected station is not valid.");
            }
        }
        return stationAccess.resolve(CurrentUser.require(), requested);
    }
}

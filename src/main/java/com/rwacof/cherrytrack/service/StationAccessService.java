package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.exception.ConflictException;
import com.rwacof.cherrytrack.exception.ForbiddenException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which stations a user may work in. Administrators can use every station; everyone else
 * only the stations they are assigned to, and being assigned means managing that station.
 */
@Service
public class StationAccessService {

    private final StationRepository stationRepository;
    private final Clock clock;

    public StationAccessService(StationRepository stationRepository, Clock clock) {
        this.stationRepository = stationRepository;
        this.clock = clock;
    }

    /** The station a request operates on: the requested one if allowed, otherwise the user's first. */
    @Transactional(readOnly = true)
    public Station resolve(AuthUser user, Long requestedId) {
        if (requestedId != null) {
            Station station = stationRepository.findById(requestedId).orElseThrow(() -> new NotFoundException("Station", requestedId));
            requireAccess(user, station.getId());
            if (!station.isActive()) {
                throw new ConflictException("STATION_INACTIVE", station.getName() + " is not active.");
            }
            return station;
        }
        List<Station> options = accessible(user, false);
        if (options.isEmpty()) {
            if (user.role() == Role.ADMIN) {
                throw new ConflictException("NO_STATION", "No station has been registered yet. Register one first.");
            }
            throw new ForbiddenException("NOT_ASSIGNED", "You are not assigned to a station. Ask an administrator to assign you.");
        }
        return options.get(0);
    }

    /** Stations the user can work in (administrators: all of them). */
    @Transactional(readOnly = true)
    public List<Station> accessible(AuthUser user, boolean includeInactive) {
        List<Station> all = user.role() == Role.ADMIN
                ? stationRepository.findAllByOrderByNameAsc()
                : stationRepository.findAssignedTo(user.id());
        return all.stream().filter(s -> includeInactive || s.isActive()).toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> accessibleIds(AuthUser user) {
        return accessible(user, true).stream().map(Station::getId).collect(Collectors.toSet());
    }

    /** Throws 403 unless the user may work in this station. */
    @Transactional(readOnly = true)
    public void requireAccess(AuthUser user, Long stationId) {
        if (user.role() == Role.ADMIN) {
            return;
        }
        boolean assigned = stationRepository.findAssignedTo(user.id()).stream().anyMatch(s -> s.getId().equals(stationId));
        if (!assigned) {
            throw new ForbiddenException("STATION_FORBIDDEN", "You are not assigned to this station.");
        }
    }

    /** "Today" in the station's own time zone. */
    public LocalDate today(Station station) {
        return LocalDate.now(clock.withZone(ZoneId.of(station.getTimezone())));
    }
}

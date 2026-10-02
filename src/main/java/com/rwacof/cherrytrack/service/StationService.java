package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.dto.StationDtos.StationDetailDto;
import com.rwacof.cherrytrack.dto.StationDtos.StationDto;
import com.rwacof.cherrytrack.dto.StationDtos.StationRequest;
import com.rwacof.cherrytrack.dto.UserDtos.UserDto;
import com.rwacof.cherrytrack.exception.BusinessRuleException;
import com.rwacof.cherrytrack.exception.ConflictException;
import com.rwacof.cherrytrack.exception.NotFoundException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Registering stations and deciding who works in them. */
@Service
public class StationService {

    private final StationRepository stationRepository;
    private final UserRepository userRepository;
    private final StationAccessService stationAccess;
    private final AuditService auditService;
    private final Clock clock;

    public StationService(StationRepository stationRepository, UserRepository userRepository,
                          StationAccessService stationAccess, AuditService auditService, Clock clock) {
        this.stationRepository = stationRepository;
        this.userRepository = userRepository;
        this.stationAccess = stationAccess;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Stations the caller can work in. Administrators may also ask for inactive ones. */
    @Transactional(readOnly = true)
    public List<StationDto> list(boolean includeInactive) {
        AuthUser actor = CurrentUser.require();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : stationRepository.countAssignedUsers()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return stationAccess.accessible(actor, includeInactive).stream()
                .map(s -> StationDto.of(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public StationDetailDto get(Long id) {
        Station station = find(id);
        List<UserDto> users = userRepository.findAssignedToStation(id).stream().map(UserDto::of).toList();
        return new StationDetailDto(StationDto.of(station, users.size()), users);
    }

    @Transactional
    public StationDto create(StationRequest request) {
        String code = request.code().trim().toUpperCase();
        String name = request.name().trim();
        if (stationRepository.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("DUPLICATE_STATION_CODE", "A station with code " + code + " already exists.");
        }
        if (stationRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("DUPLICATE_STATION_NAME", "A station named '" + name + "' already exists.");
        }
        validate(request);
        Station saved = stationRepository.saveAndFlush(new Station(code, name, blankToNull(request.location()), request.timezone().trim(),
                request.dailyCapacityKg(), request.maxDeliveryKg(), request.lowThresholdKg(), clock.instant()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("name", name);
        details.put("dailyCapacityKg", saved.getDailyCapacityKg());
        auditService.record(saved.getId(), AuditAction.STATION_CREATED, "STATION", saved.getId(), "Station registered: " + name + " (" + code + ")", details);
        return StationDto.of(saved, 0);
    }

    @Transactional
    public StationDto update(Long id, StationRequest request) {
        Station station = find(id);
        if (!station.getCode().equalsIgnoreCase(request.code().trim())) {
            throw new BusinessRuleException("STATION_CODE_FIXED", "A station's code cannot change because delivery references use it.");
        }
        String name = request.name().trim();
        stationRepository.findAll().stream()
                .filter(s -> !s.getId().equals(id) && s.getName().equalsIgnoreCase(name)).findAny()
                .ifPresent(s -> { throw new ConflictException("DUPLICATE_STATION_NAME", "A station named '" + name + "' already exists."); });
        validate(request);

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "name", station.getName(), name);
        track(changes, "location", station.getLocation(), blankToNull(request.location()));
        track(changes, "timezone", station.getTimezone(), request.timezone().trim());
        track(changes, "dailyCapacityKg", station.getDailyCapacityKg(), request.dailyCapacityKg());
        track(changes, "maxDeliveryKg", station.getMaxDeliveryKg(), request.maxDeliveryKg());
        track(changes, "lowThresholdKg", station.getLowThresholdKg(), request.lowThresholdKg());
        if (request.active() != null) {
            track(changes, "active", station.isActive(), request.active());
            station.setActive(request.active());
        }
        station.setName(name);
        station.setLocation(blankToNull(request.location()));
        station.setTimezone(request.timezone().trim());
        station.setDailyCapacityKg(request.dailyCapacityKg());
        station.setMaxDeliveryKg(request.maxDeliveryKg());
        station.setLowThresholdKg(request.lowThresholdKg());
        station.touch(clock.instant());
        stationRepository.saveAndFlush(station);

        auditService.record(id, AuditAction.STATION_UPDATED, "STATION", id,
                changes.isEmpty() ? "Station saved without changes" : "Station updated: " + String.join(", ", changes.keySet()), changes);
        long users = userRepository.findAssignedToStation(id).size();
        return StationDto.of(station, users);
    }

    /** Replaces the set of users assigned to a station. Assigned users manage that station. */
    @Transactional
    public StationDetailDto assignUsers(Long id, List<Long> userIds) {
        Station station = find(id);
        Set<Long> wanted = Set.copyOf(userIds);
        List<User> found = userRepository.findAllById(wanted);
        if (found.size() != wanted.size()) {
            throw new BusinessRuleException("UNKNOWN_USER", "One or more selected users do not exist.");
        }
        Set<Long> before = userRepository.findAssignedToStation(id).stream().map(User::getId).collect(Collectors.toSet());

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (User u : found) {
            if (!before.contains(u.getId())) {
                u.getStations().add(station);
                added.add(u.getUsername());
            }
        }
        for (User u : userRepository.findAllById(before)) {
            if (!wanted.contains(u.getId())) {
                u.getStations().remove(station);
                removed.add(u.getUsername());
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", added);
        details.put("removed", removed);
        auditService.record(id, AuditAction.STATION_USERS_CHANGED, "STATION", id,
                "Users assigned to " + station.getName() + " changed (+" + added.size() + ", -" + removed.size() + ")", details);
        userRepository.flush();
        return get(id);
    }

    private Station find(Long id) {
        return stationRepository.findById(id).orElseThrow(() -> new NotFoundException("Station", id));
    }

    private static void validate(StationRequest r) {
        try {
            ZoneId.of(r.timezone().trim());
        } catch (DateTimeException e) {
            throw new BusinessRuleException("INVALID_TIMEZONE", "'" + r.timezone() + "' is not a valid time zone (for example Africa/Kigali).");
        }
        if (r.maxDeliveryKg().compareTo(r.dailyCapacityKg()) > 0) {
            throw new BusinessRuleException("INVALID_CAPACITY", "The largest single delivery cannot exceed the daily capacity.");
        }
        if (r.lowThresholdKg().compareTo(r.dailyCapacityKg()) > 0) {
            throw new BusinessRuleException("INVALID_THRESHOLD", "The low-capacity warning level cannot exceed the daily capacity.");
        }
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        boolean same = before instanceof BigDecimal b && after instanceof BigDecimal a ? b.compareTo(a) == 0 : Objects.equals(before, after);
        if (!same) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", before);
            change.put("to", after);
            changes.put(field, change);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

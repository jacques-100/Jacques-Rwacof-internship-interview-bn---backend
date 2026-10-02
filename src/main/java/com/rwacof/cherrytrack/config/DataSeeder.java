package com.rwacof.cherrytrack.config;

import com.rwacof.cherrytrack.dto.DeliveryDtos.CreateDeliveryRequest;
import com.rwacof.cherrytrack.dto.DeliveryDtos.DeliveryDto;
import com.rwacof.cherrytrack.dto.DeliveryDtos.GradeRequest;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerDto;
import com.rwacof.cherrytrack.dto.FarmerDtos.FarmerRequest;
import com.rwacof.cherrytrack.exception.CapacityExceededException;
import com.rwacof.cherrytrack.model.AuditAction;
import com.rwacof.cherrytrack.model.Department;
import com.rwacof.cherrytrack.model.Employment;
import com.rwacof.cherrytrack.model.EmploymentType;
import com.rwacof.cherrytrack.model.GradePrice;
import com.rwacof.cherrytrack.model.JobRole;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.DepartmentRepository;
import com.rwacof.cherrytrack.repository.EmploymentRepository;
import com.rwacof.cherrytrack.repository.FarmerRepository;
import com.rwacof.cherrytrack.repository.GradePriceRepository;
import com.rwacof.cherrytrack.repository.JobRoleRepository;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;
import com.rwacof.cherrytrack.service.DeliveryService;
import com.rwacof.cherrytrack.service.FarmerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Startup data. Two independent, opt-in behaviours:
 * <ul>
 *   <li>BOOTSTRAP_ADMIN_PASSWORD: creates the first ADMIN account when no users exist.</li>
 *   <li>SEED_DEMO_DATA=true: loads stations, staff, prices, farmers and ~2 weeks of history into an empty database.</li>
 * </ul>
 * Demo deliveries are created through the real services so every business rule and audit entry applies.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String[] NAMES = {
            "Jean de Dieu Habimana", "Marie Claire Uwimana", "Emmanuel Nkurunziza", "Immaculee Mukamana",
            "Theogene Niyonzima", "Beatrice Ingabire", "Innocent Hakizimana", "Console Nyirahabimana",
            "Védaste Ndayisaba", "Josiane Mukeshimana", "Aimable Twagirayezu", "Dative Uwamahoro",
            "Eric Mugisha", "Esperance Mukandayisenga", "Fidele Bizimana", "Claudine Umutoni",
            "Come Nsengiyumva", "Vestine Nyiransabimana", "Jean Baptiste Ntakirutimana", "Alphonsine Mukarugwiro",
            "Damascene Rutayisire", "Pelagie Mukarurangwa", "Olivier Kayitare", "Gaudence Uwizeyimana"};
    private static final String[] SECTORS = {"NDB", "GKM", "RTG", "BYM"};

    private final UserRepository userRepository;
    private final JobRoleRepository jobRoleRepository;
    private final StationRepository stationRepository;
    private final DepartmentRepository departmentRepository;
    private final EmploymentRepository employmentRepository;
    private final FarmerRepository farmerRepository;
    private final GradePriceRepository gradePriceRepository;
    private final PasswordEncoder passwordEncoder;
    private final FarmerService farmerService;
    private final DeliveryService deliveryService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Clock clock;

    public DataSeeder(UserRepository userRepository, JobRoleRepository jobRoleRepository, StationRepository stationRepository,
                      DepartmentRepository departmentRepository, EmploymentRepository employmentRepository,
                      FarmerRepository farmerRepository, GradePriceRepository gradePriceRepository,
                      PasswordEncoder passwordEncoder, FarmerService farmerService, DeliveryService deliveryService,
                      JdbcTemplate jdbc, TransactionTemplate tx, AppProperties props, Clock clock) {
        this.userRepository = userRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.stationRepository = stationRepository;
        this.departmentRepository = departmentRepository;
        this.employmentRepository = employmentRepository;
        this.farmerRepository = farmerRepository;
        this.gradePriceRepository = gradePriceRepository;
        this.passwordEncoder = passwordEncoder;
        this.farmerService = farmerService;
        this.deliveryService = deliveryService;
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String adminPassword = props.bootstrap().adminPassword();
        if (userRepository.count() == 0 && adminPassword != null && !adminPassword.isBlank()) {
            requireStrong(adminPassword, "BOOTSTRAP_ADMIN_PASSWORD");
            userRepository.save(new User("admin", passwordEncoder.encode(adminPassword), "System Administrator",
                    systemRole(Role.ADMIN), clock.instant()));
            log.info("Bootstrap administrator account 'admin' created.");
        }
        if (props.seed().enabled() && farmerRepository.count() == 0 && gradePriceRepository.count() == 0 && stationRepository.count() == 0) {
            seedDemoData();
        }
    }

    private JobRole systemRole(Role level) {
        return jobRoleRepository.findAllByOrderBySystemRoleDescNameAsc().stream()
                .filter(r -> r.isSystemRole() && r.getAccessLevel() == level).findFirst().orElseThrow();
    }

    private User user(String username, String fullName, JobRole role, String email, String phone, String password, Station... stations) {
        return tx.execute(status -> {
            User u = userRepository.findByUsername(username).orElseGet(() ->
                    new User(username, passwordEncoder.encode(password), fullName, role, clock.instant()));
            u.setEmail(email);
            u.setPhone(phone);
            for (Station s : stations) u.getStations().add(s);
            return userRepository.save(u);
        });
    }

    private void seedDemoData() {
        String password = props.seed().demoPassword();
        requireStrong(password, "SEED_DEMO_PASSWORD");
        Instant now = clock.instant();
        var cap = props.capacity();

        Station nduba = stationRepository.save(new Station("NDB", "Nduba Coffee Washing Station", "Nduba Sector, Gasabo", "Africa/Kigali",
                cap.dailyLimitKg(), cap.singleDeliveryMaxKg(), cap.lowThresholdKg(), now));
        Station gikomero = stationRepository.save(new Station("GKM", "Gikomero Washing Station", "Gikomero Sector, Gasabo", "Africa/Kigali",
                new BigDecimal("3000.00"), new BigDecimal("400.00"), new BigDecimal("300.00"), now));

        JobRole quality = jobRoleRepository.save(new JobRole("Quality Inspector", "Grades cherries on arrival.", Role.CLERK, now));
        JobRole weigh = jobRoleRepository.save(new JobRole("Weighing Clerk", "Receives and weighs deliveries.", Role.CLERK, now));

        User admin = userRepository.findByUsername("admin").orElseGet(() -> user("admin", "System Administrator", systemRole(Role.ADMIN),
                "admin@cherrytrack.rw", "0788000001", password));
        User supervisor = user("supervisor", "Alice Mukamurenzi", systemRole(Role.SUPERVISOR), "alice@cherrytrack.rw", "0788000002", password, nduba, gikomero);
        User clerk = user("clerk", "Patrick Nshimiyimana", weigh, "patrick@cherrytrack.rw", "0788000003", password, nduba);
        User inspector = user("inspector", "Sandrine Uwase", quality, "sandrine@cherrytrack.rw", "0788000004", password, nduba);
        User gkmClerk = user("gkm.clerk", "Jean Claude Mugabo", weigh, "jc@cherrytrack.rw", "0788000005", password, gikomero);

        // Prices are seeded directly so they can pre-date the history (the API clamps past dates).
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        gradePriceRepository.save(new GradePrice("A", new BigDecimal("1200.00"), longAgo, admin, now));
        gradePriceRepository.save(new GradePrice("B", new BigDecimal("800.00"), longAgo, admin, now));

        // staff directory
        Department intake = departmentRepository.save(new Department("INT", "Intake & Weighing", "Receives and weighs cherries.", supervisor, now));
        Department qa = departmentRepository.save(new Department("QLT", "Quality Control", "Grading and sorting.", inspector, now));
        departmentRepository.save(new Department("FIN", "Finance & Payments", "Pays farmers and keeps the books.", admin, now));
        LocalDate hired = LocalDate.now(clock).minusMonths(8);
        tx.executeWithoutResult(s -> {
            employmentRepository.save(new Employment(userRepository.getReferenceById(supervisor.getId()), intake, nduba, "Station Supervisor", EmploymentType.FULL_TIME, hired, null, null, now));
            employmentRepository.save(new Employment(userRepository.getReferenceById(clerk.getId()), intake, nduba, "Weighing Clerk", EmploymentType.SEASONAL, hired.plusMonths(2), null, "Harvest season contract.", now));
            employmentRepository.save(new Employment(userRepository.getReferenceById(inspector.getId()), qa, nduba, "Quality Inspector", EmploymentType.FULL_TIME, hired.plusMonths(1), null, null, now));
            employmentRepository.save(new Employment(userRepository.getReferenceById(gkmClerk.getId()), intake, gikomero, "Weighing Clerk", EmploymentType.PART_TIME, hired.plusMonths(3), null, null, now));
        });

        Random random = new Random(20261001L);
        List<Long> farmerIds = new ArrayList<>();
        CurrentUser.runAs(AuthUser.withTemplate(supervisor), () -> {
            for (int i = 0; i < NAMES.length; i++) {
                String coop = SECTORS[i % SECTORS.length] + "-" + String.format("%04d", 1001 + i);
                String phone = "07" + (random.nextBoolean() ? "88" : "82") + String.format("%06d", random.nextInt(1_000_000));
                FarmerDto f = farmerService.create(new FarmerRequest(NAMES[i], phone, coop, null));
                farmerIds.add(f.id());
            }
        });

        LocalDate today = LocalDate.now(clock);
        for (int offset = 14; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            if (day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY && offset != 0) {
                continue;
            }
            seedDay(nduba, day, offset, farmerIds, clerk, inspector, supervisor, random);
            if (offset <= 7) {
                seedDay(gikomero, day, offset, farmerIds, gkmClerk, gkmClerk, supervisor, random);
            }
        }
        log.info("Demo data loaded: 2 stations, {} farmers, users admin/supervisor/clerk/inspector/gkm.clerk.", farmerIds.size());
    }

    private void seedDay(Station station, LocalDate day, int offset, List<Long> farmerIds, User receiver, User grader,
                         User payer, Random random) {
        boolean main = station.getCode().equals("NDB");
        boolean heavyDay = main && offset == 3;  // one near-capacity day makes the dashboard history interesting
        int count = heavyDay ? 40 : offset == 0 ? (main ? 16 : 7) : 5 + random.nextInt(main ? 8 : 4);
        List<DeliveryDto> created = new ArrayList<>();
        CurrentUser.runAs(AuthUser.withTemplate(receiver), () -> {
            for (int i = 0; i < count; i++) {
                double kg = heavyDay ? 150 + random.nextInt(300) : 40 + random.nextInt(offset == 0 ? 300 : 340);
                try {
                    created.add(deliveryService.create(station, new CreateDeliveryRequest(
                            farmerIds.get(random.nextInt(farmerIds.size())), day, BigDecimal.valueOf(kg).setScale(2))));
                } catch (CapacityExceededException full) {
                    break;
                }
            }
        });

        int n = created.size();
        for (int i = 0; i < n; i++) {
            DeliveryDto d = created.get(i);
            // Today keeps a realistic live mix; earlier days are mostly paid.
            String outcome = offset == 0
                    ? (i < n * 0.45 ? "PAID" : i < n * 0.75 ? "GRADED" : i < n - 1 ? "RECEIVED" : "REJECTED")
                    : offset == 1 && i >= n - 3 ? "GRADED"
                    : random.nextInt(100) < 8 ? "REJECTED" : "PAID";
            String grade = random.nextInt(100) < 65 ? "A" : "B";
            BigDecimal moisture = BigDecimal.valueOf(10 + random.nextInt(35) / 10.0).setScale(1);
            GradeRequest form = new GradeRequest(grade, moisture, null);
            switch (outcome) {
                case "REJECTED" -> CurrentUser.runAs(AuthUser.withTemplate(grader), () ->
                        deliveryService.reject(d.id(), random.nextBoolean() ? "Unripe cherries" : "Excess moisture and debris"));
                case "GRADED" -> CurrentUser.runAs(AuthUser.withTemplate(grader), () -> deliveryService.grade(d.id(), form));
                case "PAID" -> {
                    CurrentUser.runAs(AuthUser.withTemplate(grader), () -> deliveryService.grade(d.id(), form));
                    CurrentUser.runAs(AuthUser.withTemplate(payer), () -> deliveryService.pay(d.id()));
                }
                default -> { }
            }
            backdate(d.id(), day, i, n);
        }
    }

    /** Spreads timestamps across the working day so lists and timelines look realistic. */
    private void backdate(Long id, LocalDate day, int index, int total) {
        ZoneId zone = clock.getZone();
        Instant now = clock.instant();
        Instant created = day.atTime(LocalTime.of(6, 30)).plusMinutes(index * 11L).atZone(zone).toInstant();
        if (created.isAfter(now)) {
            created = now.minusSeconds((total - index) * 180L);
        }
        Instant graded = created.plusSeconds(25 * 60);
        Instant paid = graded.plusSeconds(20 * 60);
        if (graded.isAfter(now)) {
            graded = now.minusSeconds(60L * (total - index));
        }
        if (paid.isAfter(now)) {
            paid = now.minusSeconds(30L * (total - index));
        }
        Timestamp c = Timestamp.from(created);
        Timestamp g = Timestamp.from(graded);
        Timestamp p = Timestamp.from(paid);
        jdbc.update("UPDATE deliveries SET created_at = ?, updated_at = ?, "
                        + "graded_at = IF(graded_at IS NULL, NULL, ?), "
                        + "paid_at = IF(paid_at IS NULL, NULL, ?), "
                        + "rejected_at = IF(rejected_at IS NULL, NULL, ?) WHERE id = ?",
                utc(c), utc(p), utc(g), utc(p), utc(g), id);
        jdbc.update("UPDATE audit_logs SET occurred_at = ? WHERE entity_type = 'DELIVERY' AND entity_id = ? AND action = ?",
                utc(c), id, AuditAction.DELIVERY_CREATED.name());
        jdbc.update("UPDATE audit_logs SET occurred_at = ? WHERE entity_type = 'DELIVERY' AND entity_id = ? AND action IN (?, ?)",
                utc(g), id, AuditAction.DELIVERY_GRADED.name(), AuditAction.DELIVERY_REJECTED.name());
        jdbc.update("UPDATE audit_logs SET occurred_at = ? WHERE entity_type = 'DELIVERY' AND entity_id = ? AND action = ?",
                utc(p), id, AuditAction.DELIVERY_PAID.name());
    }

    /** DATETIME columns hold UTC wall-clock values; pass them as UTC literals to avoid JVM-zone shifts. */
    private static java.time.LocalDateTime utc(Timestamp t) {
        return t.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private static void requireStrong(String password, String variable) {
        if (password == null || password.length() < 10) {
            throw new IllegalStateException(variable + " must be set to at least 10 characters.");
        }
    }
}

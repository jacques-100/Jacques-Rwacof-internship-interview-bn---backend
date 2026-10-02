package com.rwacof.cherrytrack.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.GradePrice;
import com.rwacof.cherrytrack.model.JobRole;
import com.rwacof.cherrytrack.model.Permission;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.FarmerRepository;
import com.rwacof.cherrytrack.repository.GradePriceRepository;
import com.rwacof.cherrytrack.repository.JobRoleRepository;
import com.rwacof.cherrytrack.repository.StationRepository;
import com.rwacof.cherrytrack.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for tests that need the real schema (Flyway migrations on MySQL).
 * Uses the database named by TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD when set; otherwise starts a
 * MySQL Testcontainer (requires Docker). Each test starts from empty tables plus one station ("TST"),
 * and every user created with {@link #user(Role)} is assigned to it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final String PASSWORD = "Str0ng-Test-Pass!";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static MySQLContainer<?> container;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "root"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        } else {
            synchronized (AbstractIntegrationTest.class) {
                if (container == null) {
                    container = new MySQLContainer<>("mysql:8.0.36").withDatabaseName("cherrytrack_test");
                    container.start();
                }
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected FarmerRepository farmerRepository;
    @Autowired protected GradePriceRepository priceRepository;
    @Autowired protected StationRepository stationRepository;
    @Autowired protected JobRoleRepository jobRoleRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected Clock clock;

    /** The station every request in a test operates on unless it says otherwise. */
    protected Station station;

    @BeforeEach
    void cleanDatabase() {
        for (String table : new String[]{"audit_logs", "employments", "deliveries", "daily_capacity", "refresh_tokens",
                "grade_prices", "farmers", "departments", "user_stations", "system_settings", "stored_images", "users", "stations"}) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM role_permissions WHERE job_role_id IN (SELECT id FROM job_roles WHERE system_role = FALSE)");
        jdbc.update("DELETE FROM job_roles WHERE system_role = FALSE");
        jdbc.update("DELETE FROM system_settings");   // back to the catalogue defaults
        // built-in roles go back to their standard permissions, whatever an earlier test changed
        jdbc.update("DELETE FROM role_permissions");
        for (JobRole role : jobRoleRepository.findAll()) {
            for (Permission p : Permission.template(role.getAccessLevel())) {
                jdbc.update("INSERT INTO role_permissions (job_role_id, permission) VALUES (?, ?)", role.getId(), p.name());
            }
        }
        jdbc.update("DELETE FROM grades WHERE code NOT IN ('A', 'B')");
        jdbc.update("UPDATE grades SET active = TRUE");
        station = station("TST", "Test Station", "5000", "500");
    }

    // ------------------------------------------------------------------ data helpers

    protected LocalDate today() {
        return LocalDate.now(clock);
    }

    protected Station station(String code, String name, String dailyKg, String maxKg) {
        return stationRepository.save(new Station(code, name, "Test location", "Africa/Kigali",
                new BigDecimal(dailyKg), new BigDecimal(maxKg), new BigDecimal("500"), Instant.now()));
    }

    protected JobRole systemRole(Role level) {
        return jobRoleRepository.findAllByOrderBySystemRoleDescNameAsc().stream()
                .filter(r -> r.isSystemRole() && r.getAccessLevel() == level).findFirst().orElseThrow();
    }

    /** A user with the built-in role for {@code role}, assigned to the default test station. */
    protected User user(Role role) {
        return user(role, station);
    }

    protected User user(Role role, Station... stations) {
        User u = new User(role.name().toLowerCase() + SEQ.incrementAndGet(), passwordEncoder.encode(PASSWORD),
                role + " Tester", systemRole(role), Instant.now());
        for (Station s : stations) {
            u.getStations().add(s);
        }
        return userRepository.save(u);
    }

    /** Assigns an existing user to a station with plain SQL (avoids touching lazy collections outside a transaction). */
    protected void assign(long userId, Station to) {
        jdbc.update("INSERT IGNORE INTO user_stations (user_id, station_id) VALUES (?, ?)", userId, to.getId());
    }

    protected Farmer farmer() {
        int n = SEQ.incrementAndGet();
        return farmerRepository.save(new Farmer("Test Farmer " + n, "0788" + String.format("%06d", n), "TST-" + String.format("%04d", n), Instant.now()));
    }

    protected void price(String grade, String pricePerKg) {
        priceRepository.save(new GradePrice(grade, new BigDecimal(pricePerKg), Instant.parse("2020-01-01T00:00:00Z"), null, Instant.now()));
    }

    protected void standardPrices() {
        price("A", "1200.00");
        price("B", "800.00");
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected String tokenFor(User user) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** Authenticates the request and scopes it to the default station. */
    protected MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return as(token, station, builder);
    }

    protected MockHttpServletRequestBuilder as(String token, Station inStation, MockHttpServletRequestBuilder builder) {
        MockHttpServletRequestBuilder b = builder.header("Authorization", "Bearer " + token);
        return inStation == null ? b : b.header("X-Station-Id", inStation.getId());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}

package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Job roles, departments, employments and the user list with its filters. */
class DirectoryIntegrationTest extends AbstractIntegrationTest {

    private String admin;
    private String supervisor;
    private User adminUser;

    @BeforeEach
    void setUp() throws Exception {
        adminUser = user(Role.ADMIN);
        admin = tokenFor(adminUser);
        supervisor = tokenFor(user(Role.SUPERVISOR));
    }

    private long createRole(String name, String level) throws Exception {
        return body(mvc.perform(as(admin, post("/api/v1/roles")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"d\",\"accessLevel\":\"" + level + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private long createUser(String username, long roleId, String extra) throws Exception {
        return body(mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"fullName\":\"" + username + " Person\",\"password\":\"" + PASSWORD + "\",\"jobRoleId\":" + roleId + extra + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    // ------------------------------------------------------------------ roles

    @Test
    void theBuiltInRolesExistAndCustomRolesCanBeAdded() throws Exception {
        mvc.perform(as(admin, get("/api/v1/roles"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.systemRole==true)].name").value(org.hamcrest.Matchers.containsInAnyOrder("Administrator", "Supervisor", "Clerk")));
        createRole("Quality Inspector", "CLERK");
        mvc.perform(as(admin, get("/api/v1/roles"))).andExpect(jsonPath("$[*].name", hasItem("Quality Inspector")));
    }

    @Test
    void roleManagementIsAdminOnly() throws Exception {
        mvc.perform(as(supervisor, get("/api/v1/roles"))).andExpect(status().isForbidden());
        mvc.perform(as(supervisor, post("/api/v1/roles")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"accessLevel\":\"ADMIN\"}")).andExpect(status().isForbidden());
    }

    @Test
    void aCustomRoleGrantsExactlyItsAccessLevel() throws Exception {
        long roleId = createRole("Deputy Supervisor", "SUPERVISOR");
        createUser("deputy", roleId, "");
        User deputy = userRepository.findByUsername("deputy").orElseThrow();
        assign(deputy.getId(), station);
        String token = tokenFor(deputy);

        mvc.perform(as(token, get("/api/v1/reports").param("type", "DAILY_INTAKE"))).andExpect(status().isOk());   // supervisor level
        mvc.perform(as(token, get("/api/v1/users"))).andExpect(status().isForbidden());                            // but not admin level
        mvc.perform(as(token, get("/api/v1/auth/me"))).andExpect(jsonPath("$.role").value("SUPERVISOR"))
                .andExpect(jsonPath("$.jobRole.name").value("Deputy Supervisor"));
    }

    @Test
    void builtInRolesAreProtected() throws Exception {
        long clerkRole = systemRole(Role.CLERK).getId();
        mvc.perform(as(admin, put("/api/v1/roles/" + clerkRole)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"accessLevel\":\"CLERK\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE"));
        mvc.perform(as(admin, put("/api/v1/roles/" + clerkRole)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Clerk\",\"accessLevel\":\"ADMIN\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(admin, put("/api/v1/roles/" + clerkRole)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Clerk\",\"accessLevel\":\"CLERK\",\"active\":false}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(admin, put("/api/v1/roles/" + clerkRole)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Clerk\",\"description\":\"New description\",\"accessLevel\":\"CLERK\"}")).andExpect(status().isOk());
    }

    @Test
    void aRoleInUseCannotSilentlyChangeWhatPeopleCanDo() throws Exception {
        long roleId = createRole("Inspector", "CLERK");
        createUser("insp", roleId, "");
        mvc.perform(as(admin, put("/api/v1/roles/" + roleId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Inspector\",\"accessLevel\":\"SUPERVISOR\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
        mvc.perform(as(admin, put("/api/v1/roles/" + roleId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Inspector\",\"accessLevel\":\"CLERK\",\"active\":false}")).andExpect(status().isConflict());
        mvc.perform(as(admin, put("/api/v1/roles/" + roleId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Senior Inspector\",\"accessLevel\":\"CLERK\"}")).andExpect(status().isOk());   // renaming is fine
    }

    @Test
    void roleNamesAreUniqueAndInactiveRolesCannotBeAssigned() throws Exception {
        long roleId = createRole("Temp Role", "CLERK");
        mvc.perform(as(admin, post("/api/v1/roles")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"temp role\",\"accessLevel\":\"CLERK\"}")).andExpect(status().isConflict());
        mvc.perform(as(admin, put("/api/v1/roles/" + roleId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Temp Role\",\"accessLevel\":\"CLERK\",\"active\":false}")).andExpect(status().isOk());
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"someone\",\"fullName\":\"Some One\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":" + roleId + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_INACTIVE"));
    }

    @Test
    void changingAUsersRoleChangesTheirAccessImmediately() throws Exception {
        long clerkRole = systemRole(Role.CLERK).getId();
        long id = createUser("mover", clerkRole, "");
        User mover = userRepository.findById(id).orElseThrow();
        assign(mover.getId(), station);
        String token = tokenFor(mover);
        mvc.perform(as(token, get("/api/v1/reports").param("type", "DAILY_INTAKE"))).andExpect(status().isForbidden());

        mvc.perform(as(admin, put("/api/v1/users/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Mover\",\"jobRoleId\":" + systemRole(Role.SUPERVISOR).getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SUPERVISOR"));
        mvc.perform(as(token, get("/api/v1/reports").param("type", "DAILY_INTAKE"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ users: create, list, filter

    @Test
    void usersAreCreatedWithContactDetailsAndStations() throws Exception {
        long id = createUser("fieldstaff", systemRole(Role.CLERK).getId(),
                ",\"email\":\"Field.Staff@Example.com\",\"phone\":\"0788123456\",\"stationIds\":[" + station.getId() + "]");
        mvc.perform(as(admin, get("/api/v1/users/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("field.staff@example.com"))      // lower-cased
                .andExpect(jsonPath("$.phone").value("0788123456"))
                .andExpect(jsonPath("$.stations[0].code").value("TST"))
                .andExpect(jsonPath("$.jobRole.name").value("Clerk"));
    }

    @Test
    void userValidation() throws Exception {
        long clerkRole = systemRole(Role.CLERK).getId();
        createUser("first", clerkRole, ",\"email\":\"dup@example.com\"");
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"second\",\"fullName\":\"S\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":" + clerkRole + ",\"email\":\"DUP@example.com\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"third\",\"fullName\":\"S\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":" + clerkRole + ",\"phone\":\"123\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"fourth\",\"fullName\":\"S\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":" + clerkRole + ",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"fifth\",\"fullName\":\"S\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":999999}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sixth\",\"fullName\":\"S\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":" + clerkRole + ",\"stationIds\":[999999]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theUserListFiltersSortsAndPages() throws Exception {
        long clerkRole = systemRole(Role.CLERK).getId();
        long inspector = createRole("Inspector", "CLERK");
        createUser("anna", clerkRole, "");
        createUser("bruno", inspector, ",\"stationIds\":[" + station.getId() + "]");
        long carl = createUser("carl", clerkRole, "");
        mvc.perform(as(admin, patch("/api/v1/users/" + carl + "/active")).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());

        mvc.perform(as(admin, get("/api/v1/users").param("q", "BRU"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/users").param("role", "ADMIN"))).andExpect(jsonPath("$.content[*].username", hasItem(adminUser.getUsername())));
        mvc.perform(as(admin, get("/api/v1/users").param("active", "false"))).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("carl"));
        mvc.perform(as(admin, get("/api/v1/users").param("jobRoleId", String.valueOf(inspector)))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/users").param("stationId", String.valueOf(station.getId()))))
                .andExpect(jsonPath("$.content[*].username", hasItem("bruno")));
        mvc.perform(as(admin, get("/api/v1/users").param("size", "2").param("sortBy", "username").param("dir", "asc")))
                .andExpect(jsonPath("$.content", hasSize(2))).andExpect(jsonPath("$.totalPages").value(org.hamcrest.Matchers.greaterThan(1)));
        mvc.perform(as(admin, get("/api/v1/users").param("sortBy", "passwordHash"))).andExpect(status().isOk());   // not a sortable field: ignored
    }

    // ------------------------------------------------------------------ departments

    private long createDepartment(String code, String name, String extra) throws Exception {
        return body(mvc.perform(as(admin, post("/api/v1/departments")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"" + extra + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    @Test
    void departmentsHaveUniqueCodesAndNamesAndAnActiveHead() throws Exception {
        long id = createDepartment("qlt", "Quality Control", ",\"headUserId\":" + adminUser.getId());
        mvc.perform(as(admin, get("/api/v1/departments"))).andExpect(jsonPath("$[0].code").value("QLT"))
                .andExpect(jsonPath("$[0].headName").value(adminUser.getFullName()));
        mvc.perform(as(admin, post("/api/v1/departments")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"QLT\",\"name\":\"Other\"}")).andExpect(status().isConflict());
        mvc.perform(as(admin, post("/api/v1/departments")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"OTH\",\"name\":\"quality control\"}")).andExpect(status().isConflict());

        User gone = user(Role.CLERK);
        gone.setActive(false);
        userRepository.save(gone);
        mvc.perform(as(admin, put("/api/v1/departments/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"QLT\",\"name\":\"Quality Control\",\"headUserId\":" + gone.getId() + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("USER_INACTIVE"));
        mvc.perform(as(admin, put("/api/v1/departments/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"QLT\",\"name\":\"Quality & Grading\",\"active\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ------------------------------------------------------------------ employments

    private String employment(long userId, Long departmentId, String title, String type, String start, String end) {
        return "{\"userId\":" + userId + (departmentId == null ? "" : ",\"departmentId\":" + departmentId)
                + ",\"stationId\":" + station.getId() + ",\"jobTitle\":\"" + title + "\",\"employmentType\":\"" + type
                + "\",\"startDate\":\"" + start + "\"" + (end == null ? "" : ",\"endDate\":\"" + end + "\"") + "}";
    }

    @Test
    void employmentRecordsAreCreatedValidatedAndFiltered() throws Exception {
        long dept = createDepartment("INT", "Intake", "");
        long other = createDepartment("FIN", "Finance", "");
        long a = createUser("emp.a", systemRole(Role.CLERK).getId(), "");
        long b = createUser("emp.b", systemRole(Role.CLERK).getId(), "");
        String past = today().minusYears(1).toString();
        String ended = today().minusMonths(1).toString();
        String future = today().plusMonths(1).toString();

        mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                        .content(employment(a, dept, "Weighing Clerk", "FULL_TIME", past, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.departmentName").value("Intake")).andExpect(jsonPath("$.stationName").value("Test Station"));
        mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                .content(employment(b, other, "Cashier", "SEASONAL", past, ended))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ENDED"));
        mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                .content(employment(b, dept, "Night Guard", "CONTRACT", future, null))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("UPCOMING"));

        mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                .content(employment(a, dept, "Bad dates", "FULL_TIME", today().toString(), past))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DATES"));
        mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                .content(employment(a, dept, "Bad type", "VOLUNTEER", past, null))).andExpect(status().isBadRequest());
        mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                .content(employment(999999, dept, "Ghost", "FULL_TIME", past, null))).andExpect(status().isNotFound());

        mvc.perform(as(admin, get("/api/v1/employments"))).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(as(admin, get("/api/v1/employments").param("status", "ACTIVE"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/employments").param("status", "ENDED"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/employments").param("status", "UPCOMING"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/employments").param("departmentId", String.valueOf(dept)))).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(admin, get("/api/v1/employments").param("type", "SEASONAL"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/employments").param("userId", String.valueOf(b)))).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(admin, get("/api/v1/employments").param("q", "guard"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get("/api/v1/employments").param("stationId", String.valueOf(station.getId())))).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(as(supervisor, get("/api/v1/employments"))).andExpect(status().isForbidden());
        mvc.perform(as(admin, get("/api/v1/departments"))).andExpect(jsonPath("$[?(@.code=='INT')].currentStaff").value(1));   // upcoming and ended do not count
    }

    @Test
    void anEmploymentCanBeEndedButStaysWithTheSamePerson() throws Exception {
        long dept = createDepartment("INT", "Intake", "");
        long a = createUser("emp.c", systemRole(Role.CLERK).getId(), "");
        long b = createUser("emp.d", systemRole(Role.CLERK).getId(), "");
        long id = body(mvc.perform(as(admin, post("/api/v1/employments")).contentType(MediaType.APPLICATION_JSON)
                .content(employment(a, dept, "Clerk", "FULL_TIME", today().minusMonths(6).toString(), null))).andReturn()).get("id").asLong();

        mvc.perform(as(admin, put("/api/v1/employments/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(employment(a, dept, "Clerk", "FULL_TIME", today().minusMonths(6).toString(), today().minusDays(1).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        mvc.perform(as(admin, put("/api/v1/employments/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content(employment(b, dept, "Clerk", "FULL_TIME", today().minusMonths(6).toString(), null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EMPLOYEE_FIXED"));
    }
}

package com.rwacof.cherrytrack.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What a role may do is data: editable per role, enforced by the server, effective immediately. */
class PermissionsIntegrationTest extends AbstractIntegrationTest {

    private String admin;
    private Farmer farmer;

    @BeforeEach
    void setUp() throws Exception {
        standardPrices();
        admin = tokenFor(user(Role.ADMIN));
        farmer = farmer();
    }

    private long createRole(String name, String level, String... permissions) throws Exception {
        String perms = permissions.length == 0 ? "" : ",\"permissions\":[" + String.join(",", java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList()) + "]";
        return body(mvc.perform(as(admin, post("/api/v1/roles")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"accessLevel\":\"" + level + "\"" + perms + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private User userWithRole(String username, long roleId) throws Exception {
        long id = body(mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"fullName\":\"" + username + " P\",\"password\":\"" + PASSWORD + "\",\"jobRoleId\":" + roleId
                        + ",\"stationIds\":[" + station.getId() + "]}")).andExpect(status().isCreated()).andReturn()).get("id").asLong();
        return userRepository.findById(id).orElseThrow();
    }

    private ResultActions setPermissions(long roleId, String... permissions) throws Exception {
        String list = String.join(",", java.util.Arrays.stream(permissions).map(p -> "\"" + p + "\"").toList());
        return mvc.perform(as(admin, put("/api/v1/roles/" + roleId + "/permissions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[" + list + "]}"));
    }

    private ResultActions receive(String token) throws Exception {
        return mvc.perform(as(token, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + farmer.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":100}"));
    }

    // ------------------------------------------------------------------ catalogue and role listing

    @Test
    void theCatalogueListsEveryPermissionWithItsGroupAndDescription() throws Exception {
        mvc.perform(as(admin, get("/api/v1/permissions"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("DELIVERY_PAY")))
                .andExpect(jsonPath("$[*].code", hasItem("PERMISSION_MANAGE")))
                .andExpect(jsonPath("$[0].group").isNotEmpty())
                .andExpect(jsonPath("$[0].description").isNotEmpty());
        mvc.perform(as(tokenFor(user(Role.CLERK)), get("/api/v1/permissions"))).andExpect(status().isForbidden());
        mvc.perform(as(tokenFor(user(Role.SUPERVISOR)), get("/api/v1/permissions"))).andExpect(status().isForbidden());
    }

    @Test
    void builtInRolesCarryTheirStandardPermissions() throws Exception {
        JsonNode roles = body(mvc.perform(as(admin, get("/api/v1/roles"))).andReturn());
        for (JsonNode role : roles) {
            String perms = role.get("permissions").toString();
            switch (role.get("name").asText()) {
                case "Clerk" -> assertThat(perms).contains("DELIVERY_GRADE").doesNotContain("DELIVERY_PAY").doesNotContain("USER_MANAGE");
                case "Supervisor" -> assertThat(perms).contains("DELIVERY_PAY", "PRICE_MANAGE", "REPORT_VIEW").doesNotContain("USER_MANAGE");
                case "Administrator" -> assertThat(role.get("permissions")).hasSize(com.rwacof.cherrytrack.model.Permission.values().length);
                default -> { }
            }
        }
    }

    @Test
    void myProfileListsMyPermissions() throws Exception {
        mvc.perform(as(tokenFor(user(Role.CLERK)), get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.permissions", hasItem("DELIVERY_CREATE")))
                .andExpect(jsonPath("$.permissions", not(hasItem("DELIVERY_PAY"))));
    }

    // ------------------------------------------------------------------ enforcement is data-driven

    @Test
    void aRoleCanBeCreatedWithExactlyTheChosenPermissions() throws Exception {
        long roleId = createRole("Receiving Only", "CLERK", "DELIVERY_CREATE");
        String token = tokenFor(userWithRole("receiver", roleId));

        receive(token).andExpect(status().isCreated()).andExpect(jsonPath("$.allowedActions", hasSize(0)));   // nothing else allowed
        long id = body(receive(token).andReturn()).get("id").asLong();
        mvc.perform(as(token, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(token, post("/api/v1/deliveries/" + id + "/reject")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantingAPermissionTakesEffectOnTheVeryNextRequest() throws Exception {
        long roleId = createRole("Receiving Only", "CLERK", "DELIVERY_CREATE");
        String token = tokenFor(userWithRole("receiver", roleId));
        long id = body(receive(token).andReturn()).get("id").asLong();
        mvc.perform(as(token, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}"))
                .andExpect(status().isForbidden());

        setPermissions(roleId, "DELIVERY_CREATE", "DELIVERY_GRADE").andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", hasItem("DELIVERY_GRADE")));

        // same token, no re-login
        mvc.perform(as(token, get("/api/v1/deliveries/" + id))).andExpect(jsonPath("$.allowedActions", hasItem("GRADE")));
        mvc.perform(as(token, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void revokingAPermissionTakesEffectImmediatelyToo() throws Exception {
        long roleId = createRole("Full Clerk", "CLERK");   // starts from the clerk template
        String token = tokenFor(userWithRole("fullclerk", roleId));
        receive(token).andExpect(status().isCreated());

        setPermissions(roleId, "DELIVERY_GRADE").andExpect(status().isOk());
        receive(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void permissionsDecideReportsPaymentsAndPricesNotTheRoleName() throws Exception {
        long roleId = createRole("Cashier", "CLERK", "DELIVERY_PAY");        // a "clerk" who may pay and nothing else
        String cashier = tokenFor(userWithRole("cashier", roleId));
        long delivery = body(receive(tokenFor(user(Role.CLERK))).andReturn()).get("id").asLong();
        mvc.perform(as(tokenFor(user(Role.CLERK)), post("/api/v1/deliveries/" + delivery + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}")).andExpect(status().isOk());

        mvc.perform(as(cashier, post("/api/v1/deliveries/" + delivery + "/pay"))).andExpect(status().isOk());
        mvc.perform(as(cashier, get("/api/v1/reports").param("type", "PAYMENT"))).andExpect(status().isForbidden());
        mvc.perform(as(cashier, post("/api/v1/prices")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\",\"pricePerKg\":1}")).andExpect(status().isForbidden());

        // and a supervisor-level role stripped of PAY cannot pay
        long supervisorRole = createRole("Supervisor Without Pay", "SUPERVISOR");
        setPermissions(supervisorRole, "REPORT_VIEW").andExpect(status().isOk());
        String reporter = tokenFor(userWithRole("reporter", supervisorRole));
        mvc.perform(as(reporter, get("/api/v1/reports").param("type", "PAYMENT"))).andExpect(status().isOk());
        mvc.perform(as(reporter, post("/api/v1/deliveries/" + delivery + "/pay"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ rules

    @Test
    void theAdministratorRoleCannotBeEdited() throws Exception {
        setPermissions(systemRole(Role.ADMIN).getId(), "DELIVERY_CREATE").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_FIXED"));
    }

    @Test
    void builtInSupervisorAndClerkRolesCanBeTuned() throws Exception {
        long clerkRole = systemRole(Role.CLERK).getId();
        setPermissions(clerkRole, "DELIVERY_CREATE", "DELIVERY_GRADE", "DELIVERY_REJECT", "DELIVERY_CORRECT_WEIGHT", "FARMER_MANAGE", "REPORT_VIEW")
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions", hasItem("REPORT_VIEW")));
        mvc.perform(as(tokenFor(user(Role.CLERK)), get("/api/v1/reports").param("type", "DAILY_INTAKE"))).andExpect(status().isOk());
    }

    @Test
    void unknownPermissionsAreRefused() throws Exception {
        long roleId = createRole("Whatever", "CLERK");
        setPermissions(roleId, "DELIVERY_CREATE", "LAUNCH_ROCKETS").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
        mvc.perform(as(admin, post("/api/v1/roles")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bad\",\"accessLevel\":\"CLERK\",\"permissions\":[\"NOPE\"]}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changesAreAuditedWithWhatWasAddedAndRemoved() throws Exception {
        long roleId = createRole("Audited", "CLERK", "DELIVERY_CREATE", "DELIVERY_GRADE");
        setPermissions(roleId, "DELIVERY_CREATE", "DELIVERY_REJECT").andExpect(status().isOk());
        setPermissions(roleId, "DELIVERY_CREATE", "DELIVERY_REJECT").andExpect(status().isOk());   // no change: not audited again

        JsonNode logs = body(mvc.perform(as(admin, get("/api/v1/audit-logs").param("entityType", "ROLE").param("action", "ROLE_PERMISSIONS_CHANGED"))).andReturn());
        assertThat(logs.get("totalElements").asInt()).isEqualTo(1);
        JsonNode details = logs.get("content").get(0).get("details");
        assertThat(details.get("added").toString()).contains("DELIVERY_REJECT");
        assertThat(details.get("removed").toString()).contains("DELIVERY_GRADE");
    }

    // ------------------------------------------------------------------ no privilege escalation

    @Test
    void someoneWhoManagesUsersCannotManagePermissions() throws Exception {
        long roleId = createRole("People Manager", "SUPERVISOR", "USER_MANAGE");
        String manager = tokenFor(userWithRole("peopleman", roleId));
        long target = createRole("Target", "CLERK");

        mvc.perform(as(manager, put("/api/v1/roles/" + target + "/permissions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"DELIVERY_CREATE\"]}")).andExpect(status().isForbidden());
        mvc.perform(as(manager, get("/api/v1/roles"))).andExpect(status().isOk());   // but can list roles and the catalogue
        mvc.perform(as(manager, get("/api/v1/permissions"))).andExpect(status().isOk());
    }

    @Test
    void youCannotGrantAPermissionYouDoNotHoldYourself() throws Exception {
        long managerRole = createRole("People Manager", "SUPERVISOR", "USER_MANAGE", "PERMISSION_MANAGE", "DELIVERY_CREATE");
        String manager = tokenFor(userWithRole("peopleman", managerRole));

        // a new role may only contain permissions the creator holds
        mvc.perform(as(manager, post("/api/v1/roles")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Too Much\",\"accessLevel\":\"CLERK\",\"permissions\":[\"DELIVERY_CREATE\",\"DELIVERY_PAY\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CANNOT_GRANT"));
        // an existing role can't be boosted past the editor's own permissions either
        long target = createRole("Target", "CLERK", "DELIVERY_CREATE");
        mvc.perform(as(manager, put("/api/v1/roles/" + target + "/permissions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"DELIVERY_CREATE\",\"USER_MANAGE\",\"SETTINGS_MANAGE\"]}")).andExpect(status().isForbidden());
        // ...nor can they hand someone a role that holds more than they do (including themselves)
        long strong = systemRole(Role.ADMIN).getId();
        mvc.perform(as(manager, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sneaky\",\"fullName\":\"S\",\"password\":\"" + PASSWORD + "\",\"jobRoleId\":" + strong + "}")).andExpect(status().isForbidden());
        // within their own permissions it works
        mvc.perform(as(manager, put("/api/v1/roles/" + target + "/permissions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"DELIVERY_CREATE\"]}")).andExpect(status().isOk());
    }

    @Test
    void aCustomRoleAtAdministratorLevelStillDoesNotBypassPermissions() throws Exception {
        long roleId = createRole("Almost Admin", "ADMIN", "DELIVERY_CREATE");
        String token = tokenFor(userWithRole("almost", roleId));
        mvc.perform(as(token, get("/api/v1/users"))).andExpect(status().isForbidden());
        mvc.perform(as(token, get("/api/v1/audit-logs"))).andExpect(status().isForbidden());
        receive(token).andExpect(status().isCreated());
    }
}

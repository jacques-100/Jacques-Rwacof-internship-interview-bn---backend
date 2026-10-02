package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role-based access is enforced by the backend, independent of what the UI shows. */
class AuthorizationIntegrationTest extends AbstractIntegrationTest {

    private User adminUser;
    private String admin;
    private String supervisor;
    private String clerk;

    @BeforeEach
    void setUp() throws Exception {
        standardPrices();
        adminUser = user(Role.ADMIN);
        admin = tokenFor(adminUser);
        supervisor = tokenFor(user(Role.SUPERVISOR));
        clerk = tokenFor(user(Role.CLERK));
    }

    private long deliveryGraded() throws Exception {
        long farmerId = farmer().getId();
        var created = body(mvc.perform(as(clerk, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + farmerId + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":100}")).andReturn());
        long id = created.get("id").asLong();
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"grade\":\"A\"}")).andExpect(status().isOk());
        return id;
    }

    @Test
    void clerkCannotPay() throws Exception {
        long id = deliveryGraded();
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/pay"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(supervisor, post("/api/v1/deliveries/" + id + "/pay"))).andExpect(status().isOk());
    }

    @Test
    void adminCanPayToo() throws Exception {
        long id = deliveryGraded();
        mvc.perform(as(admin, post("/api/v1/deliveries/" + id + "/pay"))).andExpect(status().isOk());
    }

    @Test
    void clerkSeesNoPayActionOnAGradedDelivery() throws Exception {
        long id = deliveryGraded();
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id))).andExpect(jsonPath("$.allowedActions.length()").value(0));
        mvc.perform(as(supervisor, get("/api/v1/deliveries/" + id))).andExpect(jsonPath("$.allowedActions[0]").value("PAY"));
    }

    @Test
    void onlySupervisorsAndAdminsManagePrices() throws Exception {
        String body = "{\"grade\":\"B\",\"pricePerKg\":900.00}";
        mvc.perform(as(clerk, post("/api/v1/prices")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(as(supervisor, post("/api/v1/prices")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(as(clerk, get("/api/v1/prices"))).andExpect(status().isOk());   // clerks may read prices
    }

    @Test
    void reportsAndAuditLogsAreForSupervisorsAndAdmins() throws Exception {
        for (String url : new String[]{"/api/v1/reports?type=DAILY_INTAKE", "/api/v1/reports/export?type=PAYMENT", "/api/v1/audit-logs"}) {
            mvc.perform(as(clerk, get(url))).andExpect(status().isForbidden());
            mvc.perform(as(supervisor, get(url))).andExpect(status().isOk());
            mvc.perform(as(admin, get(url))).andExpect(status().isOk());
        }
    }

    @Test
    void userManagementIsAdminOnly() throws Exception {
        mvc.perform(as(clerk, get("/api/v1/users"))).andExpect(status().isForbidden());
        mvc.perform(as(supervisor, get("/api/v1/users"))).andExpect(status().isForbidden());
        mvc.perform(as(admin, get("/api/v1/users"))).andExpect(status().isOk());

        String create = "{\"username\":\"new.clerk\",\"fullName\":\"New Clerk\",\"password\":\"Another-Str0ng-Pass\",\"jobRoleId\":" + systemRole(Role.CLERK).getId() + "}";
        mvc.perform(as(supervisor, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isForbidden());
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated());
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCannotLockThemselvesOut() throws Exception {
        long id = adminUser.getId();
        mvc.perform(as(admin, patch("/api/v1/users/" + id + "/active")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SELF_MODIFICATION"));
        mvc.perform(as(admin, put("/api/v1/users/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Me\",\"jobRoleId\":" + systemRole(Role.CLERK).getId() + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminCanChangeRolesDeactivateAndResetPasswords() throws Exception {
        User target = user(Role.CLERK);
        String targetToken = tokenFor(target);
        mvc.perform(as(admin, put("/api/v1/users/" + target.getId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Promoted\",\"jobRoleId\":" + systemRole(Role.SUPERVISOR).getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SUPERVISOR"));
        // role comes from the database on each request, so the old token is upgraded immediately
        mvc.perform(as(targetToken, get("/api/v1/reports?type=DAILY_INTAKE"))).andExpect(status().isOk());

        mvc.perform(as(admin, post("/api/v1/users/" + target.getId() + "/reset-password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"Brand-New-Passw0rd\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(admin, patch("/api/v1/users/" + target.getId() + "/active")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(as(targetToken, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void weakPasswordsAreRejected() throws Exception {
        mvc.perform(as(admin, post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"weak.user\",\"fullName\":\"Weak\",\"password\":\"short\",\"jobRoleId\":" + systemRole(Role.CLERK).getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditLogsAreReadOnly() throws Exception {
        mvc.perform(as(admin, post("/api/v1/audit-logs"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(as(admin, put("/api/v1/audit-logs/1"))).andExpect(status().isNotFound());
        mvc.perform(as(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/audit-logs/1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void farmersAreVisibleToEveryRole() throws Exception {
        farmer();
        for (String token : new String[]{clerk, supervisor, admin}) {
            mvc.perform(as(token, get("/api/v1/farmers"))).andExpect(status().isOk());
        }
    }
}

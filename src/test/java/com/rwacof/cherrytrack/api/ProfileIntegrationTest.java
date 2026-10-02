package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A user can maintain their own profile and password at any time, and nothing beyond that. */
class ProfileIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "Brand-New-Passw0rd!";

    private User me;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        me = user(Role.SUPERVISOR);
        token = tokenFor(me);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    void myProfileShowsMyRoleAndStations() throws Exception {
        mvc.perform(as(token, get("/api/v1/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(me.getUsername()))
                .andExpect(jsonPath("$.role").value("SUPERVISOR"))
                .andExpect(jsonPath("$.jobRole.name").value("Supervisor"))
                .andExpect(jsonPath("$.stations[0].code").value("TST"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void iCanUpdateMyNameEmailAndPhone() throws Exception {
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice Mukamurenzi\",\"email\":\"Alice@Example.com\",\"phone\":\"0788123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Mukamurenzi"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.phone").value("0788123456"));
        mvc.perform(as(token, get("/api/v1/auth/me"))).andExpect(jsonPath("$.fullName").value("Alice Mukamurenzi"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'PROFILE_UPDATED' AND user_id = ?", Integer.class, me.getId())).isEqualTo(1);
    }

    @Test
    void emailAndPhoneCanBeCleared() throws Exception {
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice\",\"email\":\"a@example.com\",\"phone\":\"0788123456\"}")).andExpect(status().isOk());
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice\",\"email\":\"\",\"phone\":\"\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void profileValidationAndUniqueEmail() throws Exception {
        User other = user(Role.CLERK);
        jdbc.update("UPDATE users SET email = 'taken@example.com' WHERE id = ?", other.getId());
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice\",\"email\":\"taken@example.com\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice\",\"phone\":\"12\"}")).andExpect(status().isBadRequest());
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice\",\"email\":\"nope\"}")).andExpect(status().isBadRequest());
        // keeping my own email is not a conflict
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice\",\"email\":\"mine@example.com\"}")).andExpect(status().isOk());
        mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alice 2\",\"email\":\"mine@example.com\"}")).andExpect(status().isOk());
    }

    @Test
    void iCannotPromoteMyselfOrChangeMyStationsThroughMyProfile() throws Exception {
        for (String extra : new String[]{"\"role\":\"ADMIN\"", "\"jobRoleId\":1", "\"active\":false", "\"stationIds\":[1]", "\"username\":\"root\""}) {
            mvc.perform(as(token, put("/api/v1/auth/me")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fullName\":\"Alice\"," + extra + "}")).andExpect(status().isBadRequest());
        }
        mvc.perform(as(token, get("/api/v1/users"))).andExpect(status().isForbidden());
        mvc.perform(as(token, get("/api/v1/auth/me"))).andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }

    @Test
    void changingMyPasswordNeedsTheCurrentOne() throws Exception {
        // a wrong current password is a 422, never a 401: a 401 would make the app sign me out
        mvc.perform(as(token, post("/api/v1/auth/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-my-password\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        mvc.perform(as(token, post("/api/v1/auth/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));
        mvc.perform(as(token, post("/api/v1/auth/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
        // and nothing changed
        assertThat(login(me.getUsername(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void changingMyPasswordSignsOutOtherDevicesButKeepsThisOneSignedIn() throws Exception {
        String otherDevice = login(me.getUsername(), PASSWORD).getResponse().getCookie("ct_refresh").getValue();

        MvcResult changed = mvc.perform(as(token, post("/api/v1/auth/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String thisDevice = changed.getResponse().getCookie("ct_refresh").getValue();
        assertThat(body(changed).get("accessToken").asText()).isNotBlank();

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ct_refresh", otherDevice))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ct_refresh", thisDevice))).andExpect(status().isOk());
        assertThat(login(me.getUsername(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(me.getUsername(), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_CHANGED' AND user_id = ?", Integer.class, me.getId())).isEqualTo(1);
    }

    @Test
    void anonymousVisitorsCannotReachTheProfile() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"a\",\"newPassword\":\"bbbbbbbbbbbb\"}")).andExpect(status().isUnauthorized());
    }
}

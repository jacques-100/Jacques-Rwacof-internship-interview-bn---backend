package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends AbstractIntegrationTest {

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    void loginIssuesAccessTokenAndHttpOnlyStrictRefreshCookie() throws Exception {
        User u = user(Role.CLERK);
        MvcResult res = login(u.getUsername(), PASSWORD);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(res).get("accessToken").asText()).isNotBlank();
        assertThat(body(res).get("user").get("role").asText()).isEqualTo("CLERK");
        assertThat(body(res).get("user").has("passwordHash")).isFalse();
        String cookie = res.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("ct_refresh=", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericError() throws Exception {
        User u = user(Role.CLERK);
        MvcResult wrong = login(u.getUsername(), "nope-nope-nope");
        MvcResult unknown = login("ghost-user", "nope-nope-nope");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrong).get("message").asText()).isEqualTo(body(unknown).get("message").asText());
    }

    @Test
    void deactivatedUsersCannotLogInAndExistingTokensStopWorking() throws Exception {
        User u = user(Role.CLERK);
        String token = tokenFor(u);
        mvc.perform(as(token, get("/api/v1/auth/me"))).andExpect(status().isOk());
        u.setActive(false);
        userRepository.save(u);
        mvc.perform(as(token, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
        assertThat(login(u.getUsername(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void tooManyFailedAttemptsAreRateLimited() throws Exception {
        User u = user(Role.CLERK);
        for (int i = 0; i < 5; i++) {
            assertThat(login(u.getUsername(), "bad-password-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult blocked = login(u.getUsername(), PASSWORD);   // even the right password is refused during lockout
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(blocked).get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void refreshRotatesTheTokenAndReuseRevokesEverything() throws Exception {
        User u = user(Role.SUPERVISOR);
        MvcResult first = login(u.getUsername(), PASSWORD);
        String firstToken = first.getResponse().getCookie("ct_refresh").getValue();

        MvcResult second = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ct_refresh", firstToken))).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String secondToken = second.getResponse().getCookie("ct_refresh").getValue();
        assertThat(secondToken).isNotEqualTo(firstToken);

        // replaying the already-rotated token is treated as theft: the whole family dies
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ct_refresh", firstToken))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ct_refresh", secondToken))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        User u = user(Role.CLERK);
        String token = login(u.getUsername(), PASSWORD).getResponse().getCookie("ct_refresh").getValue();
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("ct_refresh", token))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("ct_refresh", token))).andExpect(status().isUnauthorized());
    }

    @Test
    void webLoginNeverExposesTheRefreshTokenInTheBody() throws Exception {
        User u = user(Role.CLERK);
        assertThat(body(login(u.getUsername(), PASSWORD)).has("refreshToken")).isFalse();
    }

    @Test
    void nativeClientsGetTheRefreshTokenInTheBodyAndRotateItWithAHeader() throws Exception {
        User u = user(Role.CLERK);
        MvcResult first = mvc.perform(post("/api/v1/auth/login").header("X-Client", "native").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + u.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(first.getResponse().getHeader("Set-Cookie")).isNull();
        String firstToken = body(first).get("refreshToken").asText();
        assertThat(firstToken).isNotBlank();

        MvcResult second = mvc.perform(post("/api/v1/auth/refresh").header("X-Client", "native").header("X-Refresh-Token", firstToken)).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String secondToken = body(second).get("refreshToken").asText();
        assertThat(secondToken).isNotEqualTo(firstToken);

        // a native client can't be refreshed with a cookie, and logout revokes the header token
        mvc.perform(post("/api/v1/auth/refresh").header("X-Client", "native")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("X-Client", "native").header("X-Refresh-Token", secondToken)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").header("X-Client", "native").header("X-Refresh-Token", secondToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointsRejectMissingMalformedAndTamperedTokens() throws Exception {
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        String token = tokenFor(user(Role.CLERK));
        mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + token.substring(0, token.length() - 3) + "abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void errorsUseProblemJsonWithACorrelationId() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/dashboard").header("X-Correlation-Id", "trace-123")).andReturn();
        assertThat(res.getResponse().getContentType()).contains("application/problem+json");
        assertThat(res.getResponse().getHeader("X-Correlation-Id")).isEqualTo("trace-123");
        assertThat(body(res).get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void healthIsPublicButOtherActuatorEndpointsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(as(tokenFor(user(Role.CLERK)), get("/actuator/metrics"))).andExpect(status().isForbidden());
        mvc.perform(as(tokenFor(user(Role.ADMIN)), get("/actuator/metrics"))).andExpect(status().isOk());
    }
}

package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The company logo and profile photos: stored in the database, validated by content, permission-guarded. */
class ImageIntegrationTest extends AbstractIntegrationTest {

    /** A real 1x1 PNG. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    private String admin;
    private String clerk;
    private User clerkUser;

    @BeforeEach
    void setUp() throws Exception {
        admin = tokenFor(user(Role.ADMIN));
        clerkUser = user(Role.CLERK);
        clerk = tokenFor(clerkUser);
    }

    private MockMultipartHttpServletRequestBuilder upload(String path, String token, byte[] bytes, String name, String type) {
        MockMultipartHttpServletRequestBuilder b = MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, path);
        b.file(new MockMultipartFile("file", name, type, bytes));
        return (MockMultipartHttpServletRequestBuilder) as(token, b);
    }

    // ------------------------------------------------------------------ company logo

    @Test
    void anyoneCanSeeBrandingBeforeSigningIn() throws Exception {
        mvc.perform(get("/api/v1/branding")).andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("CherryTrack"))
                .andExpect(jsonPath("$.logoVersion").doesNotExist());
        mvc.perform(get("/api/v1/branding/logo")).andExpect(status().isNotFound());
    }

    @Test
    void anAdministratorUploadsReplacesAndRemovesTheLogo() throws Exception {
        mvc.perform(upload("/api/v1/branding/logo", admin, PNG, "logo.png", "image/png")).andExpect(status().isOk())
                .andExpect(jsonPath("$.logoVersion").isNumber());

        // served without signing in, with the real type and bytes
        byte[] served = mvc.perform(get("/api/v1/branding/logo")).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(header().exists("ETag"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PNG);

        mvc.perform(upload("/api/v1/branding/logo", admin, JPEG, "logo.jpg", "image/jpeg")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/branding/logo")).andExpect(content().contentType("image/jpeg"));

        mvc.perform(as(admin, delete("/api/v1/branding/logo"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.logoVersion").doesNotExist());
        mvc.perform(get("/api/v1/branding/logo")).andExpect(status().isNotFound());

        // the changes are in the audit trail
        mvc.perform(as(admin, get("/api/v1/audit-logs?action=LOGO_UPDATED"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void onlyThoseWithTheSettingsPermissionCanChangeTheLogo() throws Exception {
        mvc.perform(upload("/api/v1/branding/logo", clerk, PNG, "logo.png", "image/png")).andExpect(status().isForbidden());
        mvc.perform(as(clerk, delete("/api/v1/branding/logo"))).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/branding/logo")
                .file(new MockMultipartFile("file", "logo.png", "image/png", PNG))).andExpect(status().isUnauthorized());
    }

    @Test
    void imagesAreCheckedByTheirContentNotTheirNameOrClaimedType() throws Exception {
        byte[] script = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes();
        mvc.perform(upload("/api/v1/branding/logo", admin, script, "logo.png", "image/png")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        mvc.perform(upload("/api/v1/branding/logo", admin, "plain text".getBytes(), "logo.jpg", "image/jpeg")).andExpect(status().isUnprocessableEntity());
        mvc.perform(upload("/api/v1/branding/logo", admin, new byte[0], "logo.png", "image/png")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/branding/logo")).andExpect(status().isNotFound());
    }

    @Test
    void oversizedFilesAreRefused() throws Exception {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mvc.perform(upload("/api/v1/branding/logo", admin, big, "logo.png", "image/png")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    // ------------------------------------------------------------------ profile photos

    @Test
    void aUserManagesTheirOwnPhotoAndColleaguesCanSeeIt() throws Exception {
        mvc.perform(as(clerk, get("/api/v1/auth/me"))).andExpect(jsonPath("$.avatarVersion").doesNotExist());

        mvc.perform(upload("/api/v1/auth/me/avatar", clerk, JPEG, "me.jpg", "image/jpeg")).andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarVersion").isNumber());
        mvc.perform(as(clerk, get("/api/v1/auth/me"))).andExpect(jsonPath("$.avatarVersion").isNumber());

        // another signed-in user can fetch it; nobody can without signing in
        byte[] served = mvc.perform(as(admin, get("/api/v1/users/" + clerkUser.getId() + "/avatar"))).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg")).andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(JPEG);
        mvc.perform(get("/api/v1/users/" + clerkUser.getId() + "/avatar")).andExpect(status().isUnauthorized());

        mvc.perform(as(clerk, delete("/api/v1/auth/me/avatar"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarVersion").doesNotExist());
        mvc.perform(as(admin, get("/api/v1/users/" + clerkUser.getId() + "/avatar"))).andExpect(status().isNotFound());
    }

    @Test
    void uploadingAPhotoNeverTouchesAnotherUsersPhoto() throws Exception {
        User other = user(Role.SUPERVISOR);
        String otherToken = tokenFor(other);
        mvc.perform(upload("/api/v1/auth/me/avatar", otherToken, PNG, "a.png", "image/png")).andExpect(status().isOk());
        mvc.perform(upload("/api/v1/auth/me/avatar", clerk, JPEG, "c.jpg", "image/jpeg")).andExpect(status().isOk());
        mvc.perform(as(admin, get("/api/v1/users/" + other.getId() + "/avatar"))).andExpect(content().contentType("image/png"));
        mvc.perform(as(admin, get("/api/v1/users/" + clerkUser.getId() + "/avatar"))).andExpect(content().contentType("image/jpeg"));
        mvc.perform(as(clerk, delete("/api/v1/auth/me/avatar"))).andExpect(status().isOk());
        mvc.perform(as(admin, get("/api/v1/users/" + other.getId() + "/avatar"))).andExpect(status().isOk());   // theirs is untouched
    }

    @Test
    void photosAreSmallerThanLogos() throws Exception {
        byte[] big = new byte[1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mvc.perform(upload("/api/v1/auth/me/avatar", clerk, big, "me.png", "image/png")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }
}

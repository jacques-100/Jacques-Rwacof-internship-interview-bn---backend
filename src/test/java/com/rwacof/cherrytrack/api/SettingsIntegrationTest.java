package com.rwacof.cherrytrack.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** System settings live in the database: validated, atomic, audited, and really used by the application. */
class SettingsIntegrationTest extends AbstractIntegrationTest {

    private String admin;
    private String clerk;
    private String supervisor;

    @BeforeEach
    void setUp() throws Exception {
        standardPrices();
        admin = tokenFor(user(Role.ADMIN));
        clerk = tokenFor(user(Role.CLERK));
        supervisor = tokenFor(user(Role.SUPERVISOR));
    }

    private ResultActions update(String token, String valuesJson) throws Exception {
        return mvc.perform(as(token, put("/api/v1/system-settings")).contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + valuesJson + "}"));
    }

    @Test
    void everyoneSignedInCanReadTheSettingsWithTheirDefinitions() throws Exception {
        mvc.perform(as(clerk, get("/api/v1/system-settings"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.values['currency.code']").value("RWF"))
                .andExpect(jsonPath("$.values['organization.name']").value("CherryTrack"))
                .andExpect(jsonPath("$.values['station.default-daily-capacity-kg']").value("5000"))
                .andExpect(jsonPath("$.values['delivery.rejection-reasons']").value(containsString("Unripe cherries")))
                .andExpect(jsonPath("$.definitions.length()").value(8))
                .andExpect(jsonPath("$.definitions[0].label").isNotEmpty());
        mvc.perform(get("/api/v1/system-settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyThoseWithThePermissionCanChangeThem() throws Exception {
        update(clerk, "{\"currency.code\":\"USD\"}").andExpect(status().isForbidden());
        update(supervisor, "{\"currency.code\":\"USD\"}").andExpect(status().isForbidden());
        update(admin, "{\"currency.code\":\"USD\"}").andExpect(status().isOk());
    }

    @Test
    void valuesAreNormalisedAndPersisted() throws Exception {
        update(admin, "{\"currency.code\":\" usd \",\"organization.name\":\"  Kigali Coffee Co  \",\"station.default-daily-capacity-kg\":\"6000.50\","
                + "\"delivery.rejection-reasons\":\"  Unripe  \\n\\n Wet \\nUnripe\\n\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values['currency.code']").value("USD"))
                .andExpect(jsonPath("$.values['organization.name']").value("Kigali Coffee Co"))
                .andExpect(jsonPath("$.values['station.default-daily-capacity-kg']").value("6000.5"))
                .andExpect(jsonPath("$.values['delivery.rejection-reasons']").value("Unripe\nWet"));
        mvc.perform(as(clerk, get("/api/v1/system-settings"))).andExpect(jsonPath("$.values['currency.code']").value("USD"));
    }

    @Test
    void invalidValuesAreRefusedAndNothingIsChangedWhenOneIsBad() throws Exception {
        update(admin, "{\"organization.name\":\"New Name\",\"currency.code\":\"RWANDAN\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_SETTING"));
        mvc.perform(as(clerk, get("/api/v1/system-settings"))).andExpect(jsonPath("$.values['organization.name']").value("CherryTrack"));

        for (String bad : new String[]{"{\"currency.code\":\"12\"}", "{\"station.default-timezone\":\"Mars/Olympus\"}",
                "{\"station.default-daily-capacity-kg\":\"-5\"}", "{\"station.default-max-delivery-kg\":\"abc\"}",
                "{\"station.default-low-threshold-kg\":\"1.234\"}", "{\"reports.max-range-days\":\"0\"}", "{\"reports.max-range-days\":\"99999\"}",
                "{\"organization.name\":\"\"}"}) {
            update(admin, bad).andExpect(status().isUnprocessableEntity());
        }
        update(admin, "{\"no.such.setting\":\"x\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_SETTING"));
    }

    @Test
    void changesAreAuditedAndUnchangedValuesAreNot() throws Exception {
        update(admin, "{\"currency.code\":\"USD\",\"organization.name\":\"CherryTrack\"}").andExpect(status().isOk());   // name unchanged
        update(admin, "{\"currency.code\":\"USD\"}").andExpect(status().isOk());                                          // nothing changed

        JsonNode logs = body(mvc.perform(as(admin, get("/api/v1/audit-logs").param("action", "SETTINGS_UPDATED"))).andReturn());
        assertThat(logs.get("totalElements").asInt()).isEqualTo(1);
        JsonNode details = logs.get("content").get(0).get("details");
        assertThat(details.has("currency.code")).isTrue();
        assertThat(details.has("organization.name")).isFalse();
        assertThat(details.get("currency.code").get("from").asText()).isEqualTo("RWF");
        assertThat(details.get("currency.code").get("to").asText()).isEqualTo("USD");
    }

    // ------------------------------------------------------------------ the application really uses them

    @Test
    void theCurrencyAppearsInReportsAndAuditText() throws Exception {
        Farmer farmer = farmer();
        long id = body(mvc.perform(as(clerk, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + farmer.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":100}")).andReturn()).get("id").asLong();
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE")))
                .andExpect(jsonPath("$.columns[?(@.key=='amount_owed')].label").value("Amount owed (RWF)"));

        update(admin, "{\"currency.code\":\"USD\"}").andExpect(status().isOk());
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE")))
                .andExpect(jsonPath("$.columns[?(@.key=='amount_owed')].label").value("Amount owed (USD)"));

        mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}")).andExpect(status().isOk());
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id + "/audit")))
                .andExpect(jsonPath("$[1].description").value(containsString("USD/kg")));
    }

    @Test
    void theReportRangeLimitIsASetting() throws Exception {
        String from = today().minusDays(30).toString();
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE").param("from", from).param("to", today().toString())))
                .andExpect(status().isOk());
        update(admin, "{\"reports.max-range-days\":\"10\"}").andExpect(status().isOk());
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE").param("from", from).param("to", today().toString())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(containsString("10 days")));
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE").param("from", today().minusDays(10).toString()).param("to", today().toString())))
                .andExpect(status().isOk());
    }
}

package com.rwacof.cherrytrack.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.Station;
import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stations: who can work where, per-station capacity, and managing stations themselves. */
class StationIntegrationTest extends AbstractIntegrationTest {

    private Station other;
    private User clerkA;
    private User supervisorA;
    private User admin;
    private String clerk;
    private String supervisor;
    private String adminToken;
    private Farmer farmer;

    @BeforeEach
    void setUp() throws Exception {
        standardPrices();
        other = station("OTH", "Other Station", "3000", "300");
        clerkA = user(Role.CLERK, station);
        supervisorA = user(Role.SUPERVISOR, station);
        admin = user(Role.ADMIN);
        clerk = tokenFor(clerkA);
        supervisor = tokenFor(supervisorA);
        adminToken = tokenFor(admin);
        farmer = farmer();
    }

    private ResultActions deliver(String token, Station in, String weight) throws Exception {
        return mvc.perform(as(token, in, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + farmer.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":" + weight + "}"));
    }

    private long deliveryIn(Station in, String weight) throws Exception {
        return body(deliver(adminToken, in, weight).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    // ------------------------------------------------------------------ access scoping

    @Test
    void aUserCanOnlyWorkInTheirAssignedStations() throws Exception {
        mvc.perform(as(clerk, other, get("/api/v1/deliveries"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STATION_FORBIDDEN"));
        mvc.perform(as(clerk, other, get("/api/v1/dashboard"))).andExpect(status().isForbidden());
        mvc.perform(as(clerk, other, get("/api/v1/capacity"))).andExpect(status().isForbidden());
        mvc.perform(as(clerk, station, get("/api/v1/deliveries"))).andExpect(status().isOk());
        deliver(clerk, other, "50").andExpect(status().isForbidden());
    }

    @Test
    void administratorsCanWorkInEveryStation() throws Exception {
        mvc.perform(as(adminToken, station, get("/api/v1/dashboard"))).andExpect(status().isOk());
        mvc.perform(as(adminToken, other, get("/api/v1/dashboard"))).andExpect(status().isOk());
    }

    @Test
    void withoutAHeaderTheUsersOwnStationIsUsed() throws Exception {
        mvc.perform(as(clerk, null, get("/api/v1/settings"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.stationCode").value("TST"));
    }

    @Test
    void aUserWithNoStationIsToldSoInsteadOfSeeingNothing() throws Exception {
        User nobody = user(Role.CLERK, new Station[0]);
        mvc.perform(as(tokenFor(nobody), null, get("/api/v1/dashboard"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ASSIGNED"));
    }

    @Test
    void anAdministratorWithNoStationsRegisteredGetsAClearSignal() throws Exception {
        jdbc.update("DELETE FROM user_stations");
        jdbc.update("DELETE FROM stations");
        mvc.perform(as(adminToken, null, get("/api/v1/dashboard"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_STATION"));
    }

    @Test
    void aDeliveryOfAnotherStationCannotBeReadOrChangedByID() throws Exception {
        long id = deliveryIn(other, "100");
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id))).andExpect(status().isForbidden());
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id + "/audit"))).andExpect(status().isForbidden());
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"grade\":\"A\"}")).andExpect(status().isForbidden());
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/reject")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(as(supervisor, post("/api/v1/deliveries/" + id + "/pay"))).andExpect(status().isForbidden());
        mvc.perform(as(clerk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/deliveries/" + id + "/weight"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"newWeightKg\":10,\"reason\":\"x\"}")).andExpect(status().isForbidden());
        // ...and the delivery is untouched
        mvc.perform(as(adminToken, other, get("/api/v1/deliveries/" + id))).andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void deliveryListsNeverMixStations() throws Exception {
        deliveryIn(station, "10");
        deliveryIn(other, "20");
        deliveryIn(other, "30");
        mvc.perform(as(adminToken, station, get("/api/v1/deliveries"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(adminToken, other, get("/api/v1/deliveries"))).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void referencesCarryTheStationCode() throws Exception {
        long id = deliveryIn(other, "10");
        mvc.perform(as(adminToken, other, get("/api/v1/deliveries/" + id)))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("DLV-OTH-\\d{8}-00001")))
                .andExpect(jsonPath("$.stationCode").value("OTH"));
    }

    @Test
    void auditLogsAreScopedToTheStationsYouManage() throws Exception {
        deliveryIn(station, "10");
        deliveryIn(other, "20");
        mvc.perform(as(supervisor, get("/api/v1/audit-logs").param("entityType", "DELIVERY")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(adminToken, get("/api/v1/audit-logs").param("entityType", "DELIVERY")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(supervisor, get("/api/v1/audit-logs").param("stationId", String.valueOf(other.getId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsAreScopedToTheStation() throws Exception {
        deliveryIn(station, "10");
        deliveryIn(other, "70");
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE")))
                .andExpect(jsonPath("$.rows[0].accepted_kg").value(10.0));
        mvc.perform(as(adminToken, other, get("/api/v1/reports").param("type", "DAILY_INTAKE")))
                .andExpect(jsonPath("$.rows[0].accepted_kg").value(70.0));
    }

    // ------------------------------------------------------------------ capacity per station

    @Test
    void capacityIsIndependentPerStation() throws Exception {
        for (int i = 0; i < 10; i++) {
            deliver(adminToken, station, "500").andExpect(status().isCreated());
        }
        deliver(adminToken, station, "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
        deliver(adminToken, other, "250").andExpect(status().isCreated());   // the other station is untouched
        mvc.perform(as(adminToken, other, get("/api/v1/capacity"))).andExpect(jsonPath("$.dailyLimitKg").value(3000.0))
                .andExpect(jsonPath("$.acceptedKg").value(250.0));
    }

    @Test
    void eachStationHasItsOwnLargestDelivery() throws Exception {
        deliver(adminToken, other, "301").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_WEIGHT"));
        deliver(adminToken, other, "300").andExpect(status().isCreated());
        deliver(adminToken, station, "500").andExpect(status().isCreated());
    }

    @Test
    void aSupervisorCanRaiseTodaysLimitWithAReasonAndTheHeadroomIsUsable() throws Exception {
        for (int i = 0; i < 10; i++) {
            deliver(supervisor, station, "500").andExpect(status().isCreated());
        }
        deliver(supervisor, station, "100").andExpect(status().isConflict());

        mvc.perform(as(supervisor, put("/api/v1/capacity/limit").param("date", today().toString())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitKg\":6000,\"reason\":\"Extra drying beds available today\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimitKg").value(6000.0))
                .andExpect(jsonPath("$.remainingKg").value(1000.0));
        deliver(supervisor, station, "100").andExpect(status().isCreated());

        // only that day changed: tomorrow's default and yesterday are untouched
        mvc.perform(as(supervisor, get("/api/v1/capacity").param("date", today().minusDays(1).toString())))
                .andExpect(jsonPath("$.dailyLimitKg").value(5000.0));

        JsonNode audit = body(mvc.perform(as(supervisor, get("/api/v1/audit-logs").param("entityType", "STATION"))).andReturn());
        assertThat(audit.get("content").toString()).contains("CAPACITY_ADJUSTED").contains("Extra drying beds");
    }

    @Test
    void limitAdjustmentIsRestricted() throws Exception {
        String body = "{\"limitKg\":6000,\"reason\":\"x\"}";
        mvc.perform(as(clerk, put("/api/v1/capacity/limit").param("date", today().toString())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // a supervisor cannot adjust a station they do not manage
        mvc.perform(as(supervisor, other, put("/api/v1/capacity/limit").param("date", today().toString())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // a reason is required, the limit cannot be below what's accepted, and the future is off limits
        mvc.perform(as(supervisor, put("/api/v1/capacity/limit").param("date", today().toString())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limitKg\":6000,\"reason\":\"\"}")).andExpect(status().isBadRequest());
        deliver(supervisor, station, "400").andExpect(status().isCreated());
        mvc.perform(as(supervisor, put("/api/v1/capacity/limit").param("date", today().toString())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limitKg\":300,\"reason\":\"too low\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMIT_BELOW_ACCEPTED"));
        mvc.perform(as(supervisor, put("/api/v1/capacity/limit").param("date", today().plusDays(1).toString())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limitKg\":6000,\"reason\":\"future\"}")).andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ managing stations

    private String stationJson(String code, String name, String daily, String max, String threshold, String zone) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"location\":\"Somewhere\",\"timezone\":\"" + zone
                + "\",\"dailyCapacityKg\":" + daily + ",\"maxDeliveryKg\":" + max + ",\"lowThresholdKg\":" + threshold + "}";
    }

    @Test
    void anAdministratorRegistersAndEditsStations() throws Exception {
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("gkm", "Gikomero Station", "4000", "450", "400", "Africa/Kigali")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("GKM"))        // normalised to capitals
                .andExpect(jsonPath("$.dailyCapacityKg").value(4000.0))
                .andExpect(jsonPath("$.active").value(true));

        long id = jdbc.queryForObject("SELECT id FROM stations WHERE code = 'GKM'", Long.class);
        mvc.perform(as(adminToken, put("/api/v1/stations/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("GKM", "Gikomero Station", "4500", "450", "400", "Africa/Kigali")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dailyCapacityKg").value(4500.0));
        mvc.perform(as(adminToken, put("/api/v1/stations/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("NEW", "Gikomero Station", "4500", "450", "400", "Africa/Kigali")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STATION_CODE_FIXED"));
    }

    @Test
    void stationValidation() throws Exception {
        String dupCode = stationJson("TST", "Brand New", "4000", "450", "400", "Africa/Kigali");
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON).content(dupCode))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_STATION_CODE"));
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("NEW", "test station", "4000", "450", "400", "Africa/Kigali")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_STATION_NAME"));
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("NEW", "New", "4000", "450", "400", "Mars/Olympus")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("NEW", "New", "400", "450", "100", "Africa/Kigali")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_CAPACITY"));
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("N", "New", "4000", "450", "400", "Africa/Kigali")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(adminToken, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content(stationJson("NEW", "New", "0", "450", "400", "Africa/Kigali")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdministratorsManageStations() throws Exception {
        String body = stationJson("NEW", "New", "4000", "450", "400", "Africa/Kigali");
        mvc.perform(as(clerk, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(as(supervisor, post("/api/v1/stations")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(as(supervisor, put("/api/v1/stations/" + station.getId()).contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isForbidden());
        mvc.perform(as(supervisor, get("/api/v1/stations/" + station.getId()))).andExpect(status().isForbidden());
    }

    @Test
    void theStationListShowsOnlyWhatTheUserCanWorkIn() throws Exception {
        mvc.perform(as(clerk, get("/api/v1/stations"))).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].code").value("TST"));
        mvc.perform(as(adminToken, get("/api/v1/stations"))).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void inactiveStationsCannotBeWorkedInAndAdminsCanStillListThem() throws Exception {
        mvc.perform(as(adminToken, put("/api/v1/stations/" + other.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(stationJson("OTH", "Other Station", "3000", "300", "500", "Africa/Kigali").replace("}", ",\"active\":false}"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(as(adminToken, other, get("/api/v1/dashboard"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATION_INACTIVE"));
        mvc.perform(as(adminToken, get("/api/v1/stations"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(as(adminToken, get("/api/v1/stations").param("includeInactive", "true"))).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void assigningUsersGivesThemAccessAndUnassigningTakesItAway() throws Exception {
        mvc.perform(as(clerk, other, get("/api/v1/deliveries"))).andExpect(status().isForbidden());

        mvc.perform(as(adminToken, put("/api/v1/stations/" + other.getId() + "/users")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + clerkA.getId() + "," + supervisorA.getId() + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.users", hasSize(2)));
        mvc.perform(as(clerk, other, get("/api/v1/deliveries"))).andExpect(status().isOk());
        mvc.perform(as(clerk, get("/api/v1/stations"))).andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(as(adminToken, put("/api/v1/stations/" + other.getId() + "/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[" + supervisorA.getId() + "]}")).andExpect(status().isOk()).andExpect(jsonPath("$.users", hasSize(1)));
        mvc.perform(as(clerk, other, get("/api/v1/deliveries"))).andExpect(status().isForbidden());
        mvc.perform(as(adminToken, put("/api/v1/stations/" + other.getId() + "/users")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[999999]}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void userStationAssignmentFromTheUserSide() throws Exception {
        mvc.perform(as(adminToken, put("/api/v1/users/" + clerkA.getId() + "/stations")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationIds\":[" + other.getId() + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stations[0].code").value("OTH"));
        mvc.perform(as(clerk, get("/api/v1/deliveries"))).andExpect(status().isForbidden());   // no longer at TST
        mvc.perform(as(clerk, other, get("/api/v1/deliveries"))).andExpect(status().isOk());
    }
}

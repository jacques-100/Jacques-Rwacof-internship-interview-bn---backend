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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryLifecycleIntegrationTest extends AbstractIntegrationTest {

    private String clerk;
    private String supervisor;
    private Farmer farmer;

    @BeforeEach
    void setUp() throws Exception {
        standardPrices();
        clerk = tokenFor(user(Role.CLERK));
        supervisor = tokenFor(user(Role.SUPERVISOR));
        farmer = farmer();
    }

    private ResultActions createRaw(String token, Object farmerId, String date, String weight) throws Exception {
        return mvc.perform(as(token, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + farmerId + ",\"deliveryDate\":\"" + date + "\",\"weightKg\":" + weight + "}"));
    }

    private JsonNode create(String weight) throws Exception {
        return body(createRaw(clerk, farmer.getId(), today().toString(), weight).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions act(String token, long id, String action, String content) throws Exception {
        return mvc.perform(as(token, post("/api/v1/deliveries/" + id + "/" + action))
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    @Test
    void createsDeliveryWithReceivedStatusAndReference() throws Exception {
        JsonNode d = create("350");
        assertThat(d.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(d.get("reference").asText()).matches("DLV-TST-\\d{8}-00001");
        assertThat(d.get("grade")).isNull();
        assertThat(d.get("amountOwed")).isNull();
        assertThat(d.get("weightKg").decimalValue()).isEqualByComparingTo("350");
        assertThat(d.get("allowedActions").toString()).contains("CORRECT_WEIGHT", "GRADE", "REJECT");
        assertThat(create("10").get("reference").asText()).endsWith("-00002");
    }

    @Test
    void fullHappyPathComputesAmountOnTheServerAndWritesAuditTrail() throws Exception {
        long id = create("350").get("id").asLong();

        act(clerk, id, "grade", "{\"grade\":\"A\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRADED"))
                .andExpect(jsonPath("$.pricePerKg").value(1200.0))
                .andExpect(jsonPath("$.amountOwed").value(420000.0));

        act(supervisor, id, "pay", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.allowedActions", hasSize(0)));

        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id + "/audit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.contains(
                        "DELIVERY_CREATED", "DELIVERY_GRADED", "DELIVERY_PAID")))
                .andExpect(jsonPath("$[1].details.amountOwed").value(420000.0));
    }

    @Test
    void clientCannotSupplyAmountPriceOrStatus() throws Exception {
        long id = create("100").get("id").asLong();
        act(clerk, id, "grade", "{\"grade\":\"A\",\"amountOwed\":1}").andExpect(status().isBadRequest());
        act(clerk, id, "grade", "{\"grade\":\"A\",\"pricePerKg\":1}").andExpect(status().isBadRequest());
        mvc.perform(as(clerk, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"farmerId\":" + farmer.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":10,\"status\":\"PAID\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(clerk, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"farmerId\":" + farmer.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":10,\"amountOwed\":999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesWeightBounds() throws Exception {
        createRaw(clerk, farmer.getId(), today().toString(), "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("weightKg")));
        createRaw(clerk, farmer.getId(), today().toString(), "-1").andExpect(status().isBadRequest());
        // above this station's largest delivery (500 kg) is a business rule, checked by the service
        createRaw(clerk, farmer.getId(), today().toString(), "500.01").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WEIGHT"));
        createRaw(clerk, farmer.getId(), today().toString(), "10.123").andExpect(status().isBadRequest());
        createRaw(clerk, farmer.getId(), today().toString(), "500").andExpect(status().isCreated());
    }

    @Test
    void rejectsFutureDates() throws Exception {
        createRaw(clerk, farmer.getId(), today().plusDays(1).toString(), "10")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_DATE_IN_FUTURE"));
    }

    @Test
    void unknownAndInactiveFarmersAreRejected() throws Exception {
        createRaw(clerk, 999999, today().toString(), "10").andExpect(status().isNotFound());
        farmer.setActive(false);
        farmerRepository.save(farmer);
        createRaw(clerk, farmer.getId(), today().toString(), "10")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FARMER_INACTIVE"));
    }

    @Test
    void enforcesDailyCapacityAndRejectedWeightIsReleased() throws Exception {
        for (int i = 0; i < 9; i++) {
            createRaw(clerk, farmer.getId(), today().toString(), "500").andExpect(status().isCreated());
        }
        long id = create("500").get("id").asLong();   // 5,000 kg exactly
        createRaw(clerk, farmer.getId(), today().toString(), "0.01")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));

        mvc.perform(as(clerk, get("/api/v1/capacity")))
                .andExpect(jsonPath("$.acceptedKg").value(5000.0))
                .andExpect(jsonPath("$.remainingKg").value(0.0))
                .andExpect(jsonPath("$.alert").value("FULL"));

        act(clerk, id, "reject", "{\"reason\":\"Unripe cherries\"}").andExpect(status().isOk());
        mvc.perform(as(clerk, get("/api/v1/capacity")))
                .andExpect(jsonPath("$.acceptedKg").value(4500.0))
                .andExpect(jsonPath("$.rejectedKg").value(500.0));
        createRaw(clerk, farmer.getId(), today().toString(), "500").andExpect(status().isCreated());
    }

    @Test
    void capacityIsPerDay() throws Exception {
        for (int i = 0; i < 10; i++) {
            createRaw(clerk, farmer.getId(), today().toString(), "500").andExpect(status().isCreated());
        }
        createRaw(clerk, farmer.getId(), today().minusDays(1).toString(), "500").andExpect(status().isCreated());
    }

    @Test
    void correctsWeightWhileReceivedAndRespectsCapacity() throws Exception {
        for (int i = 0; i < 9; i++) {
            createRaw(clerk, farmer.getId(), today().toString(), "500");
        }
        createRaw(clerk, farmer.getId(), today().toString(), "100");
        long id = create("300").get("id").asLong();   // 4,900 accepted

        mvc.perform(as(clerk, patch("/api/v1/deliveries/" + id + "/weight")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWeightKg\":500,\"reason\":\"Scale re-check\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));   // 4,900 -> 5,100 would exceed

        mvc.perform(as(clerk, patch("/api/v1/deliveries/" + id + "/weight")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWeightKg\":350,\"reason\":\"Scale correction after verification\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(350.0));

        mvc.perform(as(clerk, get("/api/v1/capacity"))).andExpect(jsonPath("$.acceptedKg").value(4950.0));
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id + "/audit")))
                .andExpect(jsonPath("$[1].action").value("WEIGHT_CORRECTED"))
                .andExpect(jsonPath("$[1].description").value("Weight corrected 300 → 350 kg"))
                .andExpect(jsonPath("$[1].details.reason").value("Scale correction after verification"));
    }

    @Test
    void weightCorrectionRequiresAReason() throws Exception {
        long id = create("300").get("id").asLong();
        mvc.perform(as(clerk, patch("/api/v1/deliveries/" + id + "/weight")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWeightKg\":350}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weightCannotBeCorrectedAfterGrading() throws Exception {
        long id = create("300").get("id").asLong();
        act(clerk, id, "grade", "{\"grade\":\"B\"}").andExpect(status().isOk());
        mvc.perform(as(clerk, patch("/api/v1/deliveries/" + id + "/weight")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWeightKg\":310,\"reason\":\"late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void invalidTransitionsAreRefused() throws Exception {
        long id = create("100").get("id").asLong();
        act(supervisor, id, "pay", "").andExpect(status().isConflict());             // RECEIVED -> PAID
        act(clerk, id, "grade", "{\"grade\":\"A\"}").andExpect(status().isOk());
        act(clerk, id, "grade", "{\"grade\":\"B\"}").andExpect(status().isConflict()); // GRADED -> GRADED
        act(clerk, id, "reject", "{\"reason\":\"too late\"}").andExpect(status().isConflict()); // GRADED -> REJECTED
        act(supervisor, id, "pay", "").andExpect(status().isOk());
        act(supervisor, id, "pay", "").andExpect(status().isConflict());             // PAID is final
        act(clerk, id, "reject", "{\"reason\":\"x\"}").andExpect(status().isConflict());
    }

    @Test
    void rejectedDeliveryIsImmutableAndRequiresReason() throws Exception {
        long id = create("100").get("id").asLong();
        act(clerk, id, "reject", "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        act(clerk, id, "reject", "{\"reason\":\"Excess moisture\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Excess moisture"))
                .andExpect(jsonPath("$.allowedActions", hasSize(0)));
        act(clerk, id, "grade", "{\"grade\":\"A\"}").andExpect(status().isConflict());
        act(supervisor, id, "pay", "").andExpect(status().isConflict());
        act(clerk, id, "reject", "{\"reason\":\"again\"}").andExpect(status().isConflict());
        mvc.perform(as(clerk, patch("/api/v1/deliveries/" + id + "/weight")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWeightKg\":50,\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void priceChangesDoNotAffectAlreadyGradedDeliveries() throws Exception {
        long first = create("100").get("id").asLong();
        act(clerk, first, "grade", "{\"grade\":\"A\"}").andExpect(jsonPath("$.amountOwed").value(120000.0));

        mvc.perform(as(supervisor, post("/api/v1/prices")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"A\",\"pricePerKg\":1500.00}"))
                .andExpect(status().isCreated());

        long second = create("100").get("id").asLong();
        act(clerk, second, "grade", "{\"grade\":\"A\"}").andExpect(jsonPath("$.amountOwed").value(150000.0));

        mvc.perform(as(clerk, get("/api/v1/deliveries/" + first)))
                .andExpect(jsonPath("$.pricePerKg").value(1200.0))
                .andExpect(jsonPath("$.amountOwed").value(120000.0));

        mvc.perform(as(clerk, get("/api/v1/prices")))
                .andExpect(jsonPath("$.current[?(@.grade=='A')].pricePerKg").value(1500.0))
                .andExpect(jsonPath("$.history", hasSize(3)));
    }

    @Test
    void gradingFailsClearlyWhenNoPriceIsConfigured() throws Exception {
        jdbc.update("DELETE FROM grade_prices");
        long id = create("100").get("id").asLong();
        act(clerk, id, "grade", "{\"grade\":\"A\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRICE_NOT_CONFIGURED"));
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id))).andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void listsSearchesAndFilters() throws Exception {
        Farmer other = farmer();
        JsonNode a = create("100");
        createRaw(clerk, other.getId(), today().toString(), "200").andExpect(status().isCreated());
        act(clerk, a.get("id").asLong(), "grade", "{\"grade\":\"A\"}");

        mvc.perform(as(clerk, get("/api/v1/deliveries").param("date", today().toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("status", "GRADED")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("grade", "A")))
                .andExpect(jsonPath("$.content[0].reference").value(a.get("reference").asText()));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("farmerId", String.valueOf(other.getId()))))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("minWeight", "150")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("q", farmer.getCooperativeNumber().toLowerCase())))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("q", a.get("reference").asText())))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("q", "%")))   // wildcard must be literal
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("sortBy", "weightKg").param("dir", "asc")))
                .andExpect(jsonPath("$.content[0].weightKg").value(100.0));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("sortBy", "createdBy.passwordHash")))   // not whitelisted
                .andExpect(status().isOk());
    }

    @Test
    void unknownDeliveryIsNotFound() throws Exception {
        mvc.perform(as(clerk, get("/api/v1/deliveries/424242"))).andExpect(status().isNotFound());
        act(clerk, 424242, "grade", "{\"grade\":\"A\"}").andExpect(status().isNotFound());
    }

    @Test
    void deliveriesCannotBeDeletedThroughTheApi() throws Exception {
        long id = create("10").get("id").asLong();
        mvc.perform(as(clerk, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/deliveries/" + id)))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deliveries", Integer.class)).isEqualTo(1);
    }

    @Test
    void gradeIsRecordedForTheActingUser() throws Exception {
        User grader = user(Role.CLERK);
        long id = create("10").get("id").asLong();
        act(tokenFor(grader), id, "grade", "{\"grade\":\"B\"}")
                .andExpect(jsonPath("$.gradedBy").value(grader.getUsername()))
                .andExpect(jsonPath("$.amountOwed").value(8000.0));
    }
}

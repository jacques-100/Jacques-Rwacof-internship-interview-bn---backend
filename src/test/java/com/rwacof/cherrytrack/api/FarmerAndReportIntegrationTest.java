package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
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

class FarmerAndReportIntegrationTest extends AbstractIntegrationTest {

    private String clerk;
    private String supervisor;

    @BeforeEach
    void setUp() throws Exception {
        standardPrices();
        clerk = tokenFor(user(Role.CLERK));
        supervisor = tokenFor(user(Role.SUPERVISOR));
    }

    private static String farmerJson(String name, String phone, String coop) {
        return "{\"fullName\":\"" + name + "\",\"phone\":\"" + phone + "\",\"cooperativeNumber\":\"" + coop + "\"}";
    }

    @Test
    void createsFarmerNormalisesCooperativeNumberAndAudits() throws Exception {
        mvc.perform(as(clerk, post("/api/v1/farmers")).contentType(MediaType.APPLICATION_JSON)
                        .content(farmerJson("Jean Paul Habimana", "0788123456", "ndb-2001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cooperativeNumber").value("NDB-2001"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.totalDeliveries").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='FARMER_CREATED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void validatesPhoneAndDuplicates() throws Exception {
        mvc.perform(as(clerk, post("/api/v1/farmers")).contentType(MediaType.APPLICATION_JSON)
                        .content(farmerJson("A B", "12345", "N")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        mvc.perform(as(clerk, post("/api/v1/farmers")).contentType(MediaType.APPLICATION_JSON)
                .content(farmerJson("First", "0788000001", "DUP-001"))).andExpect(status().isCreated());
        mvc.perform(as(clerk, post("/api/v1/farmers")).contentType(MediaType.APPLICATION_JSON)
                        .content(farmerJson("Second", "0788000002", "dup-001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COOPERATIVE_NUMBER"));
    }

    @Test
    void updatesAndDeactivatesFarmer() throws Exception {
        Farmer f = farmer();
        mvc.perform(as(clerk, put("/api/v1/farmers/" + f.getId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Renamed\",\"phone\":\"0788111222\",\"cooperativeNumber\":\"" + f.getCooperativeNumber() + "\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed"))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(as(clerk, get("/api/v1/farmers/summary")))
                .andExpect(jsonPath("$.inactive").value(1)).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void farmerListingSupportsSearchSortAndPaging() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(as(clerk, post("/api/v1/farmers")).contentType(MediaType.APPLICATION_JSON)
                    .content(farmerJson("Farmer " + (char) ('A' + i), "078800000" + i, "SRC-00" + i)));
        }
        mvc.perform(as(clerk, get("/api/v1/farmers").param("size", "2").param("page", "1")))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].fullName").value("Farmer C"));
        mvc.perform(as(clerk, get("/api/v1/farmers").param("q", "src-003")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(clerk, get("/api/v1/farmers").param("sortBy", "fullName").param("dir", "desc")))
                .andExpect(jsonPath("$.content[0].fullName").value("Farmer E"));
    }

    @Test
    void farmerDetailShowsTotalsFromDeliveries() throws Exception {
        Farmer f = farmer();
        long a = delivery(f, "100");
        long b = delivery(f, "200");
        delivery(f, "50");
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + a + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}"));
        mvc.perform(as(supervisor, post("/api/v1/deliveries/" + a + "/pay")));
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + b + "/reject")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Unripe\"}"));

        mvc.perform(as(clerk, get("/api/v1/farmers/" + f.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.farmer.totalDeliveries").value(3))
                .andExpect(jsonPath("$.farmer.totalWeightKg").value(150.0))      // 100 + 50 (rejected 200 excluded)
                .andExpect(jsonPath("$.farmer.totalAmountPaid").value(120000.0))
                .andExpect(jsonPath("$.recentDeliveries.length()").value(3));
    }

    private long delivery(Farmer f, String kg) throws Exception {
        MvcResult r = mvc.perform(as(clerk, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + f.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":" + kg + "}"))
                .andExpect(status().isCreated()).andReturn();
        return body(r).get("id").asLong();
    }

    @Test
    void dashboardAndReportsReflectTheData() throws Exception {
        Farmer f = farmer();
        long a = delivery(f, "100");
        long b = delivery(f, "200");
        delivery(f, "300");
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + a + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"A\"}"));
        mvc.perform(as(supervisor, post("/api/v1/deliveries/" + a + "/pay")));
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + b + "/reject")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Unripe\"}"));

        mvc.perform(as(clerk, get("/api/v1/dashboard")))
                .andExpect(jsonPath("$.capacity.acceptedKg").value(400.0))
                .andExpect(jsonPath("$.capacity.statusCounts.PAID").value(1))
                .andExpect(jsonPath("$.capacity.statusCounts.REJECTED").value(1))
                .andExpect(jsonPath("$.capacity.statusCounts.RECEIVED").value(1))
                .andExpect(jsonPath("$.capacity.totalAmountPaid").value(120000.0))
                .andExpect(jsonPath("$.intakeTrend.length()").value(7))
                .andExpect(jsonPath("$.recentDeliveries.length()").value(3));

        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "DAILY_INTAKE")))
                .andExpect(jsonPath("$.rows[0].accepted_kg").value(400.0))
                .andExpect(jsonPath("$.rows[0].rejected_kg").value(200.0))
                .andExpect(jsonPath("$.totals.amount_paid").value(120000.0));
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "GRADE_DISTRIBUTION")))
                .andExpect(jsonPath("$.rows[0].grade").value("A"))
                .andExpect(jsonPath("$.rows[0].share_percent").value(100.0));
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "REJECTED_DELIVERIES")))
                .andExpect(jsonPath("$.rows[0].reason").value("Unripe"));
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "CAPACITY_UTILIZATION")))
                .andExpect(jsonPath("$.rows[0].utilization_percent").value(8.0));
    }

    @Test
    void reportRangeIsValidated() throws Exception {
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "PAYMENT").param("from", "2026-10-02").param("to", "2026-10-01")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "PAYMENT").param("from", "2020-01-01").param("to", "2026-10-01")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
        mvc.perform(as(supervisor, get("/api/v1/reports").param("type", "NOPE"))).andExpect(status().isBadRequest());
    }

    @Test
    void csvExportMatchesTheReportAndNeutralisesFormulas() throws Exception {
        Farmer f = farmerRepository.save(new Farmer("=HYPERLINK(\"http://evil\")", "0788123456", "CSV-0001", java.time.Instant.now()));
        long id = delivery(f, "100");
        mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON).content("{\"grade\":\"B\"}"));
        mvc.perform(as(supervisor, post("/api/v1/deliveries/" + id + "/pay")));

        MvcResult res = mvc.perform(as(supervisor, get("/api/v1/reports/export").param("type", "PAYMENT"))).andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getHeader("Content-Disposition")).contains("attachment", ".csv");
        String csv = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Reference,Delivery date,Farmer");
        assertThat(csv).contains("80000.00");
        assertThat(csv).doesNotContain(",=HYPERLINK").contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
    }
}

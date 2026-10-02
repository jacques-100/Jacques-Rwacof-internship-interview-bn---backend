package com.rwacof.cherrytrack.api;

import com.rwacof.cherrytrack.model.Farmer;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Grades and prices are data the station configures, not constants in the code. */
class GradesIntegrationTest extends AbstractIntegrationTest {

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

    private long createGrade(String code, String name) throws Exception {
        return body(mvc.perform(as(supervisor, post("/api/v1/grades")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"description\":\"Custom\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private void setPrice(String grade, String price) throws Exception {
        mvc.perform(as(supervisor, post("/api/v1/prices")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"grade\":\"" + grade + "\",\"pricePerKg\":" + price + "}")).andExpect(status().isCreated());
    }

    private long receive(String weight) throws Exception {
        return body(mvc.perform(as(clerk, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"farmerId\":" + farmer.getId() + ",\"deliveryDate\":\"" + today() + "\",\"weightKg\":" + weight + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private ResultActions grade(long id, String form) throws Exception {
        return mvc.perform(as(clerk, post("/api/v1/deliveries/" + id + "/grade")).contentType(MediaType.APPLICATION_JSON).content(form));
    }

    @Test
    void everyoneCanReadGradesButOnlySupervisorsChangeThem() throws Exception {
        mvc.perform(as(clerk, get("/api/v1/grades"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("A", "B")));
        mvc.perform(as(clerk, post("/api/v1/grades")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"AA\",\"name\":\"Grade AA\"}")).andExpect(status().isForbidden());
        createGrade("AA", "Grade AA");
    }

    @Test
    void aNewGradeCanBePricedAndAwardedUsingTheGradingForm() throws Exception {
        createGrade("AA", "Grade AA");
        setPrice("aa", "1500.00");                      // codes are case-insensitive, stored in capitals
        long id = receive("100");

        grade(id, "{\"grade\":\"AA\",\"moisturePercent\":11.5,\"notes\":\"Uniform, bright red\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value("AA"))
                .andExpect(jsonPath("$.pricePerKg").value(1500.0))
                .andExpect(jsonPath("$.amountOwed").value(150000.0))
                .andExpect(jsonPath("$.moisturePercent").value(11.5))
                .andExpect(jsonPath("$.gradeNotes").value("Uniform, bright red"));

        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id + "/audit")))
                .andExpect(jsonPath("$[1].details.moisturePercent").value(11.5))
                .andExpect(jsonPath("$[1].details.reason").value("Uniform, bright red"));
        mvc.perform(as(clerk, get("/api/v1/deliveries").param("grade", "AA"))).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void theGradingFormValidatesItsFields() throws Exception {
        long id = receive("100");
        grade(id, "{\"grade\":\"A\",\"moisturePercent\":120}").andExpect(status().isBadRequest());
        grade(id, "{\"grade\":\"A\",\"moisturePercent\":-1}").andExpect(status().isBadRequest());
        grade(id, "{\"grade\":\"A\",\"moisturePercent\":11.55}").andExpect(status().isBadRequest());
        grade(id, "{\"grade\":\"\"}").andExpect(status().isBadRequest());
        grade(id, "{\"grade\":\"A\",\"amountOwed\":5}").andExpect(status().isBadRequest());   // still no client amounts
        grade(id, "{\"grade\":\"A\",\"moisturePercent\":0}").andExpect(status().isOk());
    }

    @Test
    void unknownGradesAreRefused() throws Exception {
        long id = receive("100");
        grade(id, "{\"grade\":\"ZZ\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_GRADE"));
        mvc.perform(as(supervisor, post("/api/v1/prices")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"grade\":\"ZZ\",\"pricePerKg\":100}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aGradeWithoutAPriceCannotBeAwarded() throws Exception {
        createGrade("C", "Grade C");
        long id = receive("100");
        grade(id, "{\"grade\":\"C\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PRICE_NOT_CONFIGURED"));
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + id))).andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void switchingAGradeOffStopsNewGradingButKeepsHistory() throws Exception {
        long gradeId = createGrade("AA", "Grade AA");
        setPrice("AA", "1500");
        long first = receive("100");
        grade(first, "{\"grade\":\"AA\"}").andExpect(status().isOk());

        mvc.perform(as(supervisor, put("/api/v1/grades/" + gradeId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"AA\",\"name\":\"Grade AA\",\"active\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        long second = receive("50");
        grade(second, "{\"grade\":\"AA\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GRADE_INACTIVE"));
        mvc.perform(as(clerk, get("/api/v1/deliveries/" + first))).andExpect(jsonPath("$.grade").value("AA")).andExpect(jsonPath("$.amountOwed").value(150000.0));
        mvc.perform(as(clerk, get("/api/v1/grades").param("activeOnly", "true"))).andExpect(jsonPath("$[*].code", not(hasItem("AA"))));
        mvc.perform(as(clerk, get("/api/v1/prices"))).andExpect(jsonPath("$.current[*].grade", not(hasItem("AA"))))
                .andExpect(jsonPath("$.history[*].grade", hasItem("AA")));
    }

    @Test
    void gradeConfigurationIsValidated() throws Exception {
        long id = createGrade("AA", "Grade AA");
        mvc.perform(as(supervisor, post("/api/v1/grades")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"aa\",\"name\":\"Another\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_GRADE"));
        mvc.perform(as(supervisor, post("/api/v1/grades")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"AB\",\"name\":\"grade aa\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_GRADE_NAME"));
        mvc.perform(as(supervisor, post("/api/v1/grades")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"bad code!\",\"name\":\"Bad\"}")).andExpect(status().isBadRequest());
        // the code is what deliveries and prices store, so it can't change
        mvc.perform(as(supervisor, put("/api/v1/grades/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"AAA\",\"name\":\"Grade AA\"}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GRADE_CODE_FIXED"));
        mvc.perform(as(supervisor, put("/api/v1/grades/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"AA\",\"name\":\"Renamed AA\",\"description\":\"Updated\",\"sortOrder\":0}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed AA"));
    }

    @Test
    void priceHistoryIsPerGradeAndAppendOnly() throws Exception {
        createGrade("AA", "Grade AA");
        setPrice("AA", "1500");
        setPrice("AA", "1650");
        mvc.perform(as(clerk, get("/api/v1/prices")))
                .andExpect(jsonPath("$.current[?(@.grade=='AA')].pricePerKg").value(1650.0))
                .andExpect(jsonPath("$.history[?(@.grade=='AA')]", hasSize(2)));
    }
}

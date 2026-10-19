package com.nexora.semantic.api;

import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RFC 7807 contract for framework-level errors: stable codes, generic details, no internals. */
class ErrorHandlingIntegrationTest extends IntegrationTest {

    private String steward;

    @BeforeEach
    void setUp() {
        steward = TestJwt.human(newTenant(), "STEWARD").bearer();
    }

    @Test
    void malformedJson() throws Exception {
        String response = mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\": \"erp\", \"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Malformed or unreadable request"))
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.instance").value("/api/v1/data-sources"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Jackson").doesNotContain("JSON parse").doesNotContain("Exception");
    }

    @Test
    void unsupportedMediaType() throws Exception {
        mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.TEXT_PLAIN).content("key=erp"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_media_type"));
    }

    @Test
    void methodNotAllowedKeepsAllowHeader() throws Exception {
        mvc.perform(delete("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists(HttpHeaders.ALLOW))
                .andExpect(jsonPath("$.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.detail").value("Method not allowed"));
    }

    @Test
    void unknownApiPath() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, steward))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void missingBody() throws Exception {
        mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));
    }
}

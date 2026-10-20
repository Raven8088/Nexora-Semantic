package com.nexora.semantic.api;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Import documents above {@code nexora.semantic.discovery.max-import-bytes} are rejected without side effects. */
@TestPropertySource(properties = "nexora.semantic.discovery.max-import-bytes=4096")
class MetadataImportLimitIntegrationTest extends IntegrationTest {

    @Test
    void oversizedDocumentIsRejectedWith413() throws Exception {
        Tenant tenant = newTenant();
        String steward = TestJwt.human(tenant, "STEWARD").bearer();
        String dataSourceId = body(mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", "postgresql",
                                "databaseName", "erp"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();

        // The real Discovery response (~8 KB) exceeds the 4 KB limit configured for this test.
        mvc.perform(post("/api/v1/data-sources/" + dataSourceId + "/snapshots").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(ALL_SCHEMAS)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("payload_too_large"));

        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/snapshots").header(HttpHeaders.AUTHORIZATION, steward))
                .andExpect(jsonPath("$.totalItems").value(0));
    }
}

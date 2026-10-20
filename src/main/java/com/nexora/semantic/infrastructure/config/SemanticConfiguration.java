package com.nexora.semantic.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.application.audit.AuditQueryService;
import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.DiscoveredMetadataReader;
import com.nexora.semantic.application.port.MetadataSnapshotRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.TenantRepository;
import com.nexora.semantic.application.source.DataSourceService;
import com.nexora.semantic.application.source.MetadataImportService;
import com.nexora.semantic.application.source.PhysicalMetadataQueryService;
import com.nexora.semantic.application.tenant.TenantService;
import com.nexora.semantic.infrastructure.discovery.JacksonDiscoveredMetadataReader;
import com.nexora.semantic.infrastructure.persistence.JdbcAuditLog;
import com.nexora.semantic.infrastructure.persistence.JdbcDataSourceRepository;
import com.nexora.semantic.infrastructure.persistence.JdbcMetadataSnapshotRepository;
import com.nexora.semantic.infrastructure.persistence.JdbcPhysicalObjectRepository;
import com.nexora.semantic.infrastructure.persistence.JdbcTenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;

/**
 * Wires the framework-free application services and their persistence adapters. Application classes carry no
 * Spring stereotypes; only {@code @Transactional} (spring-tx), which takes effect on these beans.
 */
@Configuration(proxyBeanMethods = false)
public class SemanticConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TenantRepository tenantRepository(JdbcClient jdbcClient) {
        return new JdbcTenantRepository(jdbcClient);
    }

    @Bean
    DataSourceRepository dataSourceRepository(JdbcClient jdbcClient) {
        return new JdbcDataSourceRepository(jdbcClient);
    }

    @Bean
    AuditLog auditLog(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        return new JdbcAuditLog(jdbcClient, objectMapper);
    }

    @Bean
    TenantService tenantService(TenantRepository tenants, AuditLog auditLog, Clock clock) {
        return new TenantService(tenants, auditLog, clock);
    }

    @Bean
    DataSourceService dataSourceService(DataSourceRepository dataSources, AuditLog auditLog, Clock clock) {
        return new DataSourceService(dataSources, auditLog, clock);
    }

    @Bean
    AuditQueryService auditQueryService(AuditLog auditLog) {
        return new AuditQueryService(auditLog);
    }

    // --- S2 Basic: Discovery metadata import ---

    @Bean
    DiscoveredMetadataReader discoveredMetadataReader() {
        return new JacksonDiscoveredMetadataReader();
    }

    @Bean
    MetadataSnapshotRepository metadataSnapshotRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        return new JdbcMetadataSnapshotRepository(jdbcClient, objectMapper);
    }

    @Bean
    PhysicalObjectRepository physicalObjectRepository(JdbcClient jdbcClient, NamedParameterJdbcOperations batch,
                                                      ObjectMapper objectMapper) {
        return new JdbcPhysicalObjectRepository(jdbcClient, batch, objectMapper);
    }

    @Bean
    MetadataImportService metadataImportService(DataSourceRepository dataSources, DiscoveredMetadataReader reader,
                                                MetadataSnapshotRepository snapshots, PhysicalObjectRepository objects,
                                                AuditLog auditLog, Clock clock) {
        return new MetadataImportService(dataSources, reader, snapshots, objects, auditLog, clock);
    }

    @Bean
    PhysicalMetadataQueryService physicalMetadataQueryService(DataSourceRepository dataSources,
                                                              MetadataSnapshotRepository snapshots,
                                                              PhysicalObjectRepository objects) {
        return new PhysicalMetadataQueryService(dataSources, snapshots, objects);
    }
}

package com.nexora.semantic.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.application.audit.AuditQueryService;
import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.TenantRepository;
import com.nexora.semantic.application.source.DataSourceService;
import com.nexora.semantic.application.tenant.TenantService;
import com.nexora.semantic.infrastructure.persistence.JdbcAuditLog;
import com.nexora.semantic.infrastructure.persistence.JdbcDataSourceRepository;
import com.nexora.semantic.infrastructure.persistence.JdbcTenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}

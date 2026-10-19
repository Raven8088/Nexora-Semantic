package com.nexora.semantic.application;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.TenantRepository;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DataSourceStatus;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory test doubles of the application ports, with the same tenant scoping as the JDBC adapters. */
public final class InMemoryAdapters {

    private InMemoryAdapters() {
    }

    public static final class Tenants implements TenantRepository {
        private final Map<TenantId, Tenant> rows = new LinkedHashMap<>();

        @Override
        public void insert(Tenant tenant) {
            if (rows.values().stream().anyMatch(t -> t.key().equals(tenant.key()))) {
                throw new DuplicateResourceException("Tenant");
            }
            rows.put(tenant.id(), tenant);
        }

        @Override
        public Optional<Tenant> findById(TenantId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Tenant> findByKey(TenantKey key) {
            return rows.values().stream().filter(t -> t.key().equals(key)).findFirst();
        }

        @Override
        public Page<Tenant> findAll(PageQuery q) {
            List<Tenant> all = rows.values().stream().sorted(Comparator.comparing(t -> t.key().value())).toList();
            return slice(all, q);
        }

        @Override
        public Tenant update(Tenant tenant, long expectedVersion) {
            Tenant stored = rows.get(tenant.id());
            if (stored == null || stored.version() != expectedVersion) {
                throw new StaleResourceException("Tenant");
            }
            Tenant saved = new Tenant(tenant.id(), tenant.key(), tenant.name(), tenant.status(), tenant.createdAt(),
                    tenant.createdBy(), tenant.updatedAt(), tenant.updatedBy(), expectedVersion + 1);
            rows.put(saved.id(), saved);
            return saved;
        }
    }

    public static final class DataSources implements DataSourceRepository {
        private final Map<DataSourceId, DataSource> rows = new LinkedHashMap<>();

        @Override
        public void insert(DataSource ds) {
            if (rows.values().stream().anyMatch(d -> d.tenantId().equals(ds.tenantId()) && d.key().equals(ds.key()))) {
                throw new DuplicateResourceException("Data source");
            }
            rows.put(ds.id(), ds);
        }

        @Override
        public Optional<DataSource> findById(TenantId tenantId, DataSourceId id) {
            return Optional.ofNullable(rows.get(id)).filter(d -> d.tenantId().equals(tenantId));
        }

        @Override
        public Page<DataSource> findPage(TenantId tenantId, DataSourceStatus status, PageQuery q) {
            List<DataSource> all = rows.values().stream()
                    .filter(d -> d.tenantId().equals(tenantId))
                    .filter(d -> status == null || d.status() == status)
                    .sorted(Comparator.comparing(d -> d.key().value()))
                    .toList();
            return slice(all, q);
        }

        @Override
        public DataSource update(DataSource ds, long expectedVersion) {
            DataSource stored = findById(ds.tenantId(), ds.id()).orElse(null);
            if (stored == null || stored.version() != expectedVersion) {
                throw new StaleResourceException("Data source");
            }
            DataSource saved = new DataSource(ds.id(), ds.tenantId(), ds.key(), ds.name(), ds.engine(),
                    ds.databaseName(), ds.description(), ds.status(), ds.createdAt(), ds.createdBy(), ds.updatedAt(),
                    ds.updatedBy(), expectedVersion + 1);
            rows.put(saved.id(), saved);
            return saved;
        }
    }

    public static final class RecordingAuditLog implements AuditLog {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void append(AuditEvent event) {
            events.add(event);
        }

        @Override
        public Page<AuditEvent> find(TenantId tenantId, AuditTargetType targetType, UUID targetId, PageQuery q) {
            List<AuditEvent> matching = events.stream()
                    .filter(e -> e.tenantId().equals(tenantId))
                    .filter(e -> targetType == null || e.targetType() == targetType)
                    .filter(e -> targetId == null || Objects.equals(e.targetId(), targetId))
                    .sorted(Comparator.comparing(AuditEvent::occurredAt).reversed())
                    .toList();
            return slice(matching, q);
        }

        public List<AuditEvent> events() {
            return List.copyOf(events);
        }
    }

    private static <T> Page<T> slice(List<T> all, PageQuery q) {
        int from = (int) Math.min(q.offset(), all.size());
        int to = Math.min(from + q.size(), all.size());
        return new Page<>(all.subList(from, to), q.page(), q.size(), all.size());
    }
}

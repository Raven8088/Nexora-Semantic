package com.nexora.semantic.application.source;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceChanges;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DataSourceKey;
import com.nexora.semantic.domain.source.DataSourceStatus;
import com.nexora.semantic.domain.source.EngineCode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Registration and maintenance of data sources. Every operation is scoped to the caller's tenant. */
@Transactional
public class DataSourceService {

    private static final String RESOURCE = "Data source";

    private final DataSourceRepository dataSources;
    private final AuditLog auditLog;
    private final Clock clock;

    public DataSourceService(DataSourceRepository dataSources, AuditLog auditLog, Clock clock) {
        this.dataSources = dataSources;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public DataSource register(CallerContext caller, RegisterDataSourceCommand command) {
        Instant now = now();
        DataSource dataSource = DataSource.register(DataSourceId.newId(), caller.tenantId(),
                new DataSourceKey(command.key()), command.name(), new EngineCode(command.engine()),
                command.databaseName(), command.description(), caller.principal().id(), now);
        dataSources.insert(dataSource);
        audit(caller, now, AuditAction.DATA_SOURCE_REGISTERED, dataSource,
                Map.of("key", dataSource.key().value(), "engine", dataSource.engine().value()));
        return dataSource;
    }

    @Transactional(readOnly = true)
    public DataSource get(CallerContext caller, DataSourceId id) {
        return dataSources.findById(caller.tenantId(), id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    @Transactional(readOnly = true)
    public Page<DataSource> list(CallerContext caller, DataSourceStatus status, PageQuery pageQuery) {
        return dataSources.findPage(caller.tenantId(), status, pageQuery);
    }

    /**
     * Applies {@code changes} if {@code expectedVersion} is still current. A request that changes nothing
     * returns the data source untouched, without a new version or audit event.
     */
    public DataSource update(CallerContext caller, DataSourceId id, long expectedVersion, DataSourceChanges changes) {
        DataSource current = get(caller, id);
        if (current.version() != expectedVersion) {
            throw new StaleResourceException(RESOURCE);
        }
        Instant now = now();
        DataSource changed = current.apply(changes, caller.principal().id(), now);
        if (changed == current) {
            return current;
        }
        DataSource saved = dataSources.update(changed, expectedVersion);
        List<String> changedFields = changed.changedDetailsComparedTo(current);
        if (!changedFields.isEmpty()) {
            audit(caller, now, AuditAction.DATA_SOURCE_UPDATED, saved, Map.of("changedFields", changedFields));
        }
        if (changed.status() != current.status()) {
            AuditAction action = changed.isActive()
                    ? AuditAction.DATA_SOURCE_REACTIVATED : AuditAction.DATA_SOURCE_ARCHIVED;
            audit(caller, now, action, saved,
                    Map.of("from", current.status().name(), "to", changed.status().name()));
        }
        return saved;
    }

    private void audit(CallerContext caller, Instant now, AuditAction action, DataSource target,
                       Map<String, Object> details) {
        auditLog.append(AuditEvent.of(caller.tenantId(), now, caller.principal(), caller.correlationId(), action,
                AuditTargetType.DATA_SOURCE, target.id().value(), details));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

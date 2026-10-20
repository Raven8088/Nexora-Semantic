package com.nexora.semantic.infrastructure.discovery;

import com.nexora.semantic.application.port.DiscoveredMetadataReader;
import com.nexora.semantic.domain.source.DiscoveredDatabase;
import com.nexora.semantic.domain.source.DiscoveredDatabase.SnapshotScope;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.InvalidSnapshotException;
import com.nexora.semantic.domain.source.InvalidSnapshotException.Kind;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anti-corruption adapter for Nexora Discovery metadata (audit finding I7). Reads Semantic's import envelope:
 * <pre>
 * { "contractVersion": "discovery-api/0.1", "discoveredAt": "2026-10-19T08:00:00Z",   (optional)
 *   "requestedSchemas": ["sales"],                                                    (optional; absent/empty = all)
 *   "payload": { ...Discovery POST /api/discovery 200 response... } }
 * </pre>
 * The envelope is Semantic's contract and is strict (unknown fields rejected). The payload belongs to Discovery
 * and is read tolerantly with this adapter's own mapper, leaving the application's global strict JSON setting
 * untouched. The payload is then validated structurally; violations name JSON paths, never values.
 */
public class JacksonDiscoveredMetadataReader implements DiscoveredMetadataReader {

    public static final String CONTRACT_VERSION = "discovery-api/0.1";

    static final int MAX_OBJECTS = 200_000;
    static final int MAX_IDENTIFIER_LENGTH = 128;
    static final int MAX_COMMENT_LENGTH = 2000;
    private static final int MAX_VIOLATIONS = 100;
    private static final Set<String> ENVELOPE_FIELDS = Set.of("contractVersion", "discoveredAt", "requestedSchemas",
            "payload");
    private static final Pattern ENGINE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public DiscoveredDatabase read(byte[] document) {
        JsonNode root;
        try {
            root = mapper.readTree(document);
        } catch (JacksonException e) {
            throw new InvalidSnapshotException(Kind.MALFORMED, "Malformed JSON", List.of());
        }
        Violations violations = new Violations();
        if (root == null || !root.isObject()) {
            throw violations.add("$", "must be a JSON object").fail();
        }
        root.propertyNames().forEach(name -> {
            if (!ENVELOPE_FIELDS.contains(name)) {
                violations.add(truncate(name), "unknown property");
            }
        });

        JsonNode version = root.get("contractVersion");
        if (version == null || !version.isString()) {
            violations.add("contractVersion", "is required");
        } else if (!CONTRACT_VERSION.equals(version.asString())) {
            throw new InvalidSnapshotException(Kind.UNSUPPORTED_CONTRACT_VERSION, "Unsupported contract version",
                    List.of(Map.of("field", "contractVersion", "message", "supported: " + CONTRACT_VERSION)));
        }
        Instant discoveredAt = discoveredAt(root.get("discoveredAt"), violations);
        Set<String> requested = requestedSchemas(root.get("requestedSchemas"), violations);
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            violations.add("payload", "is required and must be an object");
        }
        violations.throwIfAny();

        DiscoveryPayloadV01 payload;
        try {
            payload = mapper.treeToValue(payloadNode, DiscoveryPayloadV01.class);
        } catch (JacksonException e) {
            throw violations.add("payload", "does not match the Discovery response contract").fail();
        }
        DiscoveredDatabase database = new PayloadMapper(violations, new SnapshotScope(requested))
                .map(payload, discoveredAt);
        violations.throwIfAny();
        return database;
    }

    private static Instant discoveredAt(JsonNode node, Violations violations) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            violations.add("discoveredAt", "must be an ISO-8601 instant");
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException e) {
            violations.add("discoveredAt", "must be an ISO-8601 instant");
            return null;
        }
    }

    private static Set<String> requestedSchemas(JsonNode node, Violations violations) {
        Set<String> requested = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return requested;
        }
        if (!node.isArray()) {
            violations.add("requestedSchemas", "must be an array of schema names");
            return requested;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String path = "requestedSchemas[" + i + "]";
            if (!item.isString() || !validIdentifier(item.asString())) {
                violations.add(path, "must be a schema name");
            } else if (!requested.add(item.asString())) {
                violations.add(path, "is duplicated");
            }
        }
        return requested;
    }

    /** Maps the tolerant DTOs to Semantic's model, collecting violations with their JSON paths. */
    private static final class PayloadMapper {

        private final Violations violations;
        private final SnapshotScope scope;
        private int objects;

        PayloadMapper(Violations violations, SnapshotScope scope) {
            this.violations = violations;
            this.scope = scope;
        }

        DiscoveredDatabase map(DiscoveryPayloadV01 payload, Instant discoveredAt) {
            String database = identifier(payload.database(), "payload.database");
            EngineCode engine = null;
            if (payload.engine() == null || !ENGINE.matcher(payload.engine()).matches()) {
                violations.add("payload.engine", "must be an engine code");
            } else {
                engine = new EngineCode(payload.engine());
            }
            String productVersion = sanitize(payload.productVersion(), 255);
            List<DiscoveredDatabase.Schema> schemas = new ArrayList<>();
            if (payload.schemas() == null) {
                violations.add("payload.schemas", "is required");
            } else {
                Set<String> names = new HashSet<>();
                for (int i = 0; i < payload.schemas().size(); i++) {
                    DiscoveredDatabase.Schema schema = schema(payload.schemas().get(i), "payload.schemas[" + i + "]",
                            names);
                    if (schema != null) {
                        schemas.add(schema);
                    }
                }
            }
            if (objects > MAX_OBJECTS) {
                violations.add("payload.schemas", "exceeds " + MAX_OBJECTS + " objects");
            }
            if (violations.any() || database == null || engine == null) {
                return null;
            }
            return new DiscoveredDatabase(CONTRACT_VERSION, database, engine, productVersion, discoveredAt, scope,
                    schemas);
        }

        private DiscoveredDatabase.Schema schema(DiscoveryPayloadV01.Schema dto, String path, Set<String> names) {
            if (dto == null) {
                violations.add(path, "must be an object");
                return null;
            }
            String name = identifier(dto.name(), path + ".name");
            if (name != null && !names.add(name)) {
                violations.add(path + ".name", "is duplicated");
            }
            if (name != null && !scope.covers(name)) {
                violations.add(path + ".name", "was not requested (requestedSchemas)");
            }
            objects++;
            List<DiscoveredDatabase.Relation> relations = new ArrayList<>();
            Set<String> relationNames = new HashSet<>();
            List<DiscoveryPayloadV01.Table> tables = dto.tables() == null ? List.of() : dto.tables();
            for (int i = 0; i < tables.size(); i++) {
                DiscoveredDatabase.Relation relation = table(tables.get(i), path + ".tables[" + i + "]", relationNames);
                if (relation != null) {
                    relations.add(relation);
                }
            }
            List<DiscoveryPayloadV01.View> views = dto.views() == null ? List.of() : dto.views();
            for (int i = 0; i < views.size(); i++) {
                DiscoveredDatabase.Relation relation = view(views.get(i), path + ".views[" + i + "]", relationNames);
                if (relation != null) {
                    relations.add(relation);
                }
            }
            return name == null ? null : new DiscoveredDatabase.Schema(name, relations);
        }

        private DiscoveredDatabase.Relation table(DiscoveryPayloadV01.Table dto, String path, Set<String> names) {
            if (dto == null) {
                violations.add(path, "must be an object");
                return null;
            }
            String name = relationName(dto.name(), path, names);
            List<DiscoveredDatabase.Column> columns = columns(dto.columns(), path);
            Set<String> columnNames = new HashSet<>();
            columns.forEach(column -> columnNames.add(column.name()));
            List<String> primaryKey = columnList(dto.primaryKey(), path + ".primaryKey", columnNames, false);
            List<DiscoveredDatabase.ForeignKey> foreignKeys = new ArrayList<>();
            Set<String> foreignKeyNames = new HashSet<>();
            List<DiscoveryPayloadV01.ForeignKey> fks = dto.foreignKeys() == null ? List.of() : dto.foreignKeys();
            for (int i = 0; i < fks.size(); i++) {
                DiscoveredDatabase.ForeignKey foreignKey = foreignKey(fks.get(i), path + ".foreignKeys[" + i + "]",
                        name, columnNames, foreignKeyNames);
                if (foreignKey != null) {
                    foreignKeys.add(foreignKey);
                }
            }
            return name == null ? null : new DiscoveredDatabase.Relation(PhysicalObjectType.TABLE, name,
                    sanitize(dto.comment(), MAX_COMMENT_LENGTH), columns, primaryKey, foreignKeys);
        }

        private DiscoveredDatabase.Relation view(DiscoveryPayloadV01.View dto, String path, Set<String> names) {
            if (dto == null) {
                violations.add(path, "must be an object");
                return null;
            }
            String name = relationName(dto.name(), path, names);
            List<DiscoveredDatabase.Column> columns = columns(dto.columns(), path);
            PhysicalObjectType type = Boolean.TRUE.equals(dto.materialized())
                    ? PhysicalObjectType.MATERIALIZED_VIEW : PhysicalObjectType.VIEW;
            return name == null ? null : new DiscoveredDatabase.Relation(type, name,
                    sanitize(dto.comment(), MAX_COMMENT_LENGTH), columns, List.of(), List.of());
        }

        private String relationName(String value, String path, Set<String> names) {
            String name = identifier(value, path + ".name");
            if (name != null && !names.add(name)) {
                violations.add(path + ".name", "is duplicated within the schema");
            }
            objects++;
            return name;
        }

        private List<DiscoveredDatabase.Column> columns(List<DiscoveryPayloadV01.Column> dtos, String path) {
            List<DiscoveredDatabase.Column> columns = new ArrayList<>();
            if (dtos == null) {
                return columns;
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < dtos.size(); i++) {
                String columnPath = path + ".columns[" + i + "]";
                DiscoveryPayloadV01.Column dto = dtos.get(i);
                if (dto == null) {
                    violations.add(columnPath, "must be an object");
                    continue;
                }
                objects++;
                String name = identifier(dto.name(), columnPath + ".name");
                if (name != null && !names.add(name)) {
                    violations.add(columnPath + ".name", "is duplicated within the relation");
                }
                if (dto.position() == null || dto.position() < 1) {
                    violations.add(columnPath + ".position", "must be >= 1");
                }
                String dataType = identifier(dto.dataType(), columnPath + ".dataType");
                if (dto.size() != null && dto.size() < 0) {
                    violations.add(columnPath + ".size", "must be >= 0");
                }
                if (dto.scale() != null && dto.scale() < 0) {
                    violations.add(columnPath + ".scale", "must be >= 0");
                }
                if (dto.nullable() == null) {
                    violations.add(columnPath + ".nullable", "is required");
                }
                if (name != null && dataType != null && dto.position() != null && dto.position() >= 1
                        && dto.nullable() != null) {
                    columns.add(new DiscoveredDatabase.Column(name, dto.position(), dataType, dto.size(), dto.scale(),
                            dto.nullable(), sanitize(dto.comment(), MAX_COMMENT_LENGTH)));
                }
            }
            return columns;
        }

        private DiscoveredDatabase.ForeignKey foreignKey(DiscoveryPayloadV01.ForeignKey dto, String path,
                                                         String tableName, Set<String> columnNames,
                                                         Set<String> foreignKeyNames) {
            if (dto == null) {
                violations.add(path, "must be an object");
                return null;
            }
            objects++;
            if (dto.sourceTable() != null && tableName != null && !dto.sourceTable().equals(tableName)) {
                violations.add(path + ".sourceTable", "must match the enclosing table");
            }
            List<String> columns = columnList(dto.sourceColumns(), path + ".sourceColumns", columnNames, true);
            String targetSchema = identifier(dto.targetSchema(), path + ".targetSchema");
            String targetTable = identifier(dto.targetTable(), path + ".targetTable");
            List<String> targetColumns = columnList(dto.targetColumns(), path + ".targetColumns", null, true);
            if (columns.size() != targetColumns.size()) {
                violations.add(path + ".targetColumns", "must have as many columns as sourceColumns");
            }
            // Discovery may omit a constraint name; a deterministic name keeps the natural key stable.
            String name = dto.name() == null ? "<unnamed:" + String.join(",", columns) + ">"
                    : identifier(dto.name(), path + ".name");
            if (name != null && !foreignKeyNames.add(name)) {
                violations.add(path + ".name", "is duplicated within the table");
            }
            if (name == null || targetSchema == null || targetTable == null || columns.isEmpty()) {
                return null;
            }
            return new DiscoveredDatabase.ForeignKey(name, columns, targetSchema, targetTable, targetColumns);
        }

        /**
         * @param known    columns that must contain every entry, or null for columns of another relation
         * @param required whether the list must be non-empty
         */
        private List<String> columnList(List<String> values, String path, Set<String> known, boolean required) {
            List<String> columns = new ArrayList<>();
            if (values == null || values.isEmpty()) {
                if (required) {
                    violations.add(path, "must not be empty");
                }
                return columns;
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < values.size(); i++) {
                String column = identifier(values.get(i), path + "[" + i + "]");
                if (column == null) {
                    continue;
                }
                if (!seen.add(column)) {
                    violations.add(path + "[" + i + "]", "is duplicated");
                } else if (known != null && !known.contains(column)) {
                    violations.add(path + "[" + i + "]", "is not a column of the table");
                }
                columns.add(column);
            }
            return columns;
        }

        private String identifier(String value, String path) {
            if (!validIdentifier(value)) {
                violations.add(path, "must be 1-" + MAX_IDENTIFIER_LENGTH + " characters without control characters");
                return null;
            }
            return value;
        }
    }

    static boolean validIdentifier(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_IDENTIFIER_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }

    /** Untrusted free text: drops control characters (except line breaks and tabs) and truncates. */
    static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), maxLength));
        value.codePoints()
                .filter(c -> !Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t')
                .forEach(clean::appendCodePoint);
        String text = clean.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String truncate(String field) {
        return field.length() <= 64 ? field : field.substring(0, 64);
    }

    private static final class Violations {

        private final List<Map<String, String>> entries = new ArrayList<>();

        Violations add(String field, String message) {
            if (entries.size() < MAX_VIOLATIONS) {
                entries.add(Map.of("field", field, "message", message));
            }
            return this;
        }

        boolean any() {
            return !entries.isEmpty();
        }

        void throwIfAny() {
            if (any()) {
                throw fail();
            }
        }

        InvalidSnapshotException fail() {
            return new InvalidSnapshotException(Kind.INVALID, "Snapshot does not satisfy the import contract", entries);
        }
    }
}

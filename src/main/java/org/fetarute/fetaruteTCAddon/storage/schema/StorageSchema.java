package org.fetarute.fetaruteTCAddon.storage.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;

/**
 * 针对 Railway Company 模型的 DDL 生成器，默认生成 SQLite 语句，可按方言调整。
 */
public final class StorageSchema {

    private static final String DEFAULT_PREFIX = "fta_";
    private final String tablePrefix;

    public StorageSchema() {
        this(DEFAULT_PREFIX);
    }

    public StorageSchema(String tablePrefix) {
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    /**
     * 生成 SQLite 所需的所有建表语句。
     */
    public List<String> sqliteStatements() {
        return statements(new SqliteDialect());
    }

    /**
     * 根据指定方言生成建表语句，供启动时迁移。
     */
    public List<String> statements(SqlDialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        List<String> ddl = new ArrayList<>();
        ddl.add(playerIdentities(dialect));
        ddl.add(uniqueIndex("player_identities_uuid", "player_identities", "player_uuid"));
        ddl.add(companies(dialect));
        ddl.add(uniqueIndex("companies_code", "companies", "code"));
        ddl.add(companyMembers(dialect));
        ddl.add(index("company_members_player", "company_members", "player_identity_id"));
        ddl.add(operators(dialect));
        ddl.add(uniqueIndex("operators_code", "operators", "company_id, code"));
        ddl.add(lines(dialect));
        ddl.add(uniqueIndex("lines_code", "lines", "operator_id, code"));
        ddl.add(stations(dialect));
        ddl.add(uniqueIndex("stations_code", "stations", "operator_id, code"));
        ddl.add(routes(dialect));
        ddl.add(uniqueIndex("routes_code", "routes", "line_id, code"));
        ddl.add(routeStops(dialect));
        ddl.add(index("route_stops_route", "route_stops", "route_id"));
        return Collections.unmodifiableList(ddl);
    }

    private String playerIdentities(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    player_uuid %s NOT NULL,
                    name %s NOT NULL,
                    auth_type %s NOT NULL,
                    external_ref %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL
                );
                """.formatted(
                table("player_identities"),
                dialect.uuidType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.timestampType()
        );
    }

    private String companies(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    owner_identity_id %s NOT NULL,
                    status %s NOT NULL,
                    balance_minor %s NOT NULL,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (owner_identity_id) REFERENCES %s(id) ON DELETE RESTRICT
                );
                """.formatted(
                table("companies"),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.bigIntType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.timestampType(),
                table("player_identities")
        );
    }

    private String companyMembers(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    company_id %s NOT NULL,
                    player_identity_id %s NOT NULL,
                    roles %s NOT NULL,
                    joined_at %s NOT NULL,
                    permissions %s,
                    PRIMARY KEY (company_id, player_identity_id),
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_identity_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(
                table("company_members"),
                dialect.uuidType(),
                dialect.uuidType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.jsonType(),
                table("companies"),
                table("player_identities")
        );
    }

    private String operators(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    company_id %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    color_theme %s,
                    priority %s NOT NULL,
                    description %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(
                table("operators"),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.intType(),
                dialect.stringType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.timestampType(),
                table("companies")
        );
    }

    private String lines(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    operator_id %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    service_type %s NOT NULL,
                    color %s,
                    status %s NOT NULL,
                    spawn_freq_baseline_sec %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (operator_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(
                table("lines"),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.intType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.timestampType(),
                table("operators")
        );
    }

    private String stations(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    operator_id %s NOT NULL,
                    primary_line_id %s,
                    name %s NOT NULL,
                    secondary_name %s,
                    world %s,
                    x %s,
                    y %s,
                    z %s,
                    yaw %s,
                    pitch %s,
                    graph_node_id %s,
                    amenities %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (operator_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (primary_line_id) REFERENCES %s(id) ON DELETE SET NULL
                );
                """.formatted(
                table("stations"),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.uuidType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.doubleType(),
                dialect.doubleType(),
                dialect.doubleType(),
                dialect.doubleType(),
                dialect.doubleType(),
                dialect.stringType(),
                dialect.jsonType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.timestampType(),
                table("operators"),
                table("lines")
        );
    }

    private String routes(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id %s PRIMARY KEY,
                    code %s NOT NULL,
                    line_id %s NOT NULL,
                    name %s NOT NULL,
                    secondary_name %s,
                    pattern_type %s NOT NULL,
                    distance_m %s,
                    runtime_secs %s,
                    metadata %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    FOREIGN KEY (line_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(
                table("routes"),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.stringType(),
                dialect.intType(),
                dialect.intType(),
                dialect.jsonType(),
                dialect.timestampType(),
                dialect.timestampType(),
                table("lines")
        );
    }

    private String routeStops(SqlDialect dialect) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    route_id %s NOT NULL,
                    sequence %s NOT NULL,
                    station_id %s,
                    waypoint_node_id %s,
                    dwell_secs %s,
                    pass_type %s NOT NULL,
                    notes %s,
                    PRIMARY KEY (route_id, sequence),
                    FOREIGN KEY (route_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (station_id) REFERENCES %s(id) ON DELETE SET NULL
                );
                """.formatted(
                table("route_stops"),
                dialect.uuidType(),
                dialect.intType(),
                dialect.uuidType(),
                dialect.stringType(),
                dialect.intType(),
                dialect.stringType(),
                dialect.stringType(),
                table("routes"),
                table("stations")
        );
    }

    private String uniqueIndex(String name, String table, String columns) {
        return indexInternal(name, table, columns, true);
    }

    private String index(String name, String table, String columns) {
        return indexInternal(name, table, columns, false);
    }

    private String indexInternal(String name, String table, String columns, boolean unique) {
        Objects.requireNonNull(name, "name");
        String qualifier = unique ? "UNIQUE " : "";
        return "CREATE " + qualifier + "INDEX IF NOT EXISTS " + indexName(name)
                + " ON " + table(table) + " (" + columns + ");";
    }

    private String table(String raw) {
        return tablePrefix + raw;
    }

    private String indexName(String raw) {
        return tablePrefix + raw;
    }
}

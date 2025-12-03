package org.fetarute.fetaruteTCAddon.storage.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 针对 Railway Company 模型的 DDL 生成器，默认生成 SQLite 语句。
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
        List<String> ddl = new ArrayList<>();
        ddl.add(playerIdentities());
        ddl.add(uniqueIndex("player_identities_uuid", "player_identities", "player_uuid"));
        ddl.add(companies());
        ddl.add(uniqueIndex("companies_code", "companies", "code"));
        ddl.add(companyMembers());
        ddl.add(index("company_members_player", "company_members", "player_identity_id"));
        ddl.add(operators());
        ddl.add(uniqueIndex("operators_code", "operators", "company_id, code"));
        ddl.add(lines());
        ddl.add(uniqueIndex("lines_code", "lines", "operator_id, code"));
        ddl.add(stations());
        ddl.add(uniqueIndex("stations_code", "stations", "operator_id, code"));
        ddl.add(routes());
        ddl.add(uniqueIndex("routes_code", "routes", "line_id, code"));
        ddl.add(routeStops());
        ddl.add(index("route_stops_route", "route_stops", "route_id"));
        return Collections.unmodifiableList(ddl);
    }

    private String playerIdentities() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    auth_type TEXT NOT NULL,
                    external_ref TEXT,
                    metadata TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                """.formatted(table("player_identities"));
    }

    private String companies() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    name TEXT NOT NULL,
                    secondary_name TEXT,
                    owner_identity_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    balance_minor INTEGER NOT NULL,
                    metadata TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (owner_identity_id) REFERENCES %s(id) ON DELETE RESTRICT
                );
                """.formatted(table("companies"), table("player_identities"));
    }

    private String companyMembers() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    company_id TEXT NOT NULL,
                    player_identity_id TEXT NOT NULL,
                    roles TEXT NOT NULL,
                    joined_at INTEGER NOT NULL,
                    permissions TEXT,
                    PRIMARY KEY (company_id, player_identity_id),
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_identity_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(table("company_members"), table("companies"), table("player_identities"));
    }

    private String operators() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    company_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    secondary_name TEXT,
                    color_theme TEXT,
                    priority INTEGER NOT NULL,
                    description TEXT,
                    metadata TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (company_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(table("operators"), table("companies"));
    }

    private String lines() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    operator_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    secondary_name TEXT,
                    service_type TEXT NOT NULL,
                    color TEXT,
                    status TEXT NOT NULL,
                    spawn_freq_baseline_sec INTEGER,
                    metadata TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (operator_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(table("lines"), table("operators"));
    }

    private String stations() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    operator_id TEXT NOT NULL,
                    primary_line_id TEXT,
                    name TEXT NOT NULL,
                    secondary_name TEXT,
                    world TEXT,
                    x REAL,
                    y REAL,
                    z REAL,
                    yaw REAL,
                    pitch REAL,
                    graph_node_id TEXT,
                    amenities TEXT,
                    metadata TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (operator_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (primary_line_id) REFERENCES %s(id) ON DELETE SET NULL
                );
                """.formatted(table("stations"), table("operators"), table("lines"));
    }

    private String routes() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    line_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    secondary_name TEXT,
                    pattern_type TEXT NOT NULL,
                    tc_route_id TEXT NOT NULL,
                    distance_m INTEGER,
                    runtime_secs INTEGER,
                    metadata TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (line_id) REFERENCES %s(id) ON DELETE CASCADE
                );
                """.formatted(table("routes"), table("lines"));
    }

    private String routeStops() {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    route_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    station_id TEXT,
                    waypoint_node_id TEXT,
                    dwell_secs INTEGER,
                    pass_type TEXT NOT NULL,
                    notes TEXT,
                    PRIMARY KEY (route_id, sequence),
                    FOREIGN KEY (route_id) REFERENCES %s(id) ON DELETE CASCADE,
                    FOREIGN KEY (station_id) REFERENCES %s(id) ON DELETE SET NULL
                );
                """.formatted(table("route_stops"), table("routes"), table("stations"));
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

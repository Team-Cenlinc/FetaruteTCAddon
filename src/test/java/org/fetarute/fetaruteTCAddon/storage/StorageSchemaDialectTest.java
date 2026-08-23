package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.junit.jupiter.api.Test;

final class StorageSchemaDialectTest {

  @Test
  void sqliteSchemaUsesTablePrefix() {
    StorageSchema schema = new StorageSchema("fta_");
    List<String> ddl = schema.sqliteStatements();
    String routes =
        ddl.stream()
            .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_routes"))
            .findFirst()
            .orElseThrow();
    assertTrue(routes.contains("fta_routes"), "应当带上表前缀");
  }

  @Test
  void schemaDoesNotExposeTcRouteIdColumn() {
    StorageSchema schema = new StorageSchema("fta_");
    String routes =
        schema.sqliteStatements().stream()
            .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_routes"))
            .findFirst()
            .orElseThrow();
    boolean hasTcRouteColumn =
        routes.lines().anyMatch(line -> line.stripLeading().startsWith("tc_route_id"));
    assertTrue(!hasTcRouteColumn, "schema 中不应再包含 tc_route_id");
  }

  @Test
  void schemaIncludesRailEdgeOverridesTable() {
    StorageSchema schema = new StorageSchema("fta_");
    String table =
        schema.sqliteStatements().stream()
            .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_rail_edge_overrides"))
            .findFirst()
            .orElseThrow();
    assertTrue(table.contains("speed_limit_bps"));
    assertTrue(table.contains("temp_speed_limit_bps"));
    assertTrue(table.contains("blocked_manual"));
    assertTrue(table.contains("blocked_until"));
    assertTrue(table.contains("updated_at"));
  }

  @Test
  void railEdgesStayTopologyOnlyAndSparseInterlockingSnapshotUsesDialectText() {
    StorageSchema schema = new StorageSchema("fta_");

    String sqlite = railEdges(schema.sqliteStatements());
    String mysql = railEdges(schema.statements(new MySqlDialect()));
    String sqliteInterlocking = railInterlockingSnapshots(schema.sqliteStatements());
    String mysqlInterlocking = railInterlockingSnapshots(schema.statements(new MySqlDialect()));

    assertFalse(sqlite.contains("footprint_json"));
    assertFalse(mysql.contains("footprint_json"));
    assertTrue(sqliteInterlocking.contains("snapshot_json TEXT NOT NULL"));
    assertTrue(mysqlInterlocking.contains("snapshot_json LONGTEXT NOT NULL"));
  }

  private static String railEdges(List<String> ddl) {
    return ddl.stream()
        .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_rail_edges"))
        .findFirst()
        .orElseThrow();
  }

  private static String railInterlockingSnapshots(List<String> ddl) {
    return ddl.stream()
        .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_rail_interlocking_snapshots"))
        .findFirst()
        .orElseThrow();
  }
}

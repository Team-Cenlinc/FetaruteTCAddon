package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.PostgresDialect;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.junit.jupiter.api.Test;

final class StorageSchemaDialectTest {

  /** 未派出叫车表三种方言都建得出来，列与可空约束一致。 */
  @Test
  void pendingCallsTableRendersForEveryDialect() {
    StorageSchema schema = new StorageSchema("fta_");
    for (List<String> ddl :
        List.of(
            schema.sqliteStatements(),
            schema.statements(new MySqlDialect()),
            schema.statements(new PostgresDialect()))) {
      String table =
          ddl.stream()
              .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_pending_calls"))
              .findFirst()
              .orElseThrow();
      for (String column :
          List.of(
              "id ",
              "player_uuid ",
              "operator_code ",
              "station_code ",
              "screen_platforms ",
              "direction_key ",
              "line_id ",
              "created_at ",
              "eta_seconds ")) {
        assertTrue(table.contains(column), column + " in " + table);
      }
      assertTrue(
          table
              .lines()
              .filter(line -> line.contains("eta_seconds"))
              .noneMatch(line -> line.contains("NOT NULL")),
          "预计到站可空: " + table);
    }
  }

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
  void railEdgesCarryPerEdgeFootprintsAndSparseInterlockingSnapshotUsesDialectText() {
    StorageSchema schema = new StorageSchema("fta_");

    String sqlite = railEdges(schema.sqliteStatements());
    String mysql = railEdges(schema.statements(new MySqlDialect()));
    String sqliteInterlocking = railInterlockingSnapshots(schema.sqliteStatements());
    String mysqlInterlocking = railInterlockingSnapshots(schema.statements(new MySqlDialect()));

    // 逐边足迹**现在必须落库**——这是对早先"rail_edges 保持纯拓扑"那条决定的有意反转。
    //
    // 原决定的实质担忧是"别把整个世界的方块塞进库"，那是合理的；但实测这套线网
    // 511 条边、长度合计 23489 方块，编码后 **0.4–1.1 MB**（库当前 626 KB），远没触到那个担忧。
    //
    // 而不落库的代价是实打实的：足迹只在完整图构建时存在，从快照恢复时 cell→edge 索引必然为空、
    // cellCoverageAvailable() 为假，于是一切以实测覆盖为放行条件的机制（尾部保护释放 / Phase 4）
    // 全部 fail-closed 到一个都不放。实服第十二轮实测 cellCoverageAvailable=false，
    // 而 PROTECTIVE_RETAIN_HOLD 占全网滞留的 **38%**（260 车·分 / 691 车·分，74 分钟一轮）。
    //
    // 为什么放 rail_edges 而不是 snapshot_json：
    //   1. 快照是"全有或全无"——edgeSignature 一旦不匹配整份作废，足迹会连带蒸发；
    //      放在各自的边上则跟着边走，加一条边不会让其余边的足迹消失。
    //   2. 快照是单列大 blob，改任何一条边都要重写整块；逐边是 511 个小值。
    //   3. rail_edges 本就带 length_blocks / base_speed_limit——都是物理量，
    //      足迹是边的物理延展，同类属性放在一起才是规范化的。
    assertTrue(sqlite.contains("footprint_json"), sqlite);
    assertTrue(mysql.contains("footprint_json"), mysql);
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

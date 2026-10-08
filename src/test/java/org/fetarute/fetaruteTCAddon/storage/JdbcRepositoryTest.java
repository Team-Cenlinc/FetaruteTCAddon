package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.InterlockingZoneInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.jdbc.JdbcStorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class JdbcRepositoryTest {

  private static final Path TEST_DB = Path.of("test/data/test.sqlite").toAbsolutePath();
  private StorageManager manager;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = TEST_DB.getParent();
    Files.createDirectories(dir);
    // 清理旧的 sqlite 文件，避免之前按用例命名的残留文件干扰检查
    try (var stream = Files.list(dir)) {
      stream
          .filter(path -> path.getFileName().toString().endsWith(".sqlite"))
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ex) {
                  throw new IllegalStateException("无法清理测试数据库文件: " + path, ex);
                }
              });
    }
    // 本轮测试用的 DB 在结束后保留便于手动检查
  }

  @AfterEach
  void tearDown() {
    if (manager != null) {
      manager.shutdown();
    }
  }

  @Test
  void shouldPersistPlayerIdentity() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository repository = provider.playerIdentities();

    UUID id = UUID.randomUUID();
    UUID playerUuid = UUID.randomUUID();
    Instant now = Instant.now();
    PlayerIdentity identity =
        new PlayerIdentity(
            id,
            playerUuid,
            "Steve",
            IdentityAuthType.ONLINE,
            Optional.of("ext"),
            Map.of("region", "CN"),
            now,
            now);

    repository.save(identity);

    PlayerIdentity loaded = repository.findById(id).orElseThrow();
    assertEquals(identity.id(), loaded.id());
    assertEquals(identity.playerUuid(), loaded.playerUuid());
    assertEquals("Steve", loaded.name());
    assertEquals("CN", loaded.metadata().get("region"));
    assertTrue(repository.findByPlayerUuid(playerUuid).isPresent());
  }

  @Test
  void shouldPersistCompany() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();

    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    PlayerIdentity identity =
        new PlayerIdentity(
            ownerId,
            UUID.randomUUID(),
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    playerRepo.save(identity);

    Company company =
        new Company(
            UUID.randomUUID(),
            "FTA",
            "Fetarute Transit",
            Optional.of("FTA Co."),
            ownerId,
            CompanyStatus.ACTIVE,
            1_000_000L,
            Map.of("tier", "A"),
            now,
            now);

    companyRepo.save(company);

    Company loaded = companyRepo.findByCode("FTA").orElseThrow();
    assertEquals(company.id(), loaded.id());
    assertEquals(ownerId, loaded.ownerIdentityId());
    assertEquals("A", loaded.metadata().get("tier"));
    assertFalse(companyRepo.listByOwner(ownerId).isEmpty());
  }

  @Test
  void shouldSupportRepositoriesInsideTransaction() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();

    UUID ownerId = UUID.randomUUID();
    UUID ownerPlayerUuid = UUID.randomUUID();
    Instant now = Instant.now();

    PlayerIdentity identity =
        new PlayerIdentity(
            ownerId,
            ownerPlayerUuid,
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now);

    Company company =
        new Company(
            UUID.randomUUID(),
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            1_000_000L,
            Map.of(),
            now,
            now);

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () ->
            provider
                .transactionManager()
                .execute(
                    () -> {
                      playerRepo.save(identity);
                      companyRepo.save(company);
                      return null;
                    }));

    assertTrue(playerRepo.findByPlayerUuid(ownerPlayerUuid).isPresent());
    assertTrue(companyRepo.findByCode("FTA").isPresent());
  }

  @Test
  void shouldPersistCompanyMembersAndEnforceForeignKeys() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();
    CompanyMemberRepository memberRepo = provider.companyMembers();

    UUID ownerId = UUID.randomUUID();
    UUID ownerPlayerUuid = UUID.randomUUID();
    Instant now = Instant.now();
    playerRepo.save(
        new PlayerIdentity(
            ownerId,
            ownerPlayerUuid,
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now));

    UUID companyId = UUID.randomUUID();
    companyRepo.save(
        new Company(
            companyId,
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now));

    CompanyMember member =
        new CompanyMember(
            companyId,
            ownerId,
            EnumSet.of(MemberRole.OWNER),
            now,
            Optional.of(Map.of("can_dispatch", true)));
    memberRepo.save(member);

    CompanyMember loaded = memberRepo.findMembership(companyId, ownerId).orElseThrow();
    assertEquals(companyId, loaded.companyId());
    assertEquals(ownerId, loaded.playerIdentityId());
    assertTrue(loaded.roles().contains(MemberRole.OWNER));
    assertEquals(true, loaded.permissions().orElseThrow().get("can_dispatch"));
    assertFalse(memberRepo.listMembers(companyId).isEmpty());
    assertFalse(memberRepo.listMemberships(ownerId).isEmpty());

    CompanyMember invalid =
        new CompanyMember(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(MemberRole.VIEWER), now, Optional.empty());
    assertThrows(StorageException.class, () -> memberRepo.save(invalid));
  }

  @Test
  void shouldPersistOperator() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();
    OperatorRepository operatorRepo = provider.operators();

    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    playerRepo.save(
        new PlayerIdentity(
            ownerId,
            UUID.randomUUID(),
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now));

    UUID companyId = UUID.randomUUID();
    companyRepo.save(
        new Company(
            companyId,
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now));

    Operator operator =
        new Operator(
            UUID.randomUUID(),
            "SURN",
            companyId,
            "Sunrail",
            Optional.of("SR"),
            Optional.of("dark_aqua"),
            10,
            Optional.of("desc"),
            Map.of("tier", "A"),
            now,
            now);
    operatorRepo.save(operator);

    Operator loaded = operatorRepo.findById(operator.id()).orElseThrow();
    assertEquals(operator.code(), loaded.code());
    assertEquals(operator.companyId(), loaded.companyId());
    assertEquals(10, loaded.priority());
    assertEquals("A", loaded.metadata().get("tier"));

    assertTrue(operatorRepo.findByCompanyAndCode(companyId, "SURN").isPresent());
    assertFalse(operatorRepo.listByCompany(companyId).isEmpty());
  }

  /**
   * 兼容 SQLite “弱类型”导致的历史脏数据：INTEGER 列可能被写入 TEXT/空字符串。
   *
   * <p>该测试模拟将若干数值列改写为 {@code ""}，确保仓库读取时不会抛出 driver 的类型异常。
   */
  @Test
  void shouldCoerceNullableIntegerColumnsInSQLite() throws Exception {
    StorageProvider provider = setupProvider(TEST_DB);
    assertTrue(provider instanceof JdbcStorageProvider);
    JdbcStorageProvider jdbcProvider = (JdbcStorageProvider) provider;

    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();
    OperatorRepository operatorRepo = provider.operators();
    LineRepository lineRepo = provider.lines();
    StationRepository stationRepo = provider.stations();
    RouteRepository routeRepo = provider.routes();
    RouteStopRepository stopRepo = provider.routeStops();

    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    playerRepo.save(
        new PlayerIdentity(
            ownerId,
            UUID.randomUUID(),
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now));

    UUID companyId = UUID.randomUUID();
    companyRepo.save(
        new Company(
            companyId,
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now));

    Operator operator =
        new Operator(
            UUID.randomUUID(),
            "SURN",
            companyId,
            "Sunrail",
            Optional.empty(),
            Optional.empty(),
            10,
            Optional.empty(),
            Map.of(),
            now,
            now);
    operatorRepo.save(operator);

    Line line =
        new Line(
            UUID.randomUUID(),
            "LT",
            operator.id(),
            "Line Test",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(60),
            Map.of(),
            now,
            now);
    lineRepo.save(line);

    Station station =
        new Station(
            UUID.randomUUID(),
            "LVT",
            operator.id(),
            Optional.of(line.id()),
            "Liverpool",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Map.of(),
            now,
            now);
    stationRepo.save(station);

    Route route =
        new Route(
            UUID.randomUUID(),
            "EXP-01",
            line.id(),
            "Express",
            Optional.empty(),
            RoutePatternType.EXPRESS,
            RouteOperationType.OPERATION,
            Optional.of(10_000),
            Optional.of(600),
            Map.of(),
            now,
            now);
    routeRepo.save(route);

    stopRepo.save(
        new RouteStop(
            route.id(),
            1,
            Optional.of(station.id()),
            Optional.empty(),
            Optional.of(30),
            RouteStopPassType.STOP,
            Optional.empty()));

    // 模拟脏数据：把 INTEGER 列写成空字符串（SQLite 会接受但 driver 读取时可能报错）。
    try (var connection = jdbcProvider.dataSource().getConnection()) {
      try (var ps =
          connection.prepareStatement(
              "UPDATE fta_lines SET spawn_freq_baseline_sec = ? WHERE id = ?")) {
        ps.setString(1, "");
        ps.setString(2, line.id().toString());
        ps.executeUpdate();
      }
      try (var ps =
          connection.prepareStatement("UPDATE fta_routes SET distance_m = ? WHERE id = ?")) {
        ps.setString(1, "");
        ps.setString(2, route.id().toString());
        ps.executeUpdate();
      }
      try (var ps =
          connection.prepareStatement("UPDATE fta_routes SET runtime_secs = ? WHERE id = ?")) {
        ps.setString(1, "");
        ps.setString(2, route.id().toString());
        ps.executeUpdate();
      }
      try (var ps =
          connection.prepareStatement(
              "UPDATE fta_route_stops SET dwell_secs = ? WHERE route_id = ? AND sequence = ?")) {
        ps.setString(1, "");
        ps.setString(2, route.id().toString());
        ps.setInt(3, 1);
        ps.executeUpdate();
      }
    }

    assertDoesNotThrow(() -> lineRepo.listByOperator(operator.id()));
    assertTrue(lineRepo.findById(line.id()).orElseThrow().spawnFreqBaselineSec().isEmpty());

    Route loadedRoute = routeRepo.findById(route.id()).orElseThrow();
    assertTrue(loadedRoute.distanceMeters().isEmpty());
    assertTrue(loadedRoute.runtimeSeconds().isEmpty());

    RouteStop loadedStop = stopRepo.listByRoute(route.id()).get(0);
    assertTrue(loadedStop.dwellSeconds().isEmpty());
  }

  @Test
  void shouldMigrateLegacyRoutePatternType() throws Exception {
    // 第一次启动：创建 schema 并写入一条带旧值的 routes 记录
    Path dbFile = TEST_DB;
    StorageProvider provider = setupProvider(dbFile);
    assertTrue(provider instanceof JdbcStorageProvider);
    JdbcStorageProvider jdbcProvider = (JdbcStorageProvider) provider;

    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();
    OperatorRepository operatorRepo = provider.operators();
    LineRepository lineRepo = provider.lines();
    RouteRepository routeRepo = provider.routes();

    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    playerRepo.save(
        new PlayerIdentity(
            ownerId,
            UUID.randomUUID(),
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now));

    UUID companyId = UUID.randomUUID();
    companyRepo.save(
        new Company(
            companyId,
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now));

    Operator operator =
        new Operator(
            UUID.randomUUID(),
            "SURN",
            companyId,
            "Sunrail",
            Optional.empty(),
            Optional.empty(),
            10,
            Optional.empty(),
            Map.of(),
            now,
            now);
    operatorRepo.save(operator);

    Line line =
        new Line(
            UUID.randomUUID(),
            "LT",
            operator.id(),
            "Line Test",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    lineRepo.save(line);

    Route route =
        new Route(
            UUID.randomUUID(),
            "EXP-01",
            line.id(),
            "Express",
            Optional.empty(),
            RoutePatternType.RAPID,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);
    routeRepo.save(route);

    // 注入旧值（模拟历史库）：SEMI_EXPRESS
    try (var connection = jdbcProvider.dataSource().getConnection();
        var ps =
            connection.prepareStatement("UPDATE fta_routes SET pattern_type = ? WHERE id = ?")) {
      ps.setString(1, "SEMI_EXPRESS");
      ps.setString(2, route.id().toString());
      ps.executeUpdate();
    }

    // 第二次启动：触发 StorageManager.apply()，执行兼容性迁移
    manager.shutdown();
    manager = null;
    StorageProvider provider2 = setupProvider(dbFile);
    Route loaded = provider2.routes().findById(route.id()).orElseThrow();
    assertEquals(RoutePatternType.RAPID, loaded.patternType());
  }

  @Test
  void shouldMigrateRailGraphSnapshotNodeSignatureColumn() throws Exception {
    Path dbFile = Path.of("test/data/migration-rail-graph.sqlite").toAbsolutePath();
    UUID worldId = UUID.randomUUID();

    // 旧版快照表：缺少 node_signature 列
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS fta_rail_graph_snapshots ("
              + "world_id TEXT PRIMARY KEY,"
              + "built_at INTEGER NOT NULL,"
              + "node_count INTEGER NOT NULL,"
              + "edge_count INTEGER NOT NULL"
              + ");");
      try (var ps =
          connection.prepareStatement(
              "INSERT INTO fta_rail_graph_snapshots (world_id, built_at, node_count, edge_count) VALUES (?, ?, ?, ?)")) {
        ps.setString(1, worldId.toString());
        ps.setLong(2, 0);
        ps.setInt(3, 0);
        ps.setInt(4, 0);
        ps.executeUpdate();
      }
    }

    StorageProvider provider = setupProvider(dbFile);
    var snapshot = provider.railGraphSnapshots().findByWorld(worldId).orElseThrow();
    assertEquals("", snapshot.nodeSignature());
  }

  @Test
  void shouldMigratePidsScreensWithoutPageLayoutsColumn() throws Exception {
    Path dbFile = Path.of("test/data/migration-pids-screens.sqlite").toAbsolutePath();
    UUID screenId = UUID.randomUUID();

    // 旧版站台屏表：缺少 page_layouts 列
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS fta_pids_screens ("
              + "id TEXT PRIMARY KEY, world_id TEXT NOT NULL, x INTEGER NOT NULL,"
              + " y INTEGER NOT NULL, z INTEGER NOT NULL, facing TEXT NOT NULL,"
              + " tile_rows INTEGER NOT NULL, tile_cols INTEGER NOT NULL, layout_id TEXT NOT NULL,"
              + " operator_code TEXT, station_code TEXT, platforms TEXT, line_codes TEXT,"
              + " appearance TEXT NOT NULL, mode TEXT NOT NULL, created_at INTEGER NOT NULL,"
              + " updated_at INTEGER NOT NULL, UNIQUE (world_id, x, y, z, facing));");
      try (var ps =
          connection.prepareStatement(
              "INSERT INTO fta_pids_screens (id, world_id, x, y, z, facing, tile_rows,"
                  + " tile_cols, layout_id, appearance, mode, created_at, updated_at)"
                  + " VALUES (?, ?, 0, 64, 0, 'SOUTH', 3, 5, 'station-3x5', 'AUTO', 'LIVE', 0, 0)")) {
        ps.setString(1, screenId.toString());
        ps.setString(2, UUID.randomUUID().toString());
        ps.executeUpdate();
      }
    }

    StorageProvider provider = setupProvider(dbFile);
    var screen = provider.pidsScreens().findById(screenId).orElseThrow();
    assertEquals(List.of("station-3x5"), screen.layoutIds());

    var combined = screen.withLayouts(List.of("station-3x5", "status-3x5"), Instant.EPOCH);
    provider.pidsScreens().save(combined);
    assertEquals(
        List.of("status-3x5"),
        provider.pidsScreens().findById(screenId).orElseThrow().pageLayoutIds());

    // 不认识 page_layouts 的旧版本改了主布局：清单第一项对不上，不再沿用旧的翻页
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE fta_pids_screens SET layout_id = 'custom-3x5'");
    }
    assertEquals(
        List.of("custom-3x5"), provider.pidsScreens().findById(screenId).orElseThrow().layoutIds());

    // 手改坏的 JSON 与空白主布局只影响这一块，整表照常读入
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE fta_pids_screens SET layout_id = ' ', page_layouts = 'station-3x5'");
    }
    assertEquals(List.of(""), provider.pidsScreens().listAll().get(0).layoutIds());
  }

  @Test
  void shouldLoadLegacyRailEdgesWithoutFootprintColumn() throws Exception {
    Path dbFile = Path.of("test/data/migration-rail-edge-topology.sqlite").toAbsolutePath();
    UUID worldId = UUID.randomUUID();

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE fta_rail_edges ("
              + "world_id TEXT NOT NULL,"
              + "node_a TEXT NOT NULL,"
              + "node_b TEXT NOT NULL,"
              + "length_blocks INTEGER NOT NULL,"
              + "base_speed_limit REAL NOT NULL,"
              + "bidirectional INTEGER NOT NULL,"
              + "PRIMARY KEY (world_id, node_a, node_b)"
              + ");");
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO fta_rail_edges VALUES (?, 'A', 'B', 14, 0.0, 1)")) {
        insert.setString(1, worldId.toString());
        insert.executeUpdate();
      }
    }

    StorageProvider provider = setupProvider(dbFile);

    RailEdgeRecord loaded = provider.railEdges().listByWorld(worldId).get(0);
    assertEquals(worldId, loaded.worldId());
    assertEquals(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")), loaded.edgeId());
    assertEquals(14, loaded.lengthBlocks());
    assertEquals(0.0, loaded.baseSpeedLimit(), 1e-9);
    assertTrue(loaded.bidirectional());
  }

  /**
   * 旧库里 {@code footprint_json} **列已存在、可空、值全是 NULL** —— 这是用户实服库的真实形状。
   *
   * <p>该列是更早一版 schema 的遗留（当时被有意移除，只留下了列）。实测用户库： {@code footprint_json TEXT}（无 NOT NULL、无
   * DEFAULT），511 行全为 NULL。
   *
   * <p>两处必须成立，否则上线即炸：
   *
   * <ul>
   *   <li>兼容性迁移的 {@code ADD COLUMN} 会失败——SQLite 原文是 {@code duplicate column name: footprint_json}，含
   *       "duplicate"，必须被静默容忍；
   *   <li>读到 NULL 必须解成**空足迹**而不是抛出——于是 cell→edge 索引不可用、调用方 fail-closed， 行为与升级前一字不差，直到用户跑过一次 {@code
   *       /fta graph build} 把足迹写进去。
   * </ul>
   */
  @Test
  void shouldLoadLegacyRailEdgesWithNullableFootprintColumnHoldingNulls() throws Exception {
    Path dbFile = Path.of("test/data/migration-rail-edge-null-footprint.sqlite").toAbsolutePath();
    UUID worldId = UUID.randomUUID();

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS fta_rail_edges;");
      // 逐字照搬用户实服库的列定义：可空、无 DEFAULT。
      statement.execute(
          "CREATE TABLE fta_rail_edges ("
              + "world_id TEXT NOT NULL,"
              + "node_a TEXT NOT NULL,"
              + "node_b TEXT NOT NULL,"
              + "length_blocks INTEGER NOT NULL,"
              + "base_speed_limit REAL NOT NULL,"
              + "bidirectional INTEGER NOT NULL, footprint_json TEXT,"
              + "PRIMARY KEY (world_id, node_a, node_b)"
              + ");");
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO fta_rail_edges VALUES (?, 'A', 'B', 14, 0.0, 1, NULL)")) {
        insert.setString(1, worldId.toString());
        insert.executeUpdate();
      }
    }

    // setupProvider 会跑 schema + 兼容性迁移；ADD COLUMN 必然撞 duplicate，必须不抛。
    StorageProvider provider = setupProvider(dbFile);

    RailEdgeRecord loaded = provider.railEdges().listByWorld(worldId).get(0);
    assertEquals(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")), loaded.edgeId());
    assertEquals(14, loaded.lengthBlocks());
    assertTrue(loaded.footprintCells().isEmpty(), "NULL 必须解成空足迹（= 无从判断），而不是抛出或伪造出覆盖");
  }

  @Test
  void shouldPersistRailEdgeOverrides() {
    StorageProvider provider = setupProvider(TEST_DB);
    RailEdgeOverrideRepository repository = provider.railEdgeOverrides();

    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord record =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId,
            OptionalDouble.of(8.0),
            OptionalDouble.of(4.0),
            Optional.of(now.plusSeconds(60)),
            false,
            Optional.empty(),
            now);

    repository.upsert(record);

    RailEdgeOverrideRecord loaded = repository.findByEdge(worldId, edgeId).orElseThrow();
    assertEquals(worldId, loaded.worldId());
    assertEquals(edgeId, loaded.edgeId());
    assertEquals(8.0, loaded.speedLimitBlocksPerSecond().getAsDouble(), 1e-9);
    assertEquals(4.0, loaded.tempSpeedLimitBlocksPerSecond().getAsDouble(), 1e-9);
    assertEquals(record.tempSpeedLimitUntil(), loaded.tempSpeedLimitUntil());
    assertFalse(loaded.blockedManual());

    assertEquals(1, repository.listByWorld(worldId).size());

    repository.delete(worldId, edgeId);
    assertTrue(repository.findByEdge(worldId, edgeId).isEmpty());
  }

  @Test
  void shouldPersistRailEdgeTopology() {
    StorageProvider provider = setupProvider(TEST_DB);
    RailEdgeRepository repository = provider.railEdges();
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));

    repository.replaceWorld(worldId, List.of(new RailEdgeRecord(worldId, edgeId, 24, 8.5, true)));

    RailEdgeRecord loaded = repository.listByWorld(worldId).get(0);
    assertEquals(worldId, loaded.worldId());
    assertEquals(edgeId, loaded.edgeId());
    assertEquals(24, loaded.lengthBlocks());
    assertEquals(8.5, loaded.baseSpeedLimit(), 1e-9);
    assertTrue(loaded.bidirectional());
  }

  @Test
  void shouldReplaceAndLoadSparseRailInterlockingSnapshot() {
    StorageProvider provider = setupProvider(TEST_DB);
    UUID worldId = UUID.randomUUID();
    EdgeId first = EdgeId.undirected(NodeId.of("MT-W"), NodeId.of("MT-E"));
    EdgeId second = EdgeId.undirected(NodeId.of("DS-N"), NodeId.of("DS-S"));
    RailFootprintCell crossing = new RailFootprintCell(10, 64, 10);
    InterlockingZoneInfo zone =
        new InterlockingZoneInfo("interlocking:stable-zone", first, second, Set.of(crossing));
    RailInterlockingSnapshotRecord snapshot =
        new RailInterlockingSnapshotRecord(
            worldId,
            RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION,
            "edge-signature",
            new RailInterlockingCoverage(2, 2, true),
            Map.of(zone.zoneKey(), zone));

    provider.railInterlockingSnapshots().save(snapshot);

    assertEquals(snapshot, provider.railInterlockingSnapshots().findByWorld(worldId).orElseThrow());
    provider.railInterlockingSnapshots().delete(worldId);
    assertTrue(provider.railInterlockingSnapshots().findByWorld(worldId).isEmpty());
  }

  @Test
  void shouldIgnoreLegacyFootprintColumnWhenLoadingRailEdgeTopology() throws Exception {
    Path dbFile = Path.of("test/data/migration-rail-edge-extra-footprint.sqlite").toAbsolutePath();
    UUID worldId = UUID.randomUUID();
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE fta_rail_edges ("
              + "world_id TEXT NOT NULL,"
              + "node_a TEXT NOT NULL,"
              + "node_b TEXT NOT NULL,"
              + "length_blocks INTEGER NOT NULL,"
              + "base_speed_limit REAL NOT NULL,"
              + "bidirectional INTEGER NOT NULL,"
              + "footprint_json TEXT,"
              + "PRIMARY KEY (world_id, node_a, node_b)"
              + ");");
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO fta_rail_edges "
                  + "(world_id, node_a, node_b, length_blocks, base_speed_limit, bidirectional, footprint_json) "
                  + "VALUES (?, 'A', 'B', 12, 7.25, 1, '{not-json')")) {
        insert.setString(1, worldId.toString());
        insert.executeUpdate();
      }
    }

    StorageProvider provider = setupProvider(dbFile);

    RailEdgeRecord loaded = provider.railEdges().listByWorld(worldId).get(0);

    assertEquals(worldId, loaded.worldId());
    assertEquals(edgeId, loaded.edgeId());
    assertEquals(12, loaded.lengthBlocks());
    assertEquals(7.25, loaded.baseSpeedLimit(), 1e-9);
    assertTrue(loaded.bidirectional());
  }

  @Test
  void shouldNormalizeEdgeIdAndPersistBlockedFields() {
    StorageProvider provider = setupProvider(TEST_DB);
    RailEdgeOverrideRepository repository = provider.railEdgeOverrides();

    UUID worldId = UUID.randomUUID();
    EdgeId raw = new EdgeId(NodeId.of("B"), NodeId.of("A"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord record =
        new RailEdgeOverrideRecord(
            worldId,
            raw,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.of(now.plusSeconds(120)),
            now);

    repository.upsert(record);

    EdgeId canonical = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailEdgeOverrideRecord loaded = repository.findByEdge(worldId, canonical).orElseThrow();
    assertEquals("A", loaded.edgeId().a().value());
    assertEquals("B", loaded.edgeId().b().value());
    assertTrue(loaded.blockedManual());
    assertEquals(record.blockedUntil(), loaded.blockedUntil());

    repository.deleteWorld(worldId);
    assertTrue(repository.listByWorld(worldId).isEmpty());
  }

  /**
   * 时刻表往返：表头、各 route 的时分档案（JSON 列）、发车表与车辆交路必须整体一致。
   *
   * <p>特别钉住两件事：
   *
   * <ul>
   *   <li><b>时区</b>以文本存，读回来必须还是同一个 {@code ZoneId}。存错了不会报错，只会让整张表的时刻
   *       整体平移几个小时，而那种偏移在现场看起来像"调度突然全线晚点"。
   *   <li><b>车辆交路的回库端点</b>必须原样带回。它是"每辆车最终都会回库"这条不变量的物理落点， 在存储层丢掉等于这条不变量只在内存里成立。
   * </ul>
   */
  @Test
  void shouldPersistTimetableWithRoutePlansTripsAndDuties() {
    StorageProvider provider = setupProvider(TEST_DB);
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    UUID tripId = UUID.randomUUID();
    UUID dutyId = UUID.randomUUID();
    UUID createRouteId = UUID.randomUUID();
    UUID returnRouteId = UUID.randomUUID();

    Timetable timetable =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT1",
            "测试表",
            TimetableStatus.DRAFT,
            java.time.ZoneId.of("Asia/Shanghai"),
            5 * 3600,
            23 * 3600,
            List.of(
                new TimetableRoutePlan(
                    fixture.routeId(),
                    "TTR",
                    5,
                    List.of(
                        new TimetableStop(
                            0,
                            Optional.of("AAA"),
                            Optional.of("OP:S:AAA:1"),
                            0,
                            0,
                            RouteStopPassType.STOP),
                        new TimetableStop(
                            1,
                            Optional.of("BBB"),
                            Optional.of("OP:S:BBB:1"),
                            100,
                            130,
                            RouteStopPassType.STOP)),
                    "OP:S:AAA:1",
                    "OP:S:BBB:1",
                    Optional.of("OP:D:DEP:1"),
                    Optional.empty()),
                new TimetableRoutePlan(
                    returnRouteId,
                    "TTRET",
                    RouteOperationType.RETURN,
                    7,
                    List.of(
                        new TimetableStop(
                            0,
                            Optional.of("BBB"),
                            Optional.of("OP:S:BBB:1"),
                            0,
                            0,
                            RouteStopPassType.STOP),
                        new TimetableStop(
                            1,
                            Optional.empty(),
                            Optional.of("OP:D:DEP:1"),
                            40,
                            40,
                            RouteStopPassType.PASS)),
                    "OP:S:BBB:1",
                    "OP:D:DEP:1",
                    Optional.empty(),
                    Optional.empty())),
            List.of(
                new TimetableTrip(
                    tripId,
                    timetableId,
                    fixture.routeId(),
                    0,
                    "TTR-001",
                    8 * 3600,
                    Optional.of(dutyId))),
            List.of(
                new VehicleDuty(
                    dutyId,
                    timetableId,
                    0,
                    "D001",
                    "OP:D:DEP:1",
                    "OP:D:DEP:1",
                    Optional.of(createRouteId),
                    Optional.of(returnRouteId),
                    List.of(tripId),
                    8 * 3600 - 300,
                    8 * 3600 + 400,
                    8 * 3600 + 900,
                    VehicleDuty.CloseReason.MAX_TRIPS)),
            Optional.of("备注"),
            now,
            now);

    provider.timetables().save(timetable);
    Timetable loaded = provider.timetables().findById(timetableId).orElseThrow();

    assertEquals("TT1", loaded.code());
    assertEquals(TimetableStatus.DRAFT, loaded.status());
    assertEquals("Asia/Shanghai", loaded.zoneId().getId());
    assertEquals(5 * 3600, loaded.serviceStartSecondOfDay());
    assertEquals(2, loaded.routePlans().size());
    assertEquals(5, loaded.routePlans().get(0).weight());
    assertEquals(RouteOperationType.OPERATION, loaded.routePlans().get(0).kind());
    assertEquals(RouteOperationType.RETURN, loaded.routePlans().get(1).kind());
    assertEquals(0, loaded.routePlans().get(1).weight(), "非运营线路的 weight 恒为 0");
    assertEquals(130, loaded.routePlans().get(0).stops().get(1).departureOffsetSeconds());
    assertEquals(RouteStopPassType.STOP, loaded.routePlans().get(0).stops().get(1).passType());
    assertEquals(
        RouteStopPassType.PASS,
        loaded.routePlans().get(1).stops().get(1).passType(),
        "停车方式随 route_plans 落库：回库段的车库是通过点");
    assertEquals(Optional.of("OP:D:DEP:1"), loaded.routePlans().get(0).depotNodeId());
    assertEquals(1, loaded.trips().size());
    assertEquals("TTR-001", loaded.trips().get(0).tripCode());
    assertEquals(Optional.of(dutyId), loaded.trips().get(0).dutyId());
    assertEquals(1, loaded.duties().size());
    assertEquals("OP:D:DEP:1", loaded.duties().get(0).endDepotNodeId());
    assertEquals(Optional.of(createRouteId), loaded.duties().get(0).createRouteId());
    assertEquals(Optional.of(returnRouteId), loaded.duties().get(0).returnRouteId());
    assertEquals(8 * 3600 - 300, loaded.duties().get(0).plannedStartSecondOfDay(), "出库可早于服务日");
    assertEquals(8 * 3600 + 400, loaded.duties().get(0).returnSecondOfDay());
    assertEquals(VehicleDuty.CloseReason.MAX_TRIPS, loaded.duties().get(0).closeReason());
    assertEquals(List.of(tripId), loaded.duties().get(0).tripIds());
    assertEquals(Optional.of("备注"), loaded.notes());

    // 只有 PUBLISHED 才进运行时视图；翻状态只改表头，不碰车次与交路。
    assertTrue(provider.timetables().listPublished().isEmpty());
    Instant publishedAt = now.plusSeconds(30);
    assertTrue(
        provider.timetables().updateStatus(timetableId, TimetableStatus.PUBLISHED, publishedAt));
    List<Timetable> published = provider.timetables().listPublished();
    assertEquals(1, published.size());
    assertEquals(publishedAt, published.get(0).updatedAt());
    assertEquals(loaded.trips(), published.get(0).trips());
    assertEquals(loaded.duties(), published.get(0).duties());
    assertFalse(
        provider.timetables().updateStatus(UUID.randomUUID(), TimetableStatus.PUBLISHED, now),
        "没有这份表时报告未找到");

    // 重新保存必须整体替换子表，而不是累加。
    provider
        .timetables()
        .save(
            provider
                .timetables()
                .findById(timetableId)
                .orElseThrow()
                .withTripsAndDuties(List.of(), List.of()));
    Timetable emptied = provider.timetables().findById(timetableId).orElseThrow();
    assertTrue(emptied.trips().isEmpty());
    assertTrue(emptied.duties().isEmpty());

    provider.timetables().delete(timetableId);
    assertTrue(provider.timetables().findById(timetableId).isEmpty());
  }

  /** 外方走行线路的标记随 route_plans 的 JSON 落库、读回；旧 JSON 没有这个字段时按 false。 */
  @Test
  void shouldRoundTripExternalRoutePlanFlag() {
    StorageProvider provider = setupProvider(TEST_DB);
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    UUID foreignRoute = UUID.randomUUID();
    Timetable timetable =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT3",
            "直通表",
            TimetableStatus.DRAFT,
            java.time.ZoneId.of("UTC"),
            5 * 3600,
            23 * 3600,
            List.of(
                new TimetableRoutePlan(
                    fixture.routeId(),
                    "RA",
                    RouteOperationType.OPERATION,
                    1,
                    List.of(),
                    "OP:S:A:1",
                    "CHT:S:X:1",
                    Optional.empty(),
                    Optional.empty()),
                new TimetableRoutePlan(
                    foreignRoute,
                    "NL-RET",
                    RouteOperationType.RETURN,
                    0,
                    List.of(),
                    "CHT:S:X:1",
                    "CHT:D:DEP2:1",
                    Optional.empty(),
                    Optional.empty(),
                    true)),
            List.of(),
            List.of(),
            Optional.empty(),
            now,
            now);

    provider.timetables().save(timetable);
    Timetable loaded = provider.timetables().findById(timetableId).orElseThrow();

    assertFalse(loaded.routePlan(fixture.routeId()).orElseThrow().external());
    assertTrue(loaded.routePlan(foreignRoute).orElseThrow().external());
    assertEquals(List.of(fixture.routeId()), loaded.managedRouteIds());
  }

  /** 1.5.0 之前落库的 route_plans 没有停车方式：按当时对外的口径回推（首末站与停站大于 0 秒的点算停车）， 停站 0 秒的中途点当作通过——重新发布后才按交路定义。 */
  @Test
  void legacyRoutePlanWithoutPassTypeFallsBackToDwellHeuristic() throws Exception {
    StorageProvider provider = setupProvider(TEST_DB);
    JdbcStorageProvider jdbcProvider = (JdbcStorageProvider) provider;
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    Timetable timetable =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT4",
            "旧表",
            TimetableStatus.PUBLISHED,
            java.time.ZoneId.of("UTC"),
            5 * 3600,
            23 * 3600,
            List.of(
                new TimetableRoutePlan(
                    fixture.routeId(),
                    "RA",
                    1,
                    List.of(
                        new TimetableStop(
                            0,
                            Optional.of("AAA"),
                            Optional.of("OP:S:AAA:1"),
                            0,
                            0,
                            RouteStopPassType.STOP),
                        new TimetableStop(
                            1,
                            Optional.of("ZZZ"),
                            Optional.of("OP:S:ZZZ:1"),
                            50,
                            50,
                            RouteStopPassType.STOP),
                        new TimetableStop(
                            2,
                            Optional.of("BBB"),
                            Optional.of("OP:S:BBB:1"),
                            100,
                            130,
                            RouteStopPassType.STOP),
                        new TimetableStop(
                            3,
                            Optional.of("CCC"),
                            Optional.of("OP:S:CCC:1"),
                            230,
                            230,
                            RouteStopPassType.TERMINATE)),
                    "OP:S:AAA:1",
                    "OP:S:CCC:1",
                    Optional.empty(),
                    Optional.empty())),
            List.of(),
            List.of(),
            Optional.empty(),
            now,
            now);
    provider.timetables().save(timetable);

    // 抹掉 pass 字段，模拟 1.5.0 之前写入的行。
    try (var connection = jdbcProvider.dataSource().getConnection()) {
      String json;
      try (var ps =
          connection.prepareStatement("SELECT route_plans FROM fta_timetables WHERE id = ?")) {
        ps.setString(1, timetableId.toString());
        try (var rs = ps.executeQuery()) {
          assertTrue(rs.next());
          json = rs.getString(1);
        }
      }
      assertTrue(json.contains("\"pass\""), "新写入的行带停车方式");
      try (var ps =
          connection.prepareStatement("UPDATE fta_timetables SET route_plans = ? WHERE id = ?")) {
        ps.setString(1, json.replaceAll(",\"pass\":\"[A-Z]+\"", ""));
        ps.setString(2, timetableId.toString());
        ps.executeUpdate();
      }
    }

    List<TimetableStop> stops =
        provider.timetables().findById(timetableId).orElseThrow().routePlans().get(0).stops();
    assertEquals(
        List.of(
            RouteStopPassType.STOP,
            RouteStopPassType.PASS,
            RouteStopPassType.STOP,
            RouteStopPassType.STOP),
        stops.stream().map(TimetableStop::passType).toList());
  }

  /** 邻表基线随表落库、整体替换、随表删除：publish 重检靠它判断邻表变没变。 */
  @Test
  void shouldReplaceAndListTimetableBaselines() {
    StorageProvider provider = setupProvider(TEST_DB);
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    Timetable timetable =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT2",
            "基线表",
            TimetableStatus.DRAFT,
            java.time.ZoneId.of("UTC"),
            5 * 3600,
            23 * 3600,
            List.of(),
            List.of(),
            List.of(),
            Optional.empty(),
            now,
            now);
    provider.timetables().save(timetable);
    UUID neighborId = UUID.randomUUID();
    org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline baseline =
        new org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline(
            timetableId, neighborId, "C1/SURC/MT/MT-TT", now.plusSeconds(60), 31, 2, true);

    provider.timetables().replaceBaselines(timetableId, List.of(baseline));
    List<org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableBaseline>
        loaded = provider.timetables().listBaselines(timetableId);

    assertEquals(List.of(baseline), loaded);

    provider.timetables().replaceBaselines(timetableId, List.of());
    assertTrue(provider.timetables().listBaselines(timetableId).isEmpty(), "整体替换：空列表清空基线");

    provider.timetables().replaceBaselines(timetableId, List.of(baseline));
    provider.timetables().save(timetable);
    provider.timetables().updateStatus(timetableId, TimetableStatus.PUBLISHED, now.plusSeconds(1));
    assertEquals(
        List.of(baseline),
        provider.timetables().listBaselines(timetableId),
        "重存表（只重写发车表与交路）与翻状态都不碰基线");

    provider.timetables().delete(timetableId);
    assertTrue(provider.timetables().listBaselines(timetableId).isEmpty(), "删表时基线随之删除");
  }

  /** 计划站台随 build 落库、整体替换：翻发布状态不碰，重存表（车次换了）随之清空，删表随之删除。 */
  @Test
  void shouldReplaceAndListPlatformPlans() {
    StorageProvider provider = setupProvider(TEST_DB);
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    Timetable timetable =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT5",
            "计划站台表",
            TimetableStatus.DRAFT,
            java.time.ZoneId.of("UTC"),
            5 * 3600,
            23 * 3600,
            List.of(),
            List.of(),
            List.of(),
            Optional.empty(),
            now,
            now);
    provider.timetables().save(timetable);
    org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.PlatformPlan plan =
        new org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.PlatformPlan(
            UUID.randomUUID(), 3, "SURC:S:PPK:2");

    provider.timetables().replacePlatformPlans(timetableId, List.of(plan));
    assertEquals(List.of(plan), provider.timetables().listPlatformPlans(timetableId));

    provider.timetables().updateStatus(timetableId, TimetableStatus.PUBLISHED, now.plusSeconds(1));
    assertEquals(List.of(plan), provider.timetables().listPlatformPlans(timetableId), "翻发布状态不碰计划");

    provider.timetables().save(timetable);
    assertTrue(provider.timetables().listPlatformPlans(timetableId).isEmpty(), "重存表换了车次，旧计划随之清空");

    provider.timetables().replacePlatformPlans(timetableId, List.of(plan));
    provider.timetables().delete(timetableId);
    assertTrue(provider.timetables().listPlatformPlans(timetableId).isEmpty(), "删表时计划随之删除");
  }

  /**
   * 一次保存是一个事务：半路失败时整份回滚，库里还是上一版。
   *
   * <p>保存先删旧的车次与交路、再逐行插入。此前每条语句自动提交，插到一半撞上唯一约束时旧车次已经删掉了， 库里留下半份表——有表头没车次的表会让一条线"按表运行"却一趟车都发不出来。
   */
  @Test
  void timetableSaveRollsBackAsAWhole() {
    StorageProvider provider = setupProvider(TEST_DB);
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    Timetable original =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT4",
            "回滚表",
            TimetableStatus.DRAFT,
            java.time.ZoneId.of("UTC"),
            5 * 3600,
            23 * 3600,
            List.of(),
            List.of(trip(timetableId, fixture.routeId(), "TTR-001", 8 * 3600)),
            List.of(),
            Optional.empty(),
            now,
            now);
    provider.timetables().save(original);

    // 两趟车同一个车次号：第二行撞上 (timetable_id, trip_code) 唯一约束。
    Timetable broken =
        original.withTripsAndDuties(
            List.of(
                trip(timetableId, fixture.routeId(), "TTR-002", 9 * 3600),
                trip(timetableId, fixture.routeId(), "TTR-002", 10 * 3600)),
            List.of());
    assertThrows(StorageException.class, () -> provider.timetables().save(broken));

    Timetable reloaded = provider.timetables().findById(timetableId).orElseThrow();
    assertEquals(original.trips(), reloaded.trips(), "失败的保存不能把上一版的车次删掉");
  }

  /** 区分车型的表：同一条 route 的基础计划与车型变体都落库，交路的车型读回原样。 */
  @Test
  void timetableKeepsConsistVariantsAndDutyConsist() {
    StorageProvider provider = setupProvider(TEST_DB);
    TimetableFixture fixture = seedRoute(provider);
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    UUID timetableId = UUID.randomUUID();
    UUID routeId = fixture.routeId();
    List<TimetableStop> slow =
        List.of(
            new TimetableStop(
                0, Optional.of("A"), Optional.of("OP:S:A:1"), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.of("B"), Optional.of("OP:S:B:1"), 90, 90, RouteStopPassType.TERMINATE));
    List<TimetableStop> fast =
        List.of(
            new TimetableStop(
                0, Optional.of("A"), Optional.of("OP:S:A:1"), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.of("B"), Optional.of("OP:S:B:1"), 70, 70, RouteStopPassType.TERMINATE));
    TimetableRoutePlan base =
        new TimetableRoutePlan(
            routeId, "R1", 1, slow, "OP:S:A:1", "OP:S:B:1", Optional.empty(), Optional.empty());
    TimetableRoutePlan six =
        base.asVariant(
                UUID.randomUUID(),
                new TimetableRoutePlan.ConsistVariant("sh_a6", routeId, 70.5, 0.0),
                fast)
            .withRouteId(routeId);
    TimetableTrip trip = trip(timetableId, routeId, "TTR-001", 8 * 3600);
    UUID dutyId = UUID.randomUUID();
    trip =
        new TimetableTrip(
            trip.id(),
            timetableId,
            routeId,
            0,
            trip.tripCode(),
            trip.departureSecondOfDay(),
            Optional.of(dutyId));
    VehicleDuty duty =
        new VehicleDuty(
            dutyId,
            timetableId,
            0,
            "D001",
            "OP:D:DEP:1",
            "OP:D:DEP:1",
            Optional.empty(),
            Optional.empty(),
            List.of(trip.id()),
            8 * 3600 - 60,
            8 * 3600 + 90,
            8 * 3600 + 150,
            VehicleDuty.CloseReason.MAX_TRIPS,
            Optional.of("sh_a6"));
    Timetable timetable =
        new Timetable(
            timetableId,
            fixture.companyId(),
            fixture.operatorId(),
            fixture.lineId(),
            "TT5",
            "车型表",
            TimetableStatus.DRAFT,
            java.time.ZoneId.of("UTC"),
            5 * 3600,
            23 * 3600,
            List.of(base, six),
            List.of(trip),
            List.of(duty),
            Optional.empty(),
            now,
            now);
    provider.timetables().save(timetable);

    Timetable reloaded = provider.timetables().findById(timetableId).orElseThrow();
    assertEquals(List.of(base, six), reloaded.routePlans());
    assertEquals(List.of(duty), reloaded.duties());
    assertEquals(90, reloaded.routePlan(routeId).orElseThrow().totalRunSeconds());
    assertEquals(
        70, reloaded.routePlan(routeId, Optional.of("sh_a6")).orElseThrow().totalRunSeconds());
    assertEquals(Optional.of("sh_a6"), reloaded.consistOf(reloaded.trips().get(0)));
  }

  private static TimetableTrip trip(UUID timetableId, UUID routeId, String code, int departure) {
    return new TimetableTrip(
        UUID.randomUUID(), timetableId, routeId, 0, code, departure, Optional.empty());
  }

  /** 建起一条 company → operator → line → route 的最小链路，满足时刻表的外键。 */
  private TimetableFixture seedRoute(StorageProvider provider) {
    Instant now = Instant.now();
    UUID ownerId = UUID.randomUUID();
    provider
        .playerIdentities()
        .save(
            new PlayerIdentity(
                ownerId,
                UUID.randomUUID(),
                "Owner",
                IdentityAuthType.ONLINE,
                Optional.empty(),
                Map.of(),
                now,
                now));
    UUID companyId = UUID.randomUUID();
    provider
        .companies()
        .save(
            new Company(
                companyId,
                "TTC",
                "Timetable Co",
                Optional.empty(),
                ownerId,
                CompanyStatus.ACTIVE,
                0L,
                Map.of(),
                now,
                now));
    Operator operator =
        new Operator(
            UUID.randomUUID(),
            "TTOP",
            companyId,
            "Timetable Operator",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            now,
            now);
    provider.operators().save(operator);
    Line line =
        new Line(
            UUID.randomUUID(),
            "TTL",
            operator.id(),
            "Timetable Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(300),
            Map.of(),
            now,
            now);
    provider.lines().save(line);
    Route route =
        new Route(
            UUID.randomUUID(),
            "TTR",
            line.id(),
            "Timetable Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.of(5_000),
            Optional.of(300),
            Map.of(),
            now,
            now);
    provider.routes().save(route);
    return new TimetableFixture(companyId, operator.id(), line.id(), route.id());
  }

  private record TimetableFixture(UUID companyId, UUID operatorId, UUID lineId, UUID routeId) {}

  private StorageProvider setupProvider(Path dbFile) {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings(dbFile.toString()),
            Optional.empty(),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000));
    ConfigManager.ConfigView view =
        new ConfigManager.ConfigView(
            10,
            false,
            "zh_CN",
            settings,
            new ConfigManager.GraphSettings(8.0, 6, 2),
            new ConfigManager.AutoStationSettings("BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.2f),
            new ConfigManager.RuntimeSettings(
                10,
                10,
                2,
                1,
                1,
                3,
                4.0,
                6.0,
                3.5,
                true,
                SpeedCurveType.PHYSICS,
                1.0,
                0.0,
                0.2,
                60,
                true,
                true,
                2.0,
                8.0,
                0.15,
                1.0,
                1.0,
                3,
                true,
                10,
                Optional.empty(),
                false,
                10,
                Optional.empty(),
                false,
                10,
                Optional.empty()),
            new ConfigManager.SpawnSettings(false, 20, 200, 1, 5, 5, 40, 10, 2.0),
            new ConfigManager.TrainConfigSettings("emu", Map.of()),
            new ConfigManager.ReclaimSettings(false, 3600L, 100, 60L),
            ConfigManager.HealthSettings.defaults());
    manager = new StorageManager(null, new LoggerManager(Logger.getAnonymousLogger()));
    manager.apply(view);
    return manager.provider().orElseThrow();
  }
}

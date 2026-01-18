package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
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

  private StorageProvider setupProvider(Path dbFile) {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings(dbFile.toString()),
            Optional.empty(),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000));
    ConfigManager.ConfigView view =
        new ConfigManager.ConfigView(
            5,
            false,
            "zh_CN",
            settings,
            new ConfigManager.GraphSettings(8.0),
            new ConfigManager.AutoStationSettings("BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.2f),
            new ConfigManager.RuntimeSettings(
                10, 2, 3, 4.0, 6.0, 3.5, true, SpeedCurveType.PHYSICS, 1.0, 0.0, 0.2, 60, true),
            new ConfigManager.TrainConfigSettings(
                "emu",
                new ConfigManager.TrainTypeSettings(0.8, 1.0),
                new ConfigManager.TrainTypeSettings(0.7, 0.9),
                new ConfigManager.TrainTypeSettings(0.6, 0.8),
                new ConfigManager.TrainTypeSettings(0.9, 1.1)));
    manager = new StorageManager(null, new LoggerManager(Logger.getAnonymousLogger()));
    manager.apply(view);
    return manager.provider().orElseThrow();
  }
}

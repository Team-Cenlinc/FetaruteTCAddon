package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.ConsistArbiter.SpawnChoice;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 车型裁决：按方案出车与改车型、复用只接方案里的车型并按份额排序、按实际车型记账；没绑方案的 route 一切照旧。 */
class ConsistDispatchArbiterTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");

  private final Operator operator = operator();
  private final UUID boundRoute = UUID.randomUUID();
  private final UUID freeRoute = UUID.randomUUID();
  private final Set<String> overLimit = new HashSet<>();
  private final Map<String, String> consistOfTrain = new HashMap<>();
  private ConsistPlanService plans;
  private ConsistDispatchArbiter arbiter;

  @BeforeEach
  void setUp() {
    ConsistPlan plan =
        new ConsistPlan(
            UUID.randomUUID(), operator.id(), "WS", "3 SH_A6\n1 SH_A8 | type=emu", NOW, NOW);
    StorageProvider provider = mock(StorageProvider.class);
    ConsistPlanRepository repository = mock(ConsistPlanRepository.class);
    when(provider.consistPlans()).thenReturn(repository);
    when(repository.listAll()).thenReturn(List.of(plan));
    ConsistInspector inspector =
        new ConsistInspector() {
          @Override
          public ConsistInspection inspect(String pattern) {
            int cars = pattern.endsWith("8") ? 8 : 6;
            return new ConsistInspection(true, cars, cars * 12.0, Map.of(), OptionalInt.empty());
          }

          @Override
          public boolean exceedsSpawnLimit(String pattern) {
            return overLimit.contains(pattern);
          }
        };
    plans =
        new ConsistPlanService(
            () -> Optional.of(provider),
            inspector,
            () -> new ConfigManager.TrainConfigSettings("metro", Map.of()),
            message -> {});
    Map<UUID, RouteDefinitionCache.RouteRecord> records =
        Map.of(
            boundRoute, record(boundRoute, Map.of(ConsistPlanService.ROUTE_METADATA_KEY, "ws")),
            freeRoute, record(freeRoute, Map.of()));
    plans.attachRoutes(id -> Optional.ofNullable(records.get(id)));
    plans.reload();
    arbiter =
        new ConsistDispatchArbiter(
            plans,
            inspector,
            train -> Optional.ofNullable(consistOfTrain.get(train)),
            message -> {});
  }

  @Test
  void spawnsByShareAndWritesTheResolvedType() {
    List<String> spawned = new java.util.ArrayList<>();
    Map<String, Map<String, String>> tagsByConsist = new HashMap<>();
    for (int i = 0; i < 4; i++) {
      SpawnChoice choice = arbiter.chooseSpawn(ticket(boundRoute, false));
      assertEquals(SpawnChoice.Kind.CHOSEN, choice.kind());
      String pattern = choice.pattern().orElseThrow();
      spawned.add(pattern);
      tagsByConsist.put(pattern, choice.tags());
      consistOfTrain.put("T" + i, pattern);
      arbiter.onDispatched(ticket(boundRoute, false), "T" + i);
    }
    assertEquals(List.of("SH_A6", "SH_A6", "SH_A8", "SH_A6"), spawned);
    assertEquals(
        Map.of("FTA_TRAIN_TYPE", "EMU"),
        tagsByConsist.get("SH_A8"),
        "方案覆盖了车种：出车时写车种；加减速没覆盖，沿用车种预设，不写标签");
    assertEquals(
        Map.of("FTA_TRAIN_TYPE", "METRO"), tagsByConsist.get("SH_A6"), "默认车种也写上，控车与估算同一个车种");
  }

  @Test
  void skipsConsistsAtTheirSpawnLimit() {
    overLimit.add("SH_A6");
    assertEquals(Optional.of("SH_A8"), arbiter.chooseSpawn(ticket(boundRoute, false)).pattern());
    overLimit.add("SH_A8");
    assertEquals(SpawnChoice.Kind.BLOCKED, arbiter.chooseSpawn(ticket(boundRoute, false)).kind());
  }

  @Test
  void unboundRouteKeepsTheOldRules() {
    assertEquals(SpawnChoice.legacy(), arbiter.chooseSpawn(ticket(freeRoute, false)));
    List<LayoverRegistry.LayoverCandidate> candidates =
        List.of(candidate("A", "XYZ"), candidate("B", null));
    assertSame(candidates, arbiter.orderReuseCandidates(ticket(freeRoute, false), candidates));
    assertTrue(arbiter.acceptsForRoute(freeRoute, candidate("C", "XYZ")));
  }

  @Test
  void reuseKeepsPlanConsistsOnlyAndPutsTheOwedOneFirst() {
    LayoverRegistry.LayoverCandidate a8 = candidate("A8", "SH_A8");
    LayoverRegistry.LayoverCandidate foreign = candidate("X", "DMU_3");
    LayoverRegistry.LayoverCandidate a6 = candidate("A6", "SH_A6");
    LayoverRegistry.LayoverCandidate a6Later = candidate("A6b", " sh_a6 ");
    LayoverRegistry.LayoverCandidate untagged = candidate("N", null);
    List<LayoverRegistry.LayoverCandidate> arrived = List.of(a8, foreign, a6, a6Later, untagged);

    assertEquals(
        List.of(a6, a6Later, a8),
        arbiter.orderReuseCandidates(ticket(boundRoute, false), arrived),
        "SH_A6 欠得多排前面，同车型保持到达先后；方案外与没有车型标签的车不接");

    consistOfTrain.put("A6", "SH_A6");
    consistOfTrain.put("A6b", "SH_A6");
    arbiter.onDispatched(ticket(boundRoute, false), "A6");
    arbiter.onDispatched(ticket(boundRoute, false), "A6b");
    assertEquals(
        List.of(a8, a6, a6Later),
        arbiter.orderReuseCandidates(ticket(boundRoute, false), arrived),
        "跑了两班 SH_A6 之后轮到 SH_A8");
  }

  @Test
  void timetableTicketsAreNotReordered() {
    List<LayoverRegistry.LayoverCandidate> candidates =
        List.of(candidate("A8", "SH_A8"), candidate("X", "DMU_3"));
    assertSame(candidates, arbiter.orderReuseCandidates(ticket(boundRoute, true), candidates));
  }

  @Test
  void returnRoutesOnlyTakePlanConsists() {
    assertTrue(arbiter.acceptsForRoute(boundRoute, candidate("A8", "SH_A8")));
    assertFalse(arbiter.acceptsForRoute(boundRoute, candidate("X", "DMU_3")));
    assertFalse(arbiter.acceptsForRoute(boundRoute, candidate("N", null)));
  }

  @Test
  void dispatchWithoutConsistTagIsNotCounted() {
    arbiter.onDispatched(ticket(boundRoute, false), "unknown-train");
    assertEquals(Map.of(), plans.counts(boundRoute));
  }

  @Test
  void timetableTicketSpawnsExactlyItsDutyConsist() {
    SpawnTicket eight = ticket(freeRoute, true).withConsist(Optional.of("sh_a8"));
    for (int i = 0; i < 3; i++) {
      SpawnChoice choice = arbiter.chooseSpawn(eight);
      assertEquals(Optional.of("SH_A8"), choice.pattern(), "票上指定的车型，不按份额现排、不看 route 有没有绑方案");
      assertEquals(Map.of("FTA_TRAIN_TYPE", "EMU"), choice.tags());
      consistOfTrain.put("E" + i, "SH_A8");
      arbiter.onDispatched(eight, "E" + i);
    }
    assertTrue(plans.counts(freeRoute).isEmpty(), "表定车型不进间隔发车的份额账");

    overLimit.add("SH_A8");
    SpawnChoice limited = arbiter.chooseSpawn(eight);
    assertEquals(SpawnChoice.Kind.BLOCKED, limited.kind(), "到了出车上限也不改出别的车型");

    SpawnChoice unknown =
        arbiter.chooseSpawn(ticket(boundRoute, true).withConsist(Optional.of("x9")));
    assertEquals(SpawnChoice.Kind.BLOCKED, unknown.kind(), "方案里已经没有这个车型：不出车");
  }

  @Test
  void withoutConsistPlansADesignatedTicketCannotSpawn() {
    SpawnTicket eight = ticket(freeRoute, true).withConsist(Optional.of("sh_a8"));
    assertEquals(
        SpawnChoice.Kind.BLOCKED,
        org.fetarute
            .fetaruteTCAddon
            .dispatcher
            .schedule
            .spawn
            .ConsistArbiter
            .NONE
            .chooseSpawn(eight)
            .kind(),
        "没有编组方案可查：出不了指定车型，也不改出别的");
    assertEquals(
        SpawnChoice.legacy(),
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.ConsistArbiter.NONE.chooseSpawn(
            ticket(freeRoute, true)));
  }

  @Test
  void designatedConsistIsLookedUpWithinTheRoutesCompanyOnly() {
    Operator own = operator();
    Operator sibling =
        new Operator(
            UUID.randomUUID(),
            "SURC2",
            own.companyId(),
            "SURC2",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            NOW,
            NOW);
    Operator foreign = operator();
    // 别的公司那份方案 ID 更小：不限公司按 ID 取就会取到它（最高速度 15，与本公司的 20 不同）。
    ConsistPlan foreignPlan =
        new ConsistPlan(new UUID(0L, 1L), foreign.id(), "X", "1 SH_A8 | max-bps=15", NOW, NOW);
    ConsistPlan siblingPlan =
        new ConsistPlan(new UUID(0L, 2L), sibling.id(), "Y", "1 SH_A8 | max-bps=20", NOW, NOW);
    StorageProvider provider = mock(StorageProvider.class);
    ConsistPlanRepository repository = mock(ConsistPlanRepository.class);
    when(provider.consistPlans()).thenReturn(repository);
    when(repository.listAll()).thenReturn(List.of(foreignPlan, siblingPlan));
    org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository operators =
        mock(org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository.class);
    when(provider.operators()).thenReturn(operators);
    for (Operator operator : List.of(own, sibling, foreign)) {
      when(operators.findById(operator.id())).thenReturn(Optional.of(operator));
    }
    ConsistPlanService service =
        new ConsistPlanService(
            () -> Optional.of(provider),
            new ConsistInspector() {
              @Override
              public ConsistInspection inspect(String pattern) {
                return new ConsistInspection(true, 8, 96.0, Map.of(), OptionalInt.empty());
              }

              @Override
              public boolean exceedsSpawnLimit(String pattern) {
                return false;
              }
            },
            () -> new ConfigManager.TrainConfigSettings("metro", Map.of()),
            message -> {});
    UUID ownRoute = UUID.randomUUID();
    UUID strangerRoute = UUID.randomUUID();
    Map<UUID, RouteDefinitionCache.RouteRecord> records =
        Map.of(
            ownRoute,
            new RouteDefinitionCache.RouteRecord(
                own, record(ownRoute, Map.of()).line(), record(ownRoute, Map.of()).route()),
            strangerRoute,
            new RouteDefinitionCache.RouteRecord(
                operator(),
                record(strangerRoute, Map.of()).line(),
                record(strangerRoute, Map.of()).route()));
    service.attachRoutes(id -> Optional.ofNullable(records.get(id)));
    service.reload();

    assertEquals(
        java.util.OptionalDouble.of(20.0),
        service.member(ownRoute, "sh_a8").orElseThrow().profile().orElseThrow().maxSpeedBps(),
        "本运营商没有，就取同一公司别的运营商的那份");
    assertTrue(service.member(strangerRoute, "sh_a8").isEmpty(), "不跨公司取：别的公司的覆盖项可以不同");
  }

  private SpawnTicket ticket(UUID routeId, boolean timetable) {
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "SURC",
            operator.id(),
            operator.code(),
            UUID.randomUUID(),
            "WS",
            routeId,
            "WS-2C",
            Duration.ofSeconds(150),
            "SURC:D:LWN:1");
    return new SpawnTicket(
        UUID.randomUUID(),
        service,
        NOW,
        NOW,
        0,
        1L,
        Optional.empty(),
        Optional.empty(),
        timetable ? Optional.of(SpawnTicket.TIMETABLE_TRIP_PREFIX + "WS-001") : Optional.empty(),
        TripSource.SCHEDULED,
        0);
  }

  private static LayoverRegistry.LayoverCandidate candidate(String train, String consist) {
    Map<String, String> tags = new HashMap<>();
    tags.put("FTA_ROUTE_CODE", "WS-2N");
    if (consist != null) {
      tags.put(ConsistKey.TRAIN_TAG, consist);
    }
    return new LayoverRegistry.LayoverCandidate(
        train, "SURC:S:CHT", NodeId.of("SURC:S:CHT:1"), NOW, tags);
  }

  private RouteDefinitionCache.RouteRecord record(UUID routeId, Map<String, Object> metadata) {
    Line line =
        new Line(
            UUID.randomUUID(),
            "WS",
            operator.id(),
            "WS",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            NOW,
            NOW);
    Route route =
        new Route(
            routeId,
            "R-" + routeId.toString().substring(0, 4),
            line.id(),
            "R",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            metadata,
            NOW,
            NOW);
    return new RouteDefinitionCache.RouteRecord(operator, line, route);
  }

  private static Operator operator() {
    return new Operator(
        UUID.randomUUID(),
        "SURC",
        UUID.randomUUID(),
        "SURC",
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.empty(),
        Map.of(),
        NOW,
        NOW);
  }
}

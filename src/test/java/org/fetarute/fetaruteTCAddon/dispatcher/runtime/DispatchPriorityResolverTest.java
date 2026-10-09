package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class DispatchPriorityResolverTest {

  @Test
  void manualPriorityOverridesRouteDerivedPriority() {
    Fixture fixture = fixture(RouteOperationType.RETURN, 7, "R1");
    DispatchPriorityResolver resolver = fixture.resolver(new ArrayList<>());
    TrainProperties properties =
        trainProperties(
            "train-manual",
            "FTA_PRIORITY=42",
            "FTA_ROUTE_ID=" + fixture.routeId(),
            "FTA_ROUTE_INDEX=0");

    DispatchPriorityResolution resolution =
        resolver.resolve("test", "train-manual", properties, fixture.routeDefinition());

    assertEquals(42, resolution.priority());
    assertEquals(DispatchPrioritySource.MANUAL_FTA_PRIORITY, resolution.source());
    assertTrue(resolution.operationType().isEmpty());
    assertTrue(resolution.manualPriorityPresent());
    assertEquals(42, resolution.policyBasePriority());
    assertEquals(0, resolution.policyAdjustment());
  }

  @Test
  void createAndReturnRoutesGetDifferentDefaultPriorities() {
    Fixture createFixture = fixture(RouteOperationType.CREATE, 5, "CREATE");
    Fixture returnFixture = fixture(RouteOperationType.RETURN, 5, "RETURN");
    DispatchPriorityResolver createResolver = createFixture.resolver(new ArrayList<>());
    DispatchPriorityResolver returnResolver = returnFixture.resolver(new ArrayList<>());

    DispatchPriorityResolution create =
        createResolver.resolve(
            "test",
            "train-create",
            trainProperties(
                "train-create", "FTA_ROUTE_ID=" + createFixture.routeId(), "FTA_ROUTE_INDEX=0"),
            createFixture.routeDefinition());
    DispatchPriorityResolution ret =
        returnResolver.resolve(
            "test",
            "train-return",
            trainProperties(
                "train-return", "FTA_ROUTE_ID=" + returnFixture.routeId(), "FTA_ROUTE_INDEX=0"),
            returnFixture.routeDefinition());

    assertEquals(15, create.priority());
    assertEquals(-5, ret.priority());
    assertEquals(RouteOperationType.CREATE, create.operationType().orElseThrow());
    assertEquals(RouteOperationType.RETURN, ret.operationType().orElseThrow());
    assertFalse(create.depotExitContext());
    assertEquals(10, create.policyAdjustment());
    assertEquals(-10, ret.policyAdjustment());
  }

  /** 叫来的车（含回库途中）排在所有车之后：偏移 −20，比回库车还低。 */
  @Test
  void calledTrainsRankBelowReturnTrains() {
    Fixture operation = fixture(RouteOperationType.OPERATION, 5, "OPR");
    Fixture returning = fixture(RouteOperationType.RETURN, 5, "RET");

    DispatchPriorityResolution called =
        operation
            .resolver(new ArrayList<>())
            .resolve(
                "test",
                "train-called",
                trainProperties(
                    "train-called",
                    "FTA_ROUTE_ID=" + operation.routeId(),
                    "FTA_ROUTE_INDEX=0",
                    "FTA_CALL=00000000-0000-0000-0000-000000000001@SURC:PPK"),
                operation.routeDefinition());
    DispatchPriorityResolution calledReturning =
        returning
            .resolver(new ArrayList<>())
            .resolve(
                "test",
                "train-called-return",
                trainProperties(
                    "train-called-return",
                    "FTA_ROUTE_ID=" + returning.routeId(),
                    "FTA_ROUTE_INDEX=0",
                    "FTA_CALL=00000000-0000-0000-0000-000000000001@SURC:PPK"),
                returning.routeDefinition());

    assertEquals(-15, called.priority());
    assertEquals(DispatchPriorityPolicy.CALLED_OFFSET, called.policyAdjustment());
    assertEquals(-15, calledReturning.priority(), "回库途中同一档");
  }

  @Test
  void codeTagsResolveOperationTypeWhenRouteUuidIsMissing() {
    Fixture fixture = fixture(RouteOperationType.OPERATION, 4, "OPR");
    DispatchPriorityResolver resolver = fixture.resolver(new ArrayList<>());
    TrainProperties properties =
        trainProperties(
            "train-code-tags",
            "FTA_OPERATOR=OP",
            "FTA_LINE=L1",
            "FTA_ROUTE=OPR",
            "FTA_ROUTE_INDEX=0");

    DispatchPriorityResolution resolution =
        resolver.resolve("test", "train-code-tags", properties, null);

    assertEquals(24, resolution.priority());
    assertEquals(DispatchPrioritySource.ROUTE_CODE_TAGS, resolution.source());
    assertEquals("OP:L1:OPR", resolution.routeCode().orElseThrow());
    assertEquals(RouteOperationType.OPERATION, resolution.operationType().orElseThrow());
  }

  @Test
  void unknownRouteFallsBackSafelyAndEmitsTrace() {
    List<String> debugMessages = new ArrayList<>();
    DispatchPriorityResolver resolver =
        new DispatchPriorityResolver(null, null, new RouteProgressRegistry(), debugMessages::add);

    DispatchPriorityResolution resolution =
        resolver.resolve("unknown-test", "train-unknown", trainProperties("train-unknown"), null);

    assertEquals(0, resolution.priority());
    assertEquals(DispatchPrioritySource.DEFAULT_UNKNOWN, resolution.source());
    assertEquals("route_identity_missing", resolution.fallbackReason());
    assertTrue(
        debugMessages.stream().anyMatch(message -> message.contains("SMART_PRIORITY_RESOLVED")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("fallbackReason=route_identity_missing")));
    assertTrue(
        debugMessages.stream()
            .anyMatch(
                message ->
                    message.contains("depotExitContext=false")
                        && message.contains("manualPriorityPresent=false")
                        && message.contains("policyBasePriority=0")
                        && message.contains("policyAdjustment=0")));
  }

  /** 交路在缓存里：不查库就能解析，存储没就绪也一样。 */
  @Test
  void routeCacheResolvesPriorityWithoutStorage() {
    Fixture fixture = fixture(RouteOperationType.CREATE, 5, "CREATE");
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findRecord(fixture.routeId()))
        .thenReturn(
            Optional.of(
                new RouteDefinitionCache.RouteRecord(
                    fixture.operator(), fixture.line(), fixture.route())));
    DispatchPriorityResolver resolver =
        new DispatchPriorityResolver(
            null, routeDefinitions, new RouteProgressRegistry(), message -> {});

    DispatchPriorityResolution resolution =
        resolver.resolve(
            "test",
            "train-cached",
            trainProperties(
                "train-cached", "FTA_ROUTE_ID=" + fixture.routeId(), "FTA_ROUTE_INDEX=0"),
            fixture.routeDefinition());

    assertEquals(15, resolution.priority());
    assertEquals(RouteOperationType.CREATE, resolution.operationType().orElseThrow());
  }

  /** 信号巡检路径上查库出错：按默认优先级处理、不往外抛（否则升级成全网停车恢复）；同一个键在重查间隔内不再查库。 */
  @Test
  void storageFailureFallsBackToDefaultPriorityAndBacksOff() {
    Fixture fixture = fixture(RouteOperationType.RETURN, 7, "R1");
    StorageManager storageManager = mock(StorageManager.class);
    StorageProvider provider = mock(StorageProvider.class);
    RouteRepository routes = mock(RouteRepository.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    when(provider.routes()).thenReturn(routes);
    when(routes.findById(fixture.routeId())).thenThrow(new StorageException("读库失败"));
    AtomicLong clock = new AtomicLong();
    List<String> debugMessages = new ArrayList<>();
    DispatchPriorityResolver resolver =
        new DispatchPriorityResolver(
            storageManager, null, new RouteProgressRegistry(), debugMessages::add, clock::get);
    TrainProperties properties =
        trainProperties("train-db-down", "FTA_ROUTE_ID=" + fixture.routeId(), "FTA_ROUTE_INDEX=0");

    DispatchPriorityResolution first =
        resolver.resolve("test", "train-db-down", properties, fixture.routeDefinition());
    DispatchPriorityResolution second =
        resolver.resolve("test", "train-db-down", properties, fixture.routeDefinition());

    assertEquals(0, first.priority());
    assertTrue(first.operationType().isEmpty());
    assertEquals("operation_type_missing", first.fallbackReason());
    assertEquals(0, second.priority());
    verify(routes, times(1)).findById(fixture.routeId());
    assertTrue(
        debugMessages.stream()
            .anyMatch(message -> message.contains("SMART_PRIORITY_LOOKUP_FAILED")));

    clock.addAndGet(DispatchPriorityResolver.LOOKUP_MISS_RETRY_NANOS);
    resolver.resolve("test", "train-db-down", properties, fixture.routeDefinition());
    verify(routes, times(2)).findById(fixture.routeId());
  }

  /** 库里没有的交路同样不在每次巡检都查库。 */
  @Test
  void missingRouteIsNotQueriedOnEveryResolve() {
    Fixture fixture = fixture(RouteOperationType.OPERATION, 3, "GONE");
    StorageManager storageManager = mock(StorageManager.class);
    StorageProvider provider = mock(StorageProvider.class);
    RouteRepository routes = mock(RouteRepository.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    when(provider.routes()).thenReturn(routes);
    when(routes.findById(fixture.routeId())).thenReturn(Optional.empty());
    DispatchPriorityResolver resolver =
        new DispatchPriorityResolver(
            storageManager, null, new RouteProgressRegistry(), message -> {}, () -> 0L);
    TrainProperties properties =
        trainProperties("train-gone", "FTA_ROUTE_ID=" + fixture.routeId(), "FTA_ROUTE_INDEX=0");

    for (int i = 0; i < 5; i++) {
      resolver.resolve("test", "train-gone", properties, null);
    }

    verify(routes, times(1)).findById(fixture.routeId());
    verify(provider, never()).companies();
  }

  private static Fixture fixture(
      RouteOperationType operationType, int operatorPriority, String routeCode) {
    UUID companyId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Company company =
        new Company(
            companyId,
            "CO",
            "Company",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now);
    Operator operator =
        new Operator(
            operatorId,
            "OP",
            companyId,
            "Operator",
            Optional.empty(),
            Optional.empty(),
            operatorPriority,
            Optional.empty(),
            Map.of(),
            now,
            now);
    Line line =
        new Line(
            lineId,
            "L1",
            operatorId,
            "Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    Route route =
        new Route(
            routeId,
            routeCode,
            lineId,
            routeCode,
            Optional.empty(),
            RoutePatternType.LOCAL,
            operationType,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);
    RouteDefinition definition =
        new RouteDefinition(
            RouteId.of("OP:L1:" + routeCode),
            List.of(NodeId.of("OP:S:A:1"), NodeId.of("OP:S:B:1")),
            Optional.empty());
    return new Fixture(company, operator, line, route, definition);
  }

  private static TrainProperties trainProperties(String trainName, String... initialTags) {
    List<String> tags = new ArrayList<>(Arrays.asList(initialTags));
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(trainName);
    when(properties.hasTags()).thenAnswer(invocation -> !tags.isEmpty());
    when(properties.getTags()).thenAnswer(invocation -> List.copyOf(tags));
    return properties;
  }

  private record Fixture(
      Company company, Operator operator, Line line, Route route, RouteDefinition routeDefinition) {

    UUID routeId() {
      return route.id();
    }

    DispatchPriorityResolver resolver(List<String> debugMessages) {
      StorageManager storageManager = mock(StorageManager.class);
      StorageProvider provider = mock(StorageProvider.class);
      CompanyRepository companies = mock(CompanyRepository.class);
      OperatorRepository operators = mock(OperatorRepository.class);
      LineRepository lines = mock(LineRepository.class);
      RouteRepository routes = mock(RouteRepository.class);
      when(storageManager.isReady()).thenReturn(true);
      when(storageManager.provider()).thenReturn(Optional.of(provider));
      when(provider.companies()).thenReturn(companies);
      when(provider.operators()).thenReturn(operators);
      when(provider.lines()).thenReturn(lines);
      when(provider.routes()).thenReturn(routes);
      when(companies.listAll()).thenReturn(List.of(company));
      when(operators.findByCompanyAndCode(company.id(), operator.code()))
          .thenReturn(Optional.of(operator));
      when(operators.findById(operator.id())).thenReturn(Optional.of(operator));
      when(lines.findById(line.id())).thenReturn(Optional.of(line));
      when(lines.findByOperatorAndCode(operator.id(), line.code())).thenReturn(Optional.of(line));
      when(routes.findById(route.id())).thenReturn(Optional.of(route));
      when(routes.findByLineAndCode(line.id(), route.code())).thenReturn(Optional.of(route));
      return new DispatchPriorityResolver(
          storageManager, null, new RouteProgressRegistry(), debugMessages::add);
    }
  }
}

package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
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

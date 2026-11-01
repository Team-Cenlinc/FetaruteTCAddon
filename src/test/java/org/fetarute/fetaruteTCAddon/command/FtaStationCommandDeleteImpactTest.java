package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.call.PendingCallRecord;
import org.fetarute.fetaruteTCAddon.call.repository.PendingCallRepository;
import org.fetarute.fetaruteTCAddon.command.FtaStationCommand.StationDeletionImpact;
import org.fetarute.fetaruteTCAddon.company.api.StationGroupChange;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationGroupRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.repository.PidsBulletinRepository;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.repository.PidsScreenRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class FtaStationCommandDeleteImpactTest {

  private final UUID worldId = UUID.randomUUID();
  private final UUID stationId = UUID.randomUUID();
  private final UUID operatorId = UUID.randomUUID();

  private StorageProvider provider;
  private LineRepository lines;
  private RouteRepository routes;
  private RouteStopRepository routeStops;
  private StationGroupRepository groups;
  private RailNodeRepository railNodes;
  private StationRepository stations;
  private PidsScreenRepository screens;
  private PidsBulletinRepository bulletins;
  private PendingCallRepository calls;
  private Operator operator;
  private Station station;

  @BeforeEach
  void setUp() {
    provider = mock(StorageProvider.class);
    lines = mock(LineRepository.class);
    routes = mock(RouteRepository.class);
    routeStops = mock(RouteStopRepository.class);
    groups = mock(StationGroupRepository.class);
    railNodes = mock(RailNodeRepository.class);
    stations = mock(StationRepository.class);
    when(provider.stations()).thenReturn(stations);
    when(provider.transactionManager()).thenReturn(new InlineTransactionManager());
    OperatorRepository operators = mock(OperatorRepository.class);
    when(provider.lines()).thenReturn(lines);
    when(provider.routes()).thenReturn(routes);
    when(provider.routeStops()).thenReturn(routeStops);
    when(provider.stationGroups()).thenReturn(groups);
    when(provider.railNodes()).thenReturn(railNodes);
    when(provider.operators()).thenReturn(operators);
    screens = mock(PidsScreenRepository.class);
    bulletins = mock(PidsBulletinRepository.class);
    calls = mock(PendingCallRepository.class);
    when(provider.pidsScreens()).thenReturn(screens);
    when(provider.pidsBulletins()).thenReturn(bulletins);
    when(provider.pendingCalls()).thenReturn(calls);
    when(screens.listAll()).thenReturn(List.of());
    when(bulletins.listAll()).thenReturn(List.of());
    when(calls.listAll()).thenReturn(List.of());

    operator = mock(Operator.class);
    when(operator.id()).thenReturn(operatorId);
    when(operator.code()).thenReturn("SURN");
    when(operators.findById(operatorId)).thenReturn(Optional.of(operator));

    station = mock(Station.class);
    when(station.id()).thenReturn(stationId);
    when(station.code()).thenReturn("PTK");
    when(station.name()).thenReturn("Pitakan");

    when(lines.listAll()).thenReturn(List.of());
    when(groups.findMemberByStation(stationId)).thenReturn(Optional.empty());
    when(railNodes.listByWorld(worldId)).thenReturn(List.of());
  }

  @Test
  void unreferencedStationHasNoImpact() {
    StationDeletionImpact impact = assess();

    assertTrue(impact.routeStops().isEmpty());
    assertTrue(impact.groups().isEmpty());
    assertTrue(impact.signNodes().isEmpty());
  }

  @Test
  void listsRouteStopsReferencingStation() {
    Line line = mock(Line.class);
    UUID lineId = UUID.randomUUID();
    when(line.id()).thenReturn(lineId);
    when(line.code()).thenReturn("L1");
    when(line.operatorId()).thenReturn(operatorId);
    Route route = mock(Route.class);
    UUID routeId = UUID.randomUUID();
    when(route.id()).thenReturn(routeId);
    when(route.code()).thenReturn("UP");
    when(lines.listAll()).thenReturn(List.of(line));
    when(routes.listByLine(lineId)).thenReturn(List.of(route));
    when(routeStops.listByRoute(routeId))
        .thenReturn(
            List.of(
                stop(routeId, 0, Optional.of(UUID.randomUUID())),
                stop(routeId, 3, Optional.of(stationId)),
                stop(routeId, 4, Optional.empty())));

    StationDeletionImpact impact = assess();

    assertEquals(List.of("SURN/L1/UP#3"), impact.routeStops());
  }

  @Test
  void listsGroupContainingStation() {
    UUID groupId = UUID.randomUUID();
    StationGroup group = mock(StationGroup.class);
    when(group.code()).thenReturn("PTK-HUB");
    when(groups.findMemberByStation(stationId))
        .thenReturn(
            Optional.of(
                new StationGroupMember(
                    groupId, stationId, StationTransferType.SAME_PLATFORM, Optional.empty(), 0)));
    when(groups.findById(groupId)).thenReturn(Optional.of(group));

    StationDeletionImpact impact = assess();

    assertEquals(List.of(group), impact.groups());
  }

  @Test
  void listsOnlyStationSignsOfSameOperatorAndCode() {
    when(railNodes.listByWorld(worldId))
        .thenReturn(
            List.of(
                node("SURN:S:PTK:2", WaypointMetadata.station("surn", "ptk", 2)),
                node("SURN:S:PTK:1", WaypointMetadata.station("SURN", "PTK", 1)),
                node("SURN:S:GPT:1", WaypointMetadata.station("SURN", "GPT", 1)),
                node("FTA:S:PTK:1", WaypointMetadata.station("FTA", "PTK", 1)),
                node("SURN:D:PTK:1", WaypointMetadata.depot("SURN", "PTK", 1))));

    StationDeletionImpact impact = assess();

    assertEquals(List.of("SURN:S:PTK:1", "SURN:S:PTK:2"), impact.signNodes());
  }

  @Test
  void nodeAndDynamicStopsReferenceStation() {
    assertTrue(references(nodeStop("SURN:S:PTK:1")));
    assertTrue(references(nodeStop("surn:S:ptk:2")));
    assertTrue(references(nodeStop("SURN:S:PTK:1:01")));
    assertTrue(references(dynamicStop("DYNAMIC:SURN:S:PTK:[1:2]")));
    assertFalse(references(nodeStop("FTA:S:PTK:1")));
    assertFalse(references(nodeStop("SURN:S:GPT:1")));
    assertFalse(references(nodeStop("SURN:PTK:GPT:1:00")));
    assertFalse(references(nodeStop("SURN:D:PTK:1")));
    assertFalse(references(dynamicStop("DYNAMIC:SURN:S:GPT:[1:2]")));
  }

  @Test
  void stopOnBoundGraphNodeReferencesStation() {
    when(station.graphNodeId()).thenReturn(Optional.of("CUSTOM-NODE"));

    assertTrue(references(nodeStop("CUSTOM-NODE")));
    assertFalse(references(nodeStop("OTHER-NODE")));
  }

  @Test
  void referencedStationIsNotDeletedEvenWhenConfirmed() {
    givenStopOnStation();
    FetaruteTCAddon plugin = plugin();

    new FtaStationCommand(plugin)
        .executeDelete(
            mock(CommandSender.class), provider, operator, station, true, List.of(), Runnable::run);

    verify(stations, never()).delete(any());
    verify(plugin, never()).refreshStationDirectory();
  }

  @Test
  void previewWithoutConfirmDoesNotDelete() {
    FetaruteTCAddon plugin = plugin();

    new FtaStationCommand(plugin)
        .executeDelete(
            mock(CommandSender.class),
            provider,
            operator,
            station,
            false,
            List.of(),
            Runnable::run);

    verify(stations, never()).delete(any());
    verify(plugin, never()).refreshStationDirectory();
  }

  @Test
  void confirmedDeleteRemovesStationAndRefreshesDirectory() {
    FetaruteTCAddon plugin = plugin();

    new FtaStationCommand(plugin)
        .executeDelete(
            mock(CommandSender.class), provider, operator, station, true, List.of(), Runnable::run);

    verify(stations).delete(stationId);
    verify(plugin).refreshStationDirectory();
  }

  @Test
  void confirmedDeleteTouchesGroupAndNotifiesMemberRemoved() {
    UUID groupId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    StationGroup group =
        new StationGroup(
            groupId,
            companyId,
            "PTK-HUB",
            "Pitakan",
            Optional.empty(),
            java.util.Map.of(),
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH);
    when(groups.findMemberByStation(stationId))
        .thenReturn(
            Optional.of(
                new StationGroupMember(
                    groupId, stationId, StationTransferType.SAME_PLATFORM, Optional.empty(), 0)));
    when(groups.findById(groupId)).thenReturn(Optional.of(group));
    FetaruteTCAddon plugin = plugin();

    new FtaStationCommand(plugin)
        .executeDelete(
            mock(CommandSender.class), provider, operator, station, true, List.of(), Runnable::run);

    verify(stations).delete(stationId);
    verify(groups)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                saved ->
                    saved.id().equals(groupId)
                        && saved.updatedAt().isAfter(java.time.Instant.EPOCH)));
    verify(plugin)
        .notifyStationGroupChanged(
            new StationGroupChange(
                groupId,
                companyId,
                "PTK-HUB",
                StationGroupChange.Kind.MEMBER_REMOVED,
                Optional.of(stationId)));
  }

  @Test
  void countsScreensBulletinsAndCallsBoundByStationCode() {
    PidsScreen bound = mock(PidsScreen.class);
    when(bound.station()).thenReturn(Optional.of(new PidsStationKey("surn", "ptk")));
    PidsScreen otherOperator = mock(PidsScreen.class);
    when(otherOperator.station()).thenReturn(Optional.of(new PidsStationKey("SURC", "PTK")));
    PidsScreen unbound = mock(PidsScreen.class);
    when(unbound.station()).thenReturn(Optional.empty());
    when(screens.listAll()).thenReturn(List.of(bound, otherOperator, unbound));
    PidsBulletin bulletin = mock(PidsBulletin.class);
    when(bulletin.operatorCode()).thenReturn("SURN");
    when(bulletin.stations()).thenReturn(java.util.Set.of("PTK", "HHU"));
    when(bulletins.listAll()).thenReturn(List.of(bulletin));
    PendingCallRecord call = mock(PendingCallRecord.class);
    when(call.station()).thenReturn(new PidsStationKey("SURN", "PTK"));
    when(calls.listAll()).thenReturn(List.of(call));

    StationDeletionImpact impact = assess();

    assertEquals(1, impact.screens());
    assertEquals(1, impact.bulletins());
    assertEquals(1, impact.pendingCalls());
  }

  @Test
  void unreadableRailNodesAreReportedInsteadOfIgnored() {
    when(railNodes.listByWorld(worldId)).thenThrow(new StorageException("timeout"));

    StationDeletionImpact impact = assess();

    assertTrue(impact.signNodes().isEmpty());
    assertEquals(List.of(worldId), impact.unreadWorlds());
  }

  @Test
  void storageFailureDuringDeleteIsReportedWithoutRefreshing() {
    org.mockito.Mockito.doThrow(new StorageException("locked")).when(stations).delete(stationId);
    FetaruteTCAddon plugin = plugin();
    when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    CommandSender sender = mock(CommandSender.class);

    new FtaStationCommand(plugin)
        .executeDelete(sender, provider, operator, station, true, List.of(), Runnable::run);

    verify(plugin, never()).refreshStationDirectory();
    verify(plugin.getLocaleManager())
        .component(org.mockito.ArgumentMatchers.eq("command.station.delete.failed"), anyMap());
    verify(sender).sendMessage(any(Component.class));
  }

  private boolean references(RouteStop stop) {
    return FtaStationCommand.stopReferencesStation(stop, operator, station);
  }

  private void givenStopOnStation() {
    Line line = mock(Line.class);
    UUID lineId = UUID.randomUUID();
    when(line.id()).thenReturn(lineId);
    when(line.code()).thenReturn("L1");
    when(line.operatorId()).thenReturn(operatorId);
    Route route = mock(Route.class);
    UUID routeId = UUID.randomUUID();
    when(route.id()).thenReturn(routeId);
    when(route.code()).thenReturn("UP");
    when(lines.listAll()).thenReturn(List.of(line));
    when(routes.listByLine(lineId)).thenReturn(List.of(route));
    when(routeStops.listByRoute(routeId))
        .thenReturn(List.of(stop(routeId, 1, Optional.of(stationId))));
  }

  private static FetaruteTCAddon plugin() {
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.component(anyString())).thenReturn(Component.empty());
    when(locale.component(anyString(), anyMap())).thenReturn(Component.empty());
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getLocaleManager()).thenReturn(locale);
    return plugin;
  }

  private static RouteStop nodeStop(String nodeId) {
    return new RouteStop(
        UUID.randomUUID(),
        0,
        Optional.empty(),
        Optional.of(nodeId),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.empty());
  }

  private static RouteStop dynamicStop(String directive) {
    return new RouteStop(
        UUID.randomUUID(),
        0,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.of(directive));
  }

  private StationDeletionImpact assess() {
    return FtaStationCommand.assessDeletion(provider, operator, station, List.of(worldId));
  }

  private static RouteStop stop(UUID routeId, int sequence, Optional<UUID> stationId) {
    return new RouteStop(
        routeId,
        sequence,
        stationId,
        Optional.empty(),
        Optional.empty(),
        RouteStopPassType.STOP,
        Optional.empty());
  }

  private RailNodeRecord node(String id, WaypointMetadata meta) {
    return new RailNodeRecord(
        worldId,
        new NodeId(id),
        NodeType.STATION,
        0,
        0,
        0,
        Optional.empty(),
        Optional.of(meta),
        Optional.empty());
  }

  private static final class InlineTransactionManager implements StorageTransactionManager {

    @Override
    public StorageTransaction begin() throws StorageException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public <T> T execute(TransactionCallback<T> callback) throws StorageException {
      try {
        return callback.doInTransaction();
      } catch (StorageException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new StorageException("事务执行失败", ex);
      }
    }
  }
}

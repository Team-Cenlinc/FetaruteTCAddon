package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class RouteStopResolverTest {

  @Test
  void resolveNodeIdUsesWaypointNodeIdWhenPresent() {
    StorageProvider provider = mock(StorageProvider.class);
    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.empty(),
            Optional.of("SURN:AAA:BBB:1:00"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    Optional<NodeId> nodeId = RouteStopResolver.resolveNodeId(provider, stop);

    assertEquals(Optional.of(NodeId.of("SURN:AAA:BBB:1:00")), nodeId);
  }

  @Test
  void resolveNodeIdUsesStationGraphNodeId() {
    UUID stationId = UUID.randomUUID();
    Station station =
        new Station(
            stationId,
            "PTK",
            UUID.randomUUID(),
            Optional.empty(),
            "Platform",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("SURN:S:PTK:1"),
            Optional.empty(),
            Map.of(),
            Instant.now(),
            Instant.now());
    StationRepository stationRepository = mock(StationRepository.class);
    when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.stations()).thenReturn(stationRepository);

    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.of(stationId),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    Optional<NodeId> nodeId = RouteStopResolver.resolveNodeId(provider, stop);

    assertEquals(Optional.of(NodeId.of("SURN:S:PTK:1")), nodeId);
  }

  @Test
  void resolveNodeIdReturnsEmptyWhenStationMissing() {
    UUID stationId = UUID.randomUUID();
    StationRepository stationRepository = mock(StationRepository.class);
    when(stationRepository.findById(stationId)).thenReturn(Optional.empty());
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.stations()).thenReturn(stationRepository);

    RouteStop stop =
        new RouteStop(
            UUID.randomUUID(),
            0,
            Optional.of(stationId),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    assertTrue(RouteStopResolver.resolveNodeId(provider, stop).isEmpty());
  }
}

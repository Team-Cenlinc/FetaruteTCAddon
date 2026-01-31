package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationSidingPool;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;

class FtaRouteCommandParseStopsTest {

  @Test
  void parseStopsFromBookCreatesCretAndDstyStops() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    List<FtaRouteCommand.BookLine> lines =
        List.of(
            new FtaRouteCommand.BookLine(1, "CRET SURN:D:DEPOT:1"),
            new FtaRouteCommand.BookLine(2, "SURN:S:STATION:1"),
            new FtaRouteCommand.BookLine(3, "DSTY SURN:D:DEPOT:2"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(
            locale, provider, UUID.randomUUID(), UUID.randomUUID(), player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(3, stops.size());
    assertEquals("SURN:D:DEPOT:1", stops.get(0).waypointNodeId().orElse(""));
    assertEquals(RouteStopPassType.PASS, stops.get(0).passType());
    assertTrue(stops.get(0).notes().orElse("").startsWith("CRET "));
    assertEquals("SURN:D:DEPOT:2", stops.get(2).waypointNodeId().orElse(""));
    assertEquals(RouteStopPassType.PASS, stops.get(2).passType());
    assertTrue(stops.get(2).notes().orElse("").startsWith("DSTY "));
  }

  @Test
  void parseStopsFromBookRejectsDstyNotLast() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    List<FtaRouteCommand.BookLine> lines =
        List.of(
            new FtaRouteCommand.BookLine(1, "DSTY SURN:D:DEPOT:1"),
            new FtaRouteCommand.BookLine(2, "SURN:S:STATION:1"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(
            locale, provider, UUID.randomUUID(), UUID.randomUUID(), player, lines);

    assertTrue(result.isEmpty());
  }

  @Test
  void parseStopsFromBookAcceptsColonSyntax() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    List<FtaRouteCommand.BookLine> lines =
        List.of(
            new FtaRouteCommand.BookLine(1, "CRET:SURN:D:DEPOT:1"),
            new FtaRouteCommand.BookLine(2, "DSTY:SURN:D:DEPOT:2"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(
            locale, provider, UUID.randomUUID(), UUID.randomUUID(), player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(2, stops.size());
    assertEquals("SURN:D:DEPOT:1", stops.get(0).waypointNodeId().orElse(""));
    assertEquals("SURN:D:DEPOT:2", stops.get(1).waypointNodeId().orElse(""));
    assertTrue(stops.get(0).notes().orElse("").startsWith("CRET "));
    assertTrue(stops.get(1).notes().orElse("").startsWith("DSTY "));
  }

  @Test
  void parseStopsFromBookAcceptsInlineDynamicAction() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    UUID operatorId = UUID.randomUUID();
    StorageProvider provider = mockProviderWithStation(operatorId, "PTK");
    Player player = mock(Player.class);
    UUID routeId = UUID.randomUUID();
    List<FtaRouteCommand.BookLine> lines =
        List.of(new FtaRouteCommand.BookLine(1, "STOP PTK DYNAMIC:SURN:PTK:[1:3]"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(locale, provider, operatorId, routeId, player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(1, stops.size());
    assertEquals(RouteStopPassType.STOP, stops.get(0).passType());
    assertTrue(stops.get(0).stationId().isPresent());
    assertTrue(stops.get(0).notes().isPresent());
    assertEquals("DYNAMIC:SURN:PTK:[1:3]", stops.get(0).notes().orElse(""));
  }

  @Test
  void parseStopsFromBookAcceptsDynamicStopShorthand() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    UUID routeId = UUID.randomUUID();
    List<FtaRouteCommand.BookLine> lines =
        List.of(new FtaRouteCommand.BookLine(1, "STOP DYNAMIC:SURN:PTK:[1:3]"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(locale, provider, UUID.randomUUID(), routeId, player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(1, stops.size());
    assertEquals(RouteStopPassType.STOP, stops.get(0).passType());
    assertTrue(stops.get(0).waypointNodeId().isPresent());
    assertEquals("SURN:S:PTK:1", stops.get(0).waypointNodeId().orElse(""));
    assertEquals("DYNAMIC:SURN:PTK:[1:3]", stops.get(0).notes().orElse(""));
  }

  @Test
  void parseStopsFromBookAcceptsCretWithDynamic() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    UUID routeId = UUID.randomUUID();
    // CRET 后跟 NodeId，再跟 DYNAMIC 动作
    List<FtaRouteCommand.BookLine> lines =
        List.of(
            new FtaRouteCommand.BookLine(1, "CRET SURN:D:DEPOT:1 DYNAMIC:SURN:PTK:[1:3]"),
            new FtaRouteCommand.BookLine(2, "SURN:S:STATION:1"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(locale, provider, UUID.randomUUID(), routeId, player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(2, stops.size());
    // 第一个 stop 是 CRET
    assertEquals("SURN:D:DEPOT:1", stops.get(0).waypointNodeId().orElse(""));
    assertEquals(RouteStopPassType.PASS, stops.get(0).passType());
    // notes 应包含 CRET 和 DYNAMIC
    String notes0 = stops.get(0).notes().orElse("");
    assertTrue(notes0.contains("CRET SURN:D:DEPOT:1"), "notes should contain CRET directive");
    assertTrue(notes0.contains("DYNAMIC:SURN:PTK:[1:3]"), "notes should contain DYNAMIC action");
  }

  @Test
  void parseStopsFromBookAcceptsDstyWithDynamic() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    UUID routeId = UUID.randomUUID();
    // DSTY 后跟 NodeId，再跟 DYNAMIC 动作
    List<FtaRouteCommand.BookLine> lines =
        List.of(
            new FtaRouteCommand.BookLine(1, "SURN:S:STATION:1"),
            new FtaRouteCommand.BookLine(2, "DSTY SURN:D:DEPOT:2 DYNAMIC:SURN:PTK:[2:4]"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(locale, provider, UUID.randomUUID(), routeId, player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(2, stops.size());
    // 第二个 stop 是 DSTY
    assertEquals("SURN:D:DEPOT:2", stops.get(1).waypointNodeId().orElse(""));
    assertEquals(RouteStopPassType.PASS, stops.get(1).passType());
    // notes 应包含 DSTY 和 DYNAMIC
    String notes1 = stops.get(1).notes().orElse("");
    assertTrue(notes1.contains("DSTY SURN:D:DEPOT:2"), "notes should contain DSTY directive");
    assertTrue(notes1.contains("DYNAMIC:SURN:PTK:[2:4]"), "notes should contain DYNAMIC action");
  }

  @Test
  void parseStopsFromBookAcceptsCretWithMultipleActions() {
    FtaRouteCommand command = new FtaRouteCommand(mockPlugin());
    LocaleManager locale = mockLocale();
    StorageProvider provider = mockProvider();
    Player player = mock(Player.class);
    UUID routeId = UUID.randomUUID();
    // CRET 后跟多个 action
    List<FtaRouteCommand.BookLine> lines =
        List.of(
            new FtaRouteCommand.BookLine(
                1, "CRET SURN:D:DEPOT:1 DYNAMIC:SURN:PTK:[1:3] CHANGE:SURN:LINE2"),
            new FtaRouteCommand.BookLine(2, "SURN:S:STATION:1"));

    Optional<List<RouteStop>> result =
        command.parseStopsFromBook(locale, provider, UUID.randomUUID(), routeId, player, lines);

    assertTrue(result.isPresent());
    List<RouteStop> stops = result.get();
    assertEquals(2, stops.size());
    String notes0 = stops.get(0).notes().orElse("");
    assertTrue(notes0.contains("CRET SURN:D:DEPOT:1"));
    assertTrue(notes0.contains("DYNAMIC:SURN:PTK:[1:3]"));
    assertTrue(notes0.contains("CHANGE:SURN:LINE2"));
  }

  private static FetaruteTCAddon mockPlugin() {
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getName()).thenReturn("FetaruteTCAddon");
    return plugin;
  }

  private static LocaleManager mockLocale() {
    LocaleManager locale = mock(LocaleManager.class);
    when(locale.component(anyString(), anyMap())).thenReturn(Component.text("dummy"));
    return locale;
  }

  private static StorageProvider mockProvider() {
    StorageProvider provider = mock(StorageProvider.class);
    StationRepository stationRepository = mock(StationRepository.class);
    when(provider.stations()).thenReturn(stationRepository);
    return provider;
  }

  private static StorageProvider mockProviderWithStation(UUID operatorId, String code) {
    StorageProvider provider = mock(StorageProvider.class);
    StationRepository stationRepository = mock(StationRepository.class);
    when(provider.stations()).thenReturn(stationRepository);

    Station station =
        new Station(
            UUID.randomUUID(),
            code,
            operatorId,
            Optional.empty(),
            code,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.<StationSidingPool>of(),
            Map.of(),
            Instant.EPOCH,
            Instant.EPOCH);
    when(stationRepository.findByOperatorAndCode(operatorId, code))
        .thenReturn(Optional.of(station));
    return provider;
  }
}

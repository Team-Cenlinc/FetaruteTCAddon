package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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
}

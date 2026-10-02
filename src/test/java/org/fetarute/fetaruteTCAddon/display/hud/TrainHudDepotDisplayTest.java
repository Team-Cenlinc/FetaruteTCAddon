package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 线路终点落在车库时：同代码车站名 + 车库（用户定：“有点像 LWN concatenate depot”）。
 *
 * <p>车站按运营商代码 + 站码查车站目录：与公开 API 同一个索引，两边不会给出不同的车站；HUD 不读库。
 */
class TrainHudDepotDisplayTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private Operator surc;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    surc = storage.operator(storage.company("SURC"), "SURC", null);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private TrainHudContextResolver resolver() {
    StationDirectory directory = new StationDirectory(null, message -> {});
    directory.reload(storage.provider());
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStationDirectory()).thenReturn(Optional.of(directory));
    return new TrainHudContextResolver(
        plugin,
        mock(LocaleManager.class),
        mock(EtaService.class),
        mock(RouteDefinitionCache.class),
        mock(RouteProgressRegistry.class),
        mock(LayoverRegistry.class),
        mock(HudTemplateService.class),
        msg -> {});
  }

  private static TrainHudContext.StationDisplay depotDisplay(
      TrainHudContextResolver resolver, RouteTerminals.StationRef depot) throws Exception {
    Method method =
        TrainHudContextResolver.class.getDeclaredMethod(
            "resolveDepotDisplay", RouteTerminals.StationRef.class);
    method.setAccessible(true);
    return (TrainHudContext.StationDisplay) method.invoke(resolver, depot);
  }

  @Test
  void depotUsesSameCodeStationNamePlusDepot() throws Exception {
    Instant now = Instant.now();
    storage
        .provider()
        .stations()
        .save(
            new Station(
                UUID.randomUUID(),
                "LWN",
                surc.id(),
                Optional.empty(),
                "林湾",
                Optional.of("Lym Won"),
                Optional.empty(),
                Optional.empty(),
                Optional.of("SURC:S:LWN:1"),
                Optional.empty(),
                List.of(),
                Map.of(),
                now,
                now));

    TrainHudContext.StationDisplay display =
        depotDisplay(resolver(), new RouteTerminals.StationRef("SURC", "LWN", "SURC:D:LWN:1"));

    // 修复前按站码直接查车站，车库显示成「林湾」，与车次终点混在一起。
    assertEquals("林湾车库", display.label());
    assertEquals("Lym Won Depot", display.lang2());
    assertEquals("LWN", display.code());
  }

  @Test
  void depotWithoutMatchingStationFallsBackToCode() throws Exception {
    TrainHudContext.StationDisplay display =
        depotDisplay(resolver(), new RouteTerminals.StationRef("SURC", "XYZ", "SURC:D:XYZ:1"));

    assertEquals("XYZ车库", display.label());
    assertEquals("XYZ Depot", display.lang2());
  }
}

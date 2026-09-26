package org.fetarute.fetaruteTCAddon.display.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateService;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.junit.jupiter.api.Test;

/** 线路终点落在车库时：同代码车站名 + 车库（用户定：“有点像 LWN concatenate depot”）。 */
class TrainHudDepotDisplayTest {

  private static TrainHudContextResolver resolverWithStation(Station station, String operatorCode) {
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storage = mock(StorageManager.class);
    StorageProvider provider = mock(StorageProvider.class);
    CompanyRepository companies = mock(CompanyRepository.class);
    OperatorRepository operators = mock(OperatorRepository.class);
    StationRepository stations = mock(StationRepository.class);
    when(plugin.getStorageManager()).thenReturn(storage);
    when(storage.isReady()).thenReturn(true);
    when(storage.provider()).thenReturn(Optional.of(provider));
    when(provider.companies()).thenReturn(companies);
    when(provider.operators()).thenReturn(operators);
    when(provider.stations()).thenReturn(stations);
    Instant now = Instant.now();
    Company company =
        new Company(
            UUID.randomUUID(),
            "SURC",
            "SURC",
            Optional.empty(),
            UUID.randomUUID(),
            CompanyStatus.values()[0],
            0L,
            Map.of(),
            now,
            now);
    Operator operator =
        new Operator(
            station == null ? UUID.randomUUID() : station.operatorId(),
            operatorCode,
            company.id(),
            "SURC",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            now,
            now);
    when(companies.listAll()).thenReturn(List.of(company));
    when(operators.listByCompany(any())).thenReturn(List.of(operator));
    when(stations.listByOperator(any())).thenReturn(station == null ? List.of() : List.of(station));
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
    Station lwn =
        new Station(
            UUID.randomUUID(),
            "LWN",
            UUID.randomUUID(),
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
            now);
    TrainHudContext.StationDisplay display =
        depotDisplay(
            resolverWithStation(lwn, "SURC"),
            new RouteTerminals.StationRef("SURC", "LWN", "SURC:D:LWN:1"));
    // 修复前按站码直接查车站，车库显示成「林湾」，与车次终点混在一起。
    assertEquals("林湾车库", display.label());
    assertEquals("Lym Won Depot", display.lang2());
    assertEquals("LWN", display.code());
  }

  @Test
  void depotWithoutMatchingStationFallsBackToCode() throws Exception {
    TrainHudContext.StationDisplay display =
        depotDisplay(
            resolverWithStation(null, "SURC"),
            new RouteTerminals.StationRef("SURC", "XYZ", "SURC:D:XYZ:1"));
    assertEquals("XYZ车库", display.label());
    assertEquals("XYZ Depot", display.lang2());
  }
}

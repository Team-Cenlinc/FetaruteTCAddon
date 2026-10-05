package org.fetarute.fetaruteTCAddon.drive.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.junit.jupiter.api.Test;

class TrainSetupStoreTest {

  @Test
  void formatsPersistentSystemsInStartOrderAndDropsTheKey() {
    assertEquals(
        "power,breaker,aux",
        TrainSetupStore.format(
            EnumSet.of(SetupSystem.AUX, SetupSystem.KEY, SetupSystem.POWER, SetupSystem.BREAKER)));
  }

  @Test
  void parsesItsOwnFormatAndIgnoresJunk() {
    assertEquals(
        EnumSet.of(SetupSystem.POWER, SetupSystem.BREAKER),
        TrainSetupStore.parse("power, breaker,key,turbo,"));
    assertTrue(TrainSetupStore.parse("").isEmpty());
  }

  @Test
  void aTrainLeftLongerThanTheLimitIsCold() {
    long tenMinutes = 600_000L;

    assertFalse(TrainSetupStore.isCold(1_000L, 1_000L + tenMinutes, tenMinutes));
    assertTrue(TrainSetupStore.isCold(1_000L, 1_001L + tenMinutes, tenMinutes));
    assertFalse(TrainSetupStore.isCold(5_000L, 1_000L, tenMinutes), "时钟回拨不算冷车");
  }

  @Test
  void theReservoirLeaksWhileTheTrainStandsUnattended() {
    assertEquals(900.0, TrainSetupStore.afterLeak(900, 0, 30), 1e-9);
    assertEquals(840.0, TrainSetupStore.afterLeak(900, 120_000, 30), 1e-9, "两分钟漏 60");
    assertEquals(0.0, TrainSetupStore.afterLeak(100, 3_600_000, 30), 1e-9, "不会漏成负数");
    assertEquals(900.0, TrainSetupStore.afterLeak(900, -5_000, 30), 1e-9, "时钟回拨不漏");
  }

  private static TrainProperties fakeProperties() {
    TrainProperties properties = mock(TrainProperties.class);
    List<String> tags = new ArrayList<>();
    when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
    when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
    doAnswer(inv -> collect(inv.getArguments(), tags::add))
        .when(properties)
        .addTags(any(String[].class));
    doAnswer(inv -> collect(inv.getArguments(), tags::remove))
        .when(properties)
        .removeTags(any(String[].class));
    return properties;
  }

  private static Object collect(Object[] arguments, java.util.function.Consumer<String> sink) {
    for (Object arg : arguments) {
      if (arg instanceof String[] values) {
        for (String value : values) {
          sink.accept(value);
        }
      } else if (arg instanceof String value) {
        sink.accept(value);
      }
    }
    return null;
  }

  @Test
  void airSurvivesARoundTripAndLeaksWhileStanding() {
    TrainProperties properties = fakeProperties();

    TrainSetupStore.saveAir(properties, new TrainSetupStore.AirSnapshot(880, true), 1_000L);
    TrainSetupStore.AirSnapshot loaded = TrainSetupStore.loadAir(properties, 1_000L + 120_000L, 30);

    assertEquals(820.0, loaded.mainReservoirKpa(), 1e-9);
    assertTrue(loaded.compressorSwitch());
  }

  @Test
  void clearingTheSystemsDoesNotStopTheAirFromLeaking() {
    TrainProperties properties = fakeProperties();
    TrainSetupStore.saveAir(properties, new TrainSetupStore.AirSnapshot(880, false), 1_000L);

    // standard 级关机：已接通的系统清空，系统时刻随之清除，不能连带让气压不再漏泄。
    TrainSetupStore.save(properties, EnumSet.noneOf(SetupSystem.class), 2_000L);
    TrainSetupStore.AirSnapshot loaded =
        TrainSetupStore.loadAir(properties, 1_000L + 3_600_000L, 30);

    assertEquals(0.0, loaded.mainReservoirKpa(), 1e-9, "一小时漏光");
  }

  @Test
  void anIncompleteAirRecordCountsAsEmpty() {
    TrainProperties properties = fakeProperties();
    TrainTagHelper.writeTag(properties, TrainSetupStore.TAG_MAIN_RESERVOIR, "880");

    assertEquals(0.0, TrainSetupStore.loadAir(properties, 5_000L, 30).mainReservoirKpa());
  }

  @Test
  void anEmptyReservoirWithTheSwitchOffClearsTheTags() {
    TrainProperties properties = fakeProperties();
    TrainSetupStore.saveAir(properties, new TrainSetupStore.AirSnapshot(500, true), 1_000L);

    TrainSetupStore.saveAir(properties, new TrainSetupStore.AirSnapshot(0, false), 2_000L);

    assertTrue(
        TrainTagHelper.readTagValue(properties, TrainSetupStore.TAG_MAIN_RESERVOIR).isEmpty());
    assertTrue(
        TrainTagHelper.readTagValue(properties, TrainSetupStore.TAG_MAIN_RESERVOIR_AT).isEmpty());
  }

  @Test
  void systemsRoundTripAndGoColdAfterTheLimit() {
    TrainProperties properties = fakeProperties();
    TrainSetupStore.save(properties, EnumSet.of(SetupSystem.POWER, SetupSystem.BREAKER), 1_000L);

    assertEquals(
        EnumSet.of(SetupSystem.POWER, SetupSystem.BREAKER),
        TrainSetupStore.load(properties, 61_000L, 600_000L));
    assertTrue(TrainSetupStore.load(properties, 1_000L + 600_001L, 600_000L).isEmpty());
  }
}

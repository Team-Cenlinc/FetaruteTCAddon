package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.junit.jupiter.api.Test;

/** 未发车票据按车库牌子的列车模板名推断车种：地铁/轻轨型先于电动车组判定，推断不出来交给默认车种。 */
class SpawnTrainConfigResolverTest {

  @Test
  void metroTramAndLightRailNamesMapToMetro() {
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("Metro6A"));
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("tram_train"));
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("LIGHT_RAIL_2"));
  }

  @Test
  void metroIsCheckedBeforeEmu() {
    assertEquals(
        Optional.of(TrainType.METRO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("metro_emu"));
    assertEquals(
        Optional.of(TrainType.EMU), SpawnTrainConfigResolver.inferTrainTypeFromPattern("DS_EMU"));
  }

  @Test
  void otherKnownNamesKeepTheirTypes() {
    assertEquals(
        Optional.of(TrainType.DMU), SpawnTrainConfigResolver.inferTrainTypeFromPattern("dmu3"));
    assertEquals(
        Optional.of(TrainType.DIESEL_PUSH_PULL),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("diesel_push"));
    assertEquals(
        Optional.of(TrainType.ELECTRIC_LOCO),
        SpawnTrainConfigResolver.inferTrainTypeFromPattern("electric_loco_8"));
  }

  @Test
  void unknownOrBlankNamesAreLeftToTheDefaultType() {
    assertTrue(SpawnTrainConfigResolver.inferTrainTypeFromPattern("M9A").isEmpty());
    assertTrue(SpawnTrainConfigResolver.inferTrainTypeFromPattern(" ").isEmpty());
    assertTrue(SpawnTrainConfigResolver.inferTrainTypeFromPattern(null).isEmpty());
  }
}

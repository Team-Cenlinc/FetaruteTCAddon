package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner;
import org.junit.jupiter.api.Test;

/**
 * 交路上限的优先级链：{@code --max-trips} > 交路组的 {@code maxOperationTrips} > 默认。
 *
 * <p>此前这个值只认 flag，在组上配了 {@code maxOperationTrips} 也白配。它一旦偏小，症状是「大交路的班次被 大量取消、报
 * NO_CREATE_ACCESS」——交路接不下去，车只好提前回库，后面从中途站始发的班次就没车可用了， 离病因很远。实测 WS 配 180/360 时非得 8
 * 班/交路才排得出来，而组上正好配着 8。
 */
class FtaTimetableCommandMaxTripsTest {

  private static SpawnGroup group(String name, Integer maxTrips) {
    return new SpawnGroup(name, Optional.of(300), Optional.ofNullable(maxTrips));
  }

  /** flag 说了算，组配置再大也不越过它。 */
  @Test
  void flagWinsOverGroupConfiguration() {
    FtaTimetableCommand.MaxTripsChoice choice =
        FtaTimetableCommand.resolveMaxTrips(3, List.of(group("Full", 8), group("Short", 4)));

    assertEquals(3, choice.trips());
    assertEquals("--max-trips", choice.description());
  }

  /**
   * 没给 flag 就读组配置，多个组取<b>最大值</b>。
   *
   * <p>一条交路可以跨组接班（小交路进城、接大交路跑全程），按某一个组的上限卡它没有道理，取最大才不会把 长交路误伤。
   */
  @Test
  void groupConfigurationIsUsedAndTakesTheLargest() {
    FtaTimetableCommand.MaxTripsChoice choice =
        FtaTimetableCommand.resolveMaxTrips(null, List.of(group("Short", 4), group("Full", 8)));

    assertEquals(8, choice.trips());
    assertTrue(choice.description().contains("Full"), choice.description());
    assertTrue(choice.description().contains("maxOperationTrips"), choice.description());
    assertEquals(Optional.of("Full"), choice.group(), "面板的 [改] 要指向值真正来源的那个组，不是第一个组");
  }

  /** 组 metadata 认到 1000，编表这边的 flag 只认到 64：从组配置走进来时受同一个上界。 */
  @Test
  void groupConfigurationIsCappedAtTheFlagCeiling() {
    FtaTimetableCommand.MaxTripsChoice choice =
        FtaTimetableCommand.resolveMaxTrips(null, List.of(group("Full", 1000)));

    assertEquals(FtaTimetableCommand.MAX_TRIPS_CEILING, choice.trips());
    assertTrue(choice.description().contains("收到上限"), choice.description());
  }

  /** 各组配得不一样时给出提醒：编表取最大，运行时按组各自的 FTA_OP_MAX 回收，配得小的那个组排出来的 交路比车跑得完的长，后面几班到点没车。 */
  @Test
  void mismatchedGroupsAreWarnedAbout() {
    assertTrue(
        FtaTimetableCommand.maxTripsMismatchWarning(List.of(group("Short", 4), group("Full", 8)))
            .orElse("")
            .contains("Short"),
        "要点名配得小的那个组");
    assertTrue(
        FtaTimetableCommand.maxTripsMismatchWarning(List.of(group("Short", 8), group("Full", 8)))
            .isEmpty(),
        "配成一样就不该提醒");
    assertTrue(
        FtaTimetableCommand.maxTripsMismatchWarning(List.of(group("Short", null))).isEmpty(),
        "一个都没配也不该提醒");
  }

  /** 组上没配的不参与比较；一个都没配就回落到默认。 */
  @Test
  void fallsBackToTheDefaultWhenNoGroupConfiguresIt() {
    FtaTimetableCommand.MaxTripsChoice none =
        FtaTimetableCommand.resolveMaxTrips(null, List.of(group("Short", null)));
    assertEquals(VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS, none.trips());
    assertEquals("默认", none.description());

    assertEquals(
        VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS,
        FtaTimetableCommand.resolveMaxTrips(null, List.of()).trips());

    FtaTimetableCommand.MaxTripsChoice partial =
        FtaTimetableCommand.resolveMaxTrips(null, List.of(group("Short", null), group("Full", 6)));
    assertEquals(6, partial.trips());
  }
}

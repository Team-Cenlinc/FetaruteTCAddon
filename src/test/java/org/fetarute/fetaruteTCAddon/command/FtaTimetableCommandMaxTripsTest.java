package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner;
import org.junit.jupiter.api.Test;

/**
 * 交路上限的优先级链：{@code --max-trips}（整次覆盖）> 各交路组自己的 {@code maxOperationTrips}（按组生效）> 默认。
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
   * 没给 flag 就按组生效：报告里逐组列出各自的上限，没按组配置的 route 用默认值，不借别组的最大值。
   *
   * <p>此前多个组取最大值统一卡，短交路线借到长交路线的上限，跑一个多小时就回库换车，换车走行满线跑，排表排不开。 跨组接班的交路取它跑过的各组中最大的（见派车器）。
   */
  @Test
  void groupConfigurationIsAppliedPerGroup() {
    FtaTimetableCommand.MaxTripsChoice choice =
        FtaTimetableCommand.resolveMaxTrips(null, List.of(group("Short", 4), group("Full", 8)));

    assertEquals(VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS, choice.trips(), "没按组配置的 route 用默认值");
    assertTrue(choice.description().contains("Short 4"), choice.description());
    assertTrue(choice.description().contains("Full 8"), choice.description());
    assertTrue(choice.description().contains("按组"), choice.description());
  }

  /** 组 metadata 认到 1000，编表这边的 flag 只认到 64：从组配置走进来时受同一个上界。 */
  @Test
  void groupConfigurationIsCappedAtTheFlagCeiling() {
    FtaTimetableCommand.MaxTripsChoice choice =
        FtaTimetableCommand.resolveMaxTrips(null, List.of(group("Full", 1000)));

    assertTrue(
        choice.description().contains("Full " + FtaTimetableCommand.MAX_TRIPS_CEILING),
        choice.description());
    assertTrue(choice.description().contains("收到上限"), choice.description());
  }

  /** 只有 --max-trips 覆盖得比某组的配置大时才提醒：运行时按组各自的 FTA_OP_MAX 回收，那个组排出来的交路比车跑得完的长。 */
  @Test
  void overrideLargerThanAGroupIsWarnedAbout() {
    String warning =
        FtaTimetableCommand.maxTripsOverrideWarning(
                10, List.of(group("Short", 4), group("Full", 8)))
            .orElse("");
    assertTrue(warning.contains("Short 4") && warning.contains("Full 8"), warning);
    assertTrue(
        FtaTimetableCommand.maxTripsOverrideWarning(
                null, List.of(group("Short", 4), group("Full", 8)))
            .isEmpty(),
        "按组生效时与运行时一致，不该提醒");
    assertTrue(
        FtaTimetableCommand.maxTripsOverrideWarning(4, List.of(group("Short", 4), group("Full", 8)))
            .isEmpty(),
        "覆盖值不大于任何组的配置，不该提醒");
  }

  /** 组上没配的用默认值；一个都没配就整张表用默认。 */
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
    assertEquals(VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS, partial.trips());
    assertTrue(partial.description().contains("Full 6"), partial.description());
  }
}

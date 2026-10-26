package org.fetarute.fetaruteTCAddon.signaller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardFixtures;
import org.junit.jupiter.api.Test;

/** 切临时小区的单元：只隔道岔、咽喉就相通的成员合成一个单元，管理员划好的小区整体一个；隔着区间的单元算相邻。 */
class ZoneUnitsTest {

  private static final YardArea.Member ABC = YardArea.Member.station("ABC");
  private static final YardArea.Member DEF = YardArea.Member.station("DEF");
  private static final YardArea.Member DEP = YardArea.Member.depot("DEP");

  /** 车库 DEP 直连 ABC 1 道（不经区间点），合成一个单元；ABC 与 DEF 之间隔着区间点，是两个相邻的单元。 */
  @Test
  void membersJoinedWithoutAnIntervalShareAUnit() {
    ZoneUnits units =
        ZoneUnits.of(YardFixtures.corridor(), "SURC", List.of(ABC, DEF, DEP), List.of())
            .orElseThrow();

    assertEquals(2, units.units().size());
    assertEquals(List.of(ABC, DEP), units.units().get(0).members());
    assertEquals(List.of(DEF), units.units().get(1).members());
    assertEquals(1, units.links().size());
    assertEquals(new ZoneUnits.Link(0, 1), units.links().get(0));
  }

  /** 管理员划好的小区整体算一个单元，要么整个分出去，要么不分。 */
  @Test
  void aFixedSubZoneIsOneUnit() {
    ZoneUnits units =
        ZoneUnits.of(
                YardFixtures.corridor(), "SURC", List.of(ABC, DEF, DEP), List.of(List.of(ABC, DEF)))
            .orElseThrow();

    assertEquals(1, units.units().size());
    assertEquals(List.of(ABC, DEF, DEP), units.units().get(0).members());
    assertEquals(0, units.links().size());
  }
}

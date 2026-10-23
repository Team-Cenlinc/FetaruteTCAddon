package org.fetarute.fetaruteTCAddon.display.pids;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 线路运行状况屏按运营商轮播：只轮同一公司、还在的运营商；一家放完自己的页再到下一家，没有线路的跳过。 */
class PidsStatusRotationTest {

  private static final UUID SUR = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final Map<String, UUID> COMPANIES =
      Map.of("SURC", SUR, "SURN", SUR, "SURW", SUR, "FOO", OTHER);

  private static List<String> operators(List<String> rotation, Set<String> existing) {
    return PidsStatusRotation.operators(
        "SURC", rotation, code -> Optional.ofNullable(COMPANIES.get(code)), existing::contains);
  }

  @Test
  void onlyOperatorsOfTheSameCompanyThatStillExistRotate() {
    Set<String> all = COMPANIES.keySet();

    assertEquals(List.of("SURC"), operators(List.of(), all), "不轮播：只有本运营商");
    assertEquals(
        List.of("SURC", "SURN"), operators(List.of("SURC", "FOO", "SURN"), all), "别的公司的跳过");
    assertEquals(List.of("SURN"), operators(List.of("SURN", "SURW"), Set.of("SURN")), "不在了的跳过");
    assertEquals(List.of("SURC"), operators(List.of("FOO"), all), "一家也不算数：只显示本运营商");
  }

  @Test
  void eachOperatorShowsItsOwnPagesBeforeTheNext() {
    List<Integer> pages = List.of(2, 1, 1);
    List<Integer> all = List.of(0, 1, 2);

    assertEquals(new PidsStatusRotation.Slot(0, 0, all), PidsStatusRotation.slot(pages, 0));
    assertEquals(new PidsStatusRotation.Slot(0, 1, all), PidsStatusRotation.slot(pages, 1));
    assertEquals(new PidsStatusRotation.Slot(1, 0, all), PidsStatusRotation.slot(pages, 2));
    assertEquals(new PidsStatusRotation.Slot(2, 0, all), PidsStatusRotation.slot(pages, 3));
    assertEquals(
        new PidsStatusRotation.Slot(0, 0, all), PidsStatusRotation.slot(pages, 4), "一圈后从头");
    assertEquals(new PidsStatusRotation.Slot(2, 0, all), PidsStatusRotation.slot(pages, -1));
  }

  @Test
  void anOperatorWithoutLinesIsSkipped() {
    List<Integer> pages = List.of(1, 0, 1);

    assertEquals(1, PidsStatusRotation.slot(pages, 1).position(), "第二家有线路的");

    assertEquals(
        new PidsStatusRotation.Slot(0, 0, List.of(0, 2)), PidsStatusRotation.slot(pages, 0));
    assertEquals(
        new PidsStatusRotation.Slot(2, 0, List.of(0, 2)),
        PidsStatusRotation.slot(pages, 1),
        "标签只列有线路的几家");
    assertEquals(
        new PidsStatusRotation.Slot(0, 0, List.of(0)),
        PidsStatusRotation.slot(List.of(0, 0), 7),
        "都没有线路：放第一家（写过滤没有匹配的线路）");
  }
}

package org.fetarute.fetaruteTCAddon.web.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS 的 CSV 与坐标写法")
class GtfsFormatTest {

  @Test
  @DisplayName("含逗号、引号时按 RFC 4180 加引号；制表符与换行换成空格（GTFS 禁止）；行尾固定 LF")
  void escapesCsvFields() {
    byte[] csv =
        GtfsCsv.write(
            List.of("a", "b", "c"),
            List.of(
                List.of("plain", "has,comma", "has \"quote\""),
                List.of("line\nbreak", "tab\there", "尾空格 ")));

    assertEquals(
        "a,b,c\n" + "plain,\"has,comma\",\"has \"\"quote\"\"\"\n" + "line break,tab here,尾空格 \n",
        new String(csv, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("时刻允许超过 24 点；日期固定 YYYYMMDD")
  void writesTimesPastMidnight() {
    assertEquals("23:50:20", GtfsTime.timeOfDay(23 * 3600 + 50 * 60 + 20));
    assertEquals("24:00:00", GtfsTime.timeOfDay(24 * 3600));
    assertEquals("24:11:40", GtfsTime.timeOfDay(24 * 3600 + 11 * 60 + 40));
    assertEquals("00:00:00", GtfsTime.timeOfDay(-1));
    assertEquals("20261025", GtfsTime.date(java.time.LocalDate.of(2026, 10, 25)));
  }

  @Test
  @DisplayName("坐标：一格一米，+z 向南取负纬度；原点避开校验器的 point_near_origin")
  void projectsBlockCoordinates() {
    assertEquals(-200.0 / 6_378_137.0 * 180.0 / Math.PI, GtfsGeo.latitude(200.0), 1e-12);
    assertEquals(
        GtfsGeo.ORIGIN_LONGITUDE + 500.0 / 6_378_137.0 * 180.0 / Math.PI,
        GtfsGeo.longitude(500.0),
        1e-12);
    assertEquals("0.0000000", GtfsGeo.format(GtfsGeo.latitude(0.0)));
    assertEquals("0.0000000", GtfsGeo.format(-0.0));
    assertEquals("-0.0017966", GtfsGeo.format(GtfsGeo.latitude(200.0)));
    assertEquals("-29.9955084", GtfsGeo.format(GtfsGeo.longitude(500.0)));
  }
}

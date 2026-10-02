package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

/** 走行线路引用的字符串形态：四段 code、只去空白、大小写原样（仓储按 code 精确匹配）；段数不对就不是引用。 */
class TimetableRouteMetadataTest {

  @Test
  void parsesFourSegmentsAndKeepsCase() {
    Optional<TimetableRouteMetadata.RouteRef> ref =
        TimetableRouteMetadata.parse(" cht / SURN / nl / nl-ret ");

    assertTrue(ref.isPresent());
    assertEquals("cht", ref.get().company(), "code 按存储原样，不能替用户改大小写");
    assertEquals("SURN", ref.get().operator());
    assertEquals("nl", ref.get().line());
    assertEquals("nl-ret", ref.get().route());
    assertEquals("cht/SURN/nl/nl-ret", ref.get().format());
  }

  @Test
  void rejectsWrongSegmentCountOrBlankSegment() {
    assertTrue(TimetableRouteMetadata.parse("CHT/SURN/NL").isEmpty());
    assertTrue(TimetableRouteMetadata.parse("CHT/SURN/NL/RET/EXTRA").isEmpty());
    assertTrue(TimetableRouteMetadata.parse("CHT//NL/RET").isEmpty());
    assertTrue(TimetableRouteMetadata.parse(null).isEmpty());
    assertTrue(TimetableRouteMetadata.parse("").isEmpty());
  }

  @Test
  void readsFromMetadataOnlyWhenItIsAWellFormedString() {
    Map<String, Object> metadata =
        Map.of(
            TimetableRouteMetadata.KEY_RETURN_ROUTE,
            "CHT/SURN/NL/NL-RET",
            TimetableRouteMetadata.KEY_CREATE_ROUTE,
            42);

    assertEquals(
        "CHT/SURN/NL/NL-RET",
        TimetableRouteMetadata.read(metadata, TimetableRouteMetadata.KEY_RETURN_ROUTE)
            .orElseThrow()
            .format());
    assertTrue(
        TimetableRouteMetadata.read(metadata, TimetableRouteMetadata.KEY_CREATE_ROUTE).isEmpty());
    assertTrue(
        TimetableRouteMetadata.read(null, TimetableRouteMetadata.KEY_CREATE_ROUTE).isEmpty());
  }

  @Test
  void keyDeterminesTheExpectedRouteType() {
    assertEquals(
        RouteOperationType.CREATE,
        TimetableRouteMetadata.typeOf(TimetableRouteMetadata.KEY_CREATE_ROUTE));
    assertEquals(
        RouteOperationType.RETURN,
        TimetableRouteMetadata.typeOf(TimetableRouteMetadata.KEY_RETURN_ROUTE));
  }
}

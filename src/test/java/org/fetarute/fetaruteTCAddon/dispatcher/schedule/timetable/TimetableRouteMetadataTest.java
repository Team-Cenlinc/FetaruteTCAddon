package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

/** 走行线路引用的字符串形态：四段 code、去空白、转大写；段数不对就不是引用。 */
class TimetableRouteMetadataTest {

  @Test
  void parsesFourSegmentsAndNormalizes() {
    Optional<TimetableRouteMetadata.RouteRef> ref =
        TimetableRouteMetadata.parse(" cht / surn / nl / nl-ret ");

    assertTrue(ref.isPresent());
    assertEquals("CHT", ref.get().company());
    assertEquals("SURN", ref.get().operator());
    assertEquals("NL", ref.get().line());
    assertEquals("NL-RET", ref.get().route());
    assertEquals("CHT/SURN/NL/NL-RET", ref.get().format());
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

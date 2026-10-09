package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 叫车标签、叫车票车次标识与线路叫车开关的读写。 */
class CallTagTest {

  @Test
  void tagRoundTrips() {
    CallTag tag = new CallTag(UUID.randomUUID(), new PidsStationKey("surc", "ppk"));

    assertEquals(Optional.of(tag), CallTag.parse(tag.format()));
    assertTrue(tag.format().endsWith("@SURC:PPK"));
    assertTrue(CallTag.parse("not-a-tag").isEmpty());
    assertTrue(CallTag.parse("x@SURC:PPK").isEmpty(), "叫车 id 不是 UUID");
  }

  @Test
  void tripIdCarriesTheTagAndTheEntryIndex() {
    String tag = new CallTag(UUID.randomUUID(), new PidsStationKey("SURC", "PPK")).format();
    Optional<String> withEntry =
        Optional.of(
            OnDemandTrip.format(tag, Optional.of(new OnDemandTrip.Entry(4, Optional.empty()))));
    Optional<String> plain = Optional.of(OnDemandTrip.format(tag, Optional.empty()));

    assertEquals(Optional.of(tag), OnDemandTrip.callTagOf(withEntry));
    assertEquals(OptionalInt.of(4), OnDemandTrip.entryIndexOf(withEntry));
    assertEquals(OptionalInt.empty(), OnDemandTrip.entryIndexOf(plain));
    assertEquals(plain, OnDemandTrip.withoutEntry(withEntry), "放弃区间生成后只去掉下标");
    assertEquals(
        OptionalInt.empty(),
        OnDemandTrip.entryIndexOf(Optional.of("TIMETABLE-abc#4")),
        "不是叫车票的车次不认下标");
    assertTrue(OnDemandTrip.callTagOf(Optional.of("TIMETABLE-abc")).isEmpty());
  }

  /** 生成点不在交路节点表里时，车次标识带上生成点；旧写法（只有下标）照认。 */
  @Test
  void tripIdCarriesTheEntryNode() {
    String tag = new CallTag(UUID.randomUUID(), new PidsStationKey("SURC", "PPK")).format();
    OnDemandTrip.Entry entry =
        new OnDemandTrip.Entry(3, Optional.of(NodeId.of("SURC:ZKW:HHU:1:003")));
    Optional<String> withNode = Optional.of(OnDemandTrip.format(tag, Optional.of(entry)));

    assertEquals(Optional.of(entry), OnDemandTrip.entryOf(withNode));
    assertEquals(OptionalInt.of(3), OnDemandTrip.entryIndexOf(withNode));
    assertEquals(Optional.of(tag), OnDemandTrip.callTagOf(withNode));
    assertEquals(
        Optional.of(OnDemandTrip.format(tag, Optional.empty())),
        OnDemandTrip.withoutEntry(withNode),
        "放弃区间生成时连生成点一起去掉");
    assertEquals(
        Optional.of(new OnDemandTrip.Entry(4, Optional.empty())),
        OnDemandTrip.entryOf(
            Optional.of(
                OnDemandTrip.format(
                    tag, Optional.of(new OnDemandTrip.Entry(4, Optional.empty()))))),
        "只有下标的旧写法");
    OnDemandTrip.Entry slashed = new OnDemandTrip.Entry(2, Optional.of(NodeId.of("A/B:1")));
    assertEquals(
        Optional.of(slashed),
        OnDemandTrip.entryOf(Optional.of(OnDemandTrip.format(tag, Optional.of(slashed)))),
        "节点名里有斜杠也读得回来");
    assertEquals(
        Optional.empty(),
        OnDemandTrip.entryOf(
            Optional.of(
                OnDemandTrip.format(
                    tag, Optional.of(new OnDemandTrip.Entry(0, Optional.of(NodeId.of("X"))))))),
        "下标 0 不算区间生成");
  }

  @Test
  void lineFlagsReadOnlyExplicitValues() {
    assertTrue(LineCallMetadata.allowsPlayerCall(Map.of("allow_player_call", true)));
    assertTrue(LineCallMetadata.allowsPlayerCall(Map.of("allow_player_call", "TRUE")));
    assertFalse(LineCallMetadata.allowsPlayerCall(Map.of()));
    assertEquals(
        OptionalInt.of(3),
        LineCallMetadata.maxTrains(Map.of("call_max_trains", 3.0D)),
        "库里读回的数字是 Double");
    assertEquals(OptionalInt.empty(), LineCallMetadata.maxTrains(Map.of("call_max_trains", 0)));
  }
}

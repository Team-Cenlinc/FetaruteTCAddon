package org.fetarute.fetaruteTCAddon.interlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("跨服接口")
class InterlinkTest {

  private static TrainHandoff handoff(String to) {
    return new TrainHandoff(
        "h-1",
        "east",
        to,
        "g-1",
        "uid-42",
        "OP-L1-A01-0042",
        "OP:S:BBB:1",
        "members:\n- type: minecart\n",
        Optional.of(UUID.randomUUID()),
        3,
        Optional.of(
            new TrainHandoff.Timetable(UUID.randomUUID(), "1023", LocalDate.of(2026, 10, 3), 45)),
        Optional.of(new TrainHandoff.Driver(UUID.randomUUID(), 0, "ATO")),
        12.5,
        Instant.parse("2026-10-03T08:00:00Z"));
  }

  @Test
  @DisplayName("整车快照：编码再解码原样还原；版本不认识或缺字段一律拒收")
  void codec() {
    TrainHandoff original = handoff("west");
    String json = TrainHandoffCodec.encode(original);
    assertEquals(Optional.of(original), TrainHandoffCodec.decode(json));
    assertTrue(
        TrainHandoffCodec.decode(json.replace("\"formatVersion\":1", "\"formatVersion\":2"))
            .isEmpty());
    assertTrue(TrainHandoffCodec.decode(json.replace("\"trainUid\"", "\"other\"")).isEmpty());
    assertTrue(TrainHandoffCodec.decode("not json").isEmpty());
    assertTrue(TrainHandoffCodec.decode("").isEmpty());
  }

  @Test
  @DisplayName("未配置跨服：一律拒绝，对端离线")
  void noop() {
    NoopInterlinkGateway gateway = new NoopInterlinkGateway("east");
    var grant =
        gateway
            .requestBoundary(
                new InterlinkGateway.BoundaryRequest(
                    "r-1",
                    "west",
                    new RailBoundaryLink("OP:S:AAA:1", Optional.of("west"), "OP:S:BBB:1"),
                    "uid-42",
                    "T",
                    Instant.EPOCH))
            .join();
    assertFalse(grant.granted());
    assertFalse(gateway.sendHandoff(handoff("west")).join().accepted());
    assertEquals(InterlinkGateway.PeerStatus.OFFLINE, gateway.peerStatus("west"));
  }

  @Test
  @DisplayName("进程内回环：对端在线才授予，快照经编解码送达")
  void loopback() {
    LoopbackInterlinkGateway.Hub hub = new LoopbackInterlinkGateway.Hub();
    LoopbackInterlinkGateway east = new LoopbackInterlinkGateway(hub, "east");
    var request =
        new InterlinkGateway.BoundaryRequest(
            "r-1",
            "west",
            new RailBoundaryLink("OP:S:AAA:1", Optional.of("west"), "OP:S:BBB:1"),
            "uid-42",
            "T",
            Instant.EPOCH);
    assertFalse(east.requestBoundary(request).join().granted(), "对端不在线时拒绝");

    LoopbackInterlinkGateway west = new LoopbackInterlinkGateway(hub, "west");
    List<TrainHandoff> received = new ArrayList<>();
    west.onBoundaryRequest(
        req ->
            new InterlinkGateway.BoundaryGrant(
                req.requestId(), true, Optional.of("g-1"), "", Optional.empty()));
    west.onHandoff(
        h -> {
          received.add(h);
          return new InterlinkGateway.HandoffAck(h.handoffId(), true, "");
        });
    assertTrue(east.requestBoundary(request).join().granted());
    assertTrue(east.sendHandoff(handoff("west")).join().accepted());
    assertEquals(List.of(handoff("west")).get(0).trainUid(), received.get(0).trainUid());
    assertEquals(InterlinkGateway.PeerStatus.ONLINE, east.peerStatus("west"));
    west.close();
    assertEquals(InterlinkGateway.PeerStatus.OFFLINE, east.peerStatus("west"));
  }

  @Test
  @DisplayName("边界与服务器 ID")
  void boundaryAndIdentity() {
    assertFalse(
        new RailBoundaryLink("PORTAL:a:1:2:3", Optional.empty(), "PORTAL:b:1:2:3").remote());
    assertFalse(new RailBoundaryLink("A", Optional.of(" "), "B").remote(), "空白服务器 ID 视为本服");
    assertTrue(new RailBoundaryLink("A", Optional.of("west"), "B").remote());
    ServerIdentity.configure("  east ");
    assertEquals(Optional.of("east"), ServerIdentity.id());
    ServerIdentity.configure("");
    assertTrue(ServerIdentity.id().isEmpty());
  }
}

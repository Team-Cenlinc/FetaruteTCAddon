package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.standard.type.ChunkLoadOptions;
import com.bergerkiller.bukkit.tc.properties.standard.type.CollisionOptions;
import com.bergerkiller.bukkit.tc.properties.standard.type.SlowdownMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.junit.jupiter.api.Test;

/** 叫车在发车侧的规则：待命车配对、区间生成的首个 destination、出车物理属性。 */
class OnDemandSpawnTest {

  private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

  /** 叫车票接叫来的车或没绑交路的车；别的票不接叫来的车。 */
  @Test
  void calledTrainsOnlyServeCalls() {
    LayoverRegistry.LayoverCandidate called =
        candidate("called-1", Map.of(SimpleTicketAssigner.TAG_CALLED_TRAIN, "x@SURC:PPK"));
    LayoverRegistry.LayoverCandidate free = candidate("free-1", Map.of());
    LayoverRegistry.LayoverCandidate bound = candidate("bound-1", Map.of());
    List<LayoverRegistry.LayoverCandidate> all = List.of(called, free, bound);

    List<LayoverRegistry.LayoverCandidate> forCall =
        SimpleTicketAssigner.filterCalledTrains(
            ticket(TripSource.ON_DEMAND), all, name -> name.startsWith("bound"));
    List<LayoverRegistry.LayoverCandidate> forHeadway =
        SimpleTicketAssigner.filterCalledTrains(
            ticket(TripSource.SCHEDULED), all, name -> name.startsWith("bound"));

    assertEquals(List.of(called, free), forCall, "叫车票不抢绑着时刻表交路的车");
    assertEquals(List.of(free, bound), forHeadway, "别的票不接叫来的车");
  }

  /** 区间生成：首个 destination 是生成点的下一个节点。 */
  @Test
  void entrySpawnHeadsForTheNodeAfterTheEntry() {
    TrainProperties properties = mock(TrainProperties.class);
    List<NodeId> nodes = List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C"), NodeId.of("D"));

    assertTrue(SimpleTicketAssigner.applyPreparedSpawnDestination(properties, nodes, 2));
    verify(properties).setDestination("D");
    assertFalse(
        SimpleTicketAssigner.applyPreparedSpawnDestination(properties, nodes, 3), "末节点之后没有下一站");
  }

  /** 出车物理属性：关摩擦、重力、碰撞，常驻加载用最小范围。 */
  @Test
  void spawnPhysicsDisablesFrictionGravityAndCollision() {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getChunkLoadOptions()).thenReturn(ChunkLoadOptions.DEFAULT);

    assertTrue(SpawnPhysicsProperties.apply(properties));
    verify(properties).setSlowingDown(SlowdownMode.FRICTION, false);
    verify(properties).setSlowingDown(SlowdownMode.GRAVITY, false);
    verify(properties).setCollision(CollisionOptions.CANCEL);
    verify(properties)
        .setChunkLoadOptions(ChunkLoadOptions.DEFAULT.withMode(ChunkLoadOptions.Mode.MINIMAL));
  }

  /** 属性写不上不冒泡：物理编组已经存在，出车事务只看返回值。 */
  @Test
  void spawnPhysicsSwallowsFailures() {
    TrainProperties properties = mock(TrainProperties.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("unloaded"))
        .when(properties)
        .setCollision(any());

    assertFalse(SpawnPhysicsProperties.apply(properties));
    assertFalse(SpawnPhysicsProperties.apply(null));
  }

  private static LayoverRegistry.LayoverCandidate candidate(String name, Map<String, String> tags) {
    return new LayoverRegistry.LayoverCandidate(
        name, "SURC:PPK", NodeId.of("SURC:S:PPK:1"), NOW, tags);
  }

  private static SpawnTicket ticket(TripSource source) {
    return new SpawnTicket(
        UUID.randomUUID(),
        new SpawnService(
            new SpawnServiceKey(UUID.randomUUID()),
            UUID.randomUUID(),
            "C",
            UUID.randomUUID(),
            "SURC",
            UUID.randomUUID(),
            "WS",
            UUID.randomUUID(),
            "WS-1",
            Duration.ofMinutes(10),
            "SURC:S:AAA:1"),
        NOW,
        NOW,
        NOW,
        0,
        0L,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        source,
        0,
        Optional.empty());
  }
}

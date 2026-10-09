package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

class RouteProgressRegistryTest {

  /**
   * 到达锚点：只由带现场证据的到达写入，驶过后续图节点不丢，推进到别的索引、换交路、移除时作废，改名时跟着走。
   *
   * <p>它与 {@code lastPassedGraphNode} 不是同一个量——后者车头每过一个图节点就被覆盖，出站之后就不再记得走的是哪条股道。
   */
  @Test
  void arrivalAnchorSurvivesPassingNodesAndExpiresWithTheIndex() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"),
            List.of(NodeId.of("OP:S:A:1"), NodeId.of("OP:S:B:1"), NodeId.of("OP:S:C:1")),
            Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    TagStore store = new TagStore("FTA_ROUTE_INDEX=0");
    NodeId trackTwo = NodeId.of("OP:S:B:2");

    registry.initFromTags("train-1", store.properties(), route);
    assertEquals(Optional.empty(), registry.arrivalNodeAt("train-1", 0), "初始化没有现场证据");

    registry.recordArrival(
        "train-1", null, route, 1, trackTwo, store.properties(), Instant.ofEpochMilli(1000));
    registry.updateLastPassedGraphNode(
        "train-1", NodeId.of("SWITCHER:B:E"), Instant.ofEpochMilli(1100));
    assertEquals(Optional.of(trackTwo), registry.arrivalNodeAt("train-1", 1));
    assertEquals(Optional.empty(), registry.arrivalNodeAt("train-1", 0), "只对到达的那个索引成立");

    registry.advance("train-1", null, route, 1, store.properties(), Instant.ofEpochMilli(1200));
    assertEquals(Optional.of(trackTwo), registry.arrivalNodeAt("train-1", 1), "同索引无证据的刷新沿用锚点");

    assertTrue(registry.rename("train-1", "train-renamed"));
    assertEquals(Optional.of(trackTwo), registry.arrivalNodeAt("train-renamed", 1));
    assertEquals(Optional.empty(), registry.arrivalNodeAt("train-1", 1));

    registry.advance(
        "train-renamed", null, route, 2, store.properties(), Instant.ofEpochMilli(1300));
    assertEquals(Optional.empty(), registry.arrivalNodeAt("train-renamed", 1));
    assertEquals(Optional.empty(), registry.arrivalNodeAt("train-renamed", 2), "推进没有现场证据");

    registry.recordArrival(
        "train-renamed",
        null,
        route,
        2,
        NodeId.of("OP:S:C:1"),
        store.properties(),
        Instant.ofEpochMilli(1400));
    registry.remove("train-renamed");
    assertEquals(Optional.empty(), registry.arrivalNodeAt("train-renamed", 2));
  }

  @Test
  void initFromTagsRestoresIndex() {
    UUID routeId = UUID.randomUUID();
    TagStore store =
        new TagStore("FTA_ROUTE_ID=" + routeId, "FTA_ROUTE_INDEX=1", "FTA_ROUTE_UPDATED_AT=123");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    RouteProgressRegistry.RouteProgressEntry entry =
        registry.initFromTags("train-1", store.properties(), route);

    assertEquals(1, entry.currentIndex());
    assertEquals(Optional.of(NodeId.of("C")), entry.nextTarget());
  }

  @Test
  void advanceWritesBackTags() {
    UUID routeId = UUID.randomUUID();
    TagStore store = new TagStore("FTA_ROUTE_ID=" + routeId);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());

    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.advance("train-1", routeId, route, 1, store.properties(), Instant.ofEpochMilli(1000));

    assertEquals(
        Optional.of(1),
        TrainTagHelper.readIntTag(store.properties(), RouteProgressRegistry.TAG_ROUTE_INDEX));
    assertTrue(
        TrainTagHelper.readTagValue(store.properties(), RouteProgressRegistry.TAG_ROUTE_UPDATED_AT)
            .isPresent());
  }

  @Test
  void updateSignalReportsMissingEntry() {
    RouteProgressRegistry registry = new RouteProgressRegistry();
    boolean updated =
        registry.updateSignal("train-1", SignalAspect.STOP, Instant.ofEpochMilli(1000));

    assertFalse(updated);
  }

  @Test
  void upsertPreservesLastSignal() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"),
            List.of(NodeId.of("A"), NodeId.of("B"), NodeId.of("C")),
            Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    TagStore store = new TagStore("FTA_ROUTE_INDEX=0");
    registry.initFromTags("train-1", store.properties(), route);

    registry.updateSignal("train-1", SignalAspect.CAUTION, Instant.ofEpochMilli(1000));
    RouteProgressRegistry.RouteProgressEntry advanced =
        registry.advance("train-1", null, route, 1, store.properties(), Instant.ofEpochMilli(1100));

    assertEquals(SignalAspect.CAUTION, advanced.lastSignal());
  }

  @Test
  void getAndRemoveAreCaseInsensitive() {
    UUID routeId = UUID.randomUUID();
    TagStore store = new TagStore("FTA_ROUTE_ID=" + routeId, "FTA_ROUTE_INDEX=1");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("Train-Case", store.properties(), route);

    assertTrue(registry.get("train-case").isPresent());
    assertTrue(registry.get("TRAIN-CASE").isPresent());

    registry.remove("TRAIN-case");
    assertTrue(registry.get("train-case").isEmpty());
  }

  @Test
  void renameUpdatesEntryWhenOnlyCaseChanges() {
    UUID routeId = UUID.randomUUID();
    TagStore store = new TagStore("FTA_ROUTE_ID=" + routeId, "FTA_ROUTE_INDEX=0");
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    registry.initFromTags("Train-A", store.properties(), route);

    assertTrue(registry.rename("Train-A", "train-a"));
    assertTrue(registry.get("TRAIN-A").isPresent());
    assertEquals("train-a", registry.get("train-a").orElseThrow().trainName());
  }

  /**
   * 交路中途生成的车：生成点在两个交路节点之间（交路节点表里没有它）时，最后经过的图节点记为生成点，运行时据此从生成点起算授权； 生成点就是交路节点、生成位置标记已不是
   * pending、在首节点出车时都照常按交路节点认位置。
   */
  @Test
  void entrySpawnedTrainIsAnchoredAtItsSpawnNode() {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("route"),
            List.of(
                NodeId.of("OP:D:DEP:1"),
                NodeId.of("OP:S:A:1"),
                NodeId.of("OP:S:B:1"),
                NodeId.of("OP:S:C:1")),
            Optional.empty());
    RouteProgressRegistry registry = new RouteProgressRegistry();
    TagStore between =
        new TagStore(
            "FTA_ROUTE_INDEX=1", "FTA_DEPOT_ID=OP:A:B:1:003", "FTA_SPAWN_ORIGIN_PENDING=true");

    registry.initFromTags("entry-1", between.properties(), route);

    assertEquals(
        Optional.of(NodeId.of("OP:A:B:1:003")),
        registry.get("entry-1").orElseThrow().lastPassedGraphNode());
    assertEquals(1, registry.get("entry-1").orElseThrow().currentIndex());

    assertEquals(
        Optional.empty(),
        RouteProgressRegistry.entrySpawnNode(
            new TagStore(
                    "FTA_ROUTE_INDEX=1", "FTA_DEPOT_ID=OP:S:A:1", "FTA_SPAWN_ORIGIN_PENDING=true")
                .properties(),
            route,
            1),
        "生成点就是交路节点");
    assertEquals(
        Optional.empty(),
        RouteProgressRegistry.entrySpawnNode(
            new TagStore(
                    "FTA_ROUTE_INDEX=1",
                    "FTA_DEPOT_ID=OP:A:B:1:003",
                    "FTA_SPAWN_ORIGIN_PENDING=false")
                .properties(),
            route,
            1),
        "车已离开生成点");
    assertEquals(
        Optional.empty(),
        RouteProgressRegistry.entrySpawnNode(
            new TagStore(
                    "FTA_ROUTE_INDEX=0", "FTA_DEPOT_ID=OP:D:DEP:2", "FTA_SPAWN_ORIGIN_PENDING=true")
                .properties(),
            route,
            0),
        "车库出车照旧由 CRET 位置恢复处理");
  }

  private static final class TagStore {
    private final TrainProperties properties;
    private final List<String> tags;

    private TagStore(String... initial) {
      this.tags = new ArrayList<>(Arrays.asList(initial));
      this.properties = mock(TrainProperties.class);
      when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
      doAnswer(
              inv -> {
                tags.addAll(extractTags(inv.getArgument(0)));
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      doAnswer(
              inv -> {
                tags.removeAll(extractTags(inv.getArgument(0)));
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    private TrainProperties properties() {
      return properties;
    }

    private static List<String> extractTags(Object arg) {
      if (arg == null) {
        return List.of();
      }
      if (arg instanceof String[] values) {
        return Arrays.asList(values);
      }
      if (arg instanceof String value) {
        return List.of(value);
      }
      return List.of();
    }
  }
}

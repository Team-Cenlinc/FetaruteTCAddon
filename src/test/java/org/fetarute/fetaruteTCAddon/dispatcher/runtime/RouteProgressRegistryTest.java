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

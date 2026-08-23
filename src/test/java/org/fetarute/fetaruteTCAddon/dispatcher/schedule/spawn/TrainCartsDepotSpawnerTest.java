package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainSpawnTagInitializer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.junit.jupiter.api.Test;

class TrainCartsDepotSpawnerTest {

  @Test
  void spawnOwnerIdentityOverridesInheritedTemplateTagBeforeSignalRefresh() {
    MutableTrainProperties train =
        new MutableTrainProperties(
            "train1", "  FTA_TRAIN_NAME = train1  ", "FTA_ROUTE_CODE=template-route");

    TrainCartsDepotSpawner.initializeSpawnOwner(train.properties(), "SURC-WS-LC-9420");

    assertEquals("SURC-WS-LC-9420", train.properties().getTrainName());
    assertEquals(
        "SURC-WS-LC-9420",
        TrainTagHelper.readTagValue(train.properties(), RouteProgressRegistry.TAG_TRAIN_NAME)
            .orElseThrow());
    assertEquals(1L, train.countTag(RouteProgressRegistry.TAG_TRAIN_NAME));
  }

  @Test
  void spawnOwnerTagSurvivesTrainRenameFailureForStartupRecovery() {
    MutableTrainProperties train = new MutableTrainProperties("train1");
    doThrow(new IllegalStateException("rename-failed"))
        .when(train.properties())
        .setTrainName(anyString());

    assertThrows(
        IllegalStateException.class,
        () -> TrainCartsDepotSpawner.initializeSpawnOwner(train.properties(), "SURC-DS-LW-1001"));

    assertEquals(
        "SURC-DS-LW-1001",
        TrainTagHelper.readTagValue(train.properties(), RouteProgressRegistry.TAG_TRAIN_NAME)
            .orElseThrow());
  }

  @Test
  void spawnLifecycleTagsReplaceInheritedTemplateStateAndClearControlState() {
    MutableTrainProperties train =
        new MutableTrainProperties(
            "train1",
            "FTA_DEPOT_ID=SURC:D:LWN:2",
            "FTA_DEPOT_ID=SURC:D:OLD:1",
            "FTA_ROUTE_ID=old-route",
            "FTA_RUN_ID=old-run",
            "FTA_DEST_CODE=OLD",
            "FTA_DEST_NAME=Old_Terminal",
            "FTA_ROUTE_INDEX=9",
            "FTA_ROUTE_UPDATED_AT=9999999999999",
            "FTA_OP_TRIPS=99",
            "FTA_OP_MAX=100",
            "FTA_SPAWN_GROUP=old-group",
            "FTA_TICKET_ID=old-ticket",
            TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING + "=true",
            "FTA_LAST_LAUNCH_AT=9999999999999",
            "FTA_LAST_SPEED_CMD_BPS=20.0",
            "FTA_LAST_SPEED_CMD_AT=9999999999999",
            "FTA_DOOR_FIRST_STOP_DONE=true",
            "FTA_MANUAL_HOLD=true",
            "FTA_PRIORITY=42",
            "FTA_BYPASS");
    Map<String, String> current =
        Map.of(
            "FTA_RUN_ID",
            "new-run",
            "FTA_ROUTE_ID",
            "new-route",
            "FTA_ROUTE_CODE",
            "MT-1N_Short",
            "FTA_LINE_CODE",
            "MT-1N",
            "FTA_OPERATOR_CODE",
            "SURC",
            "FTA_PATTERN",
            "CREATE",
            "FTA_DEPOT_ID",
            "SURC:D:OFL:1",
            TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING,
            "true",
            "FTA_SPAWN_PATTERN",
            "metro-pattern",
            "FTA_RUN_AT",
            "123456789");

    TrainSpawnTagInitializer.replaceLifecycleTags(train.properties(), current);

    assertEquals(
        "SURC:D:OFL:1",
        TrainTagHelper.readTagValue(train.properties(), "FTA_DEPOT_ID").orElseThrow());
    assertEquals(
        "new-route", TrainTagHelper.readTagValue(train.properties(), "FTA_ROUTE_ID").orElseThrow());
    for (String key : current.keySet()) {
      assertEquals(1L, train.countTag(key), () -> key + " 必须只有一个规范值");
    }
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_DEST_CODE").isPresent());
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_DEST_NAME").isPresent());
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_ROUTE_INDEX").isPresent());
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_OP_TRIPS").isPresent());
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_TICKET_ID").isPresent());
    assertFalse(
        TrainTagHelper.readTagValue(
                train.properties(), TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING)
            .isPresent());
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_LAST_LAUNCH_AT").isPresent());
    assertFalse(
        TrainTagHelper.readTagValue(train.properties(), "FTA_LAST_SPEED_CMD_BPS").isPresent());
    assertFalse(
        TrainTagHelper.readTagValue(train.properties(), "FTA_LAST_SPEED_CMD_AT").isPresent());
    assertFalse(
        TrainTagHelper.readTagValue(train.properties(), "FTA_DOOR_FIRST_STOP_DONE").isPresent());
    assertFalse(TrainTagHelper.readTagValue(train.properties(), "FTA_MANUAL_HOLD").isPresent());
    assertEquals(
        "42", TrainTagHelper.readTagValue(train.properties(), "FTA_PRIORITY").orElseThrow());
    assertEquals(1L, train.countTag("FTA_PRIORITY"), "保存模板声明的人工调度优先级必须保留");
    assertEquals(1L, train.countTag("FTA_BYPASS"), "保存模板声明的牌子旁路能力必须保留");
  }

  /** 提供可变 TrainProperties 名称与 tag，复现 spawn pattern 继承模板身份的真实行为。 */
  private static final class MutableTrainProperties {
    private final TrainProperties properties = mock(TrainProperties.class);
    private final AtomicReference<String> trainName;
    private final List<String> tags;

    private MutableTrainProperties(String initialTrainName, String... initialTags) {
      trainName = new AtomicReference<>(initialTrainName);
      tags = new ArrayList<>(Arrays.asList(initialTags));
      when(properties.getTrainName()).thenAnswer(ignored -> trainName.get());
      when(properties.hasTags()).thenAnswer(ignored -> !tags.isEmpty());
      when(properties.getTags()).thenAnswer(ignored -> List.copyOf(tags));
      doAnswer(
              invocation -> {
                trainName.set(invocation.getArgument(0));
                return null;
              })
          .when(properties)
          .setTrainName(anyString());
      doAnswer(
              invocation -> {
                for (Object argument : invocation.getArguments()) {
                  if (argument instanceof String tag) {
                    tags.add(tag);
                  }
                }
                return null;
              })
          .when(properties)
          .addTags(any(String[].class));
      doAnswer(
              invocation -> {
                for (Object argument : invocation.getArguments()) {
                  if (argument instanceof String tag) {
                    tags.remove(tag);
                  }
                }
                return null;
              })
          .when(properties)
          .removeTags(any(String[].class));
    }

    private TrainProperties properties() {
      return properties;
    }

    private long countTag(String key) {
      String prefix = key + "=";
      return tags.stream()
          .map(tag -> tag == null ? "" : tag.trim())
          .filter(tag -> tag.equals(key) || tag.startsWith(prefix))
          .count();
    }
  }
}

package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.junit.jupiter.api.Test;

class TrainConfigResolverTest {

  @Test
  void resolvesFromTagsWhenPresent() {
    TagStore store =
        new TagStore(
            "FTA_TRAIN_TYPE=DMU", "FTA_TRAIN_ACCEL_BPS2=0.55", "FTA_TRAIN_DECEL_BPS2=0.75");
    TrainConfigResolver resolver = new TrainConfigResolver();
    TrainConfig config = resolver.resolve(store.properties(), defaultConfig());

    assertEquals(TrainType.DMU, config.type());
    assertEquals(0.55, config.accelBps2());
    assertEquals(0.75, config.decelBps2());
  }

  @Test
  void writeConfigUpdatesTags() {
    TagStore store = new TagStore();
    TrainConfigResolver resolver = new TrainConfigResolver();
    TrainConfig config = new TrainConfig(TrainType.EMU, 0.8, 1.0);

    resolver.writeConfig(store.properties(), config, Optional.empty(), Optional.of(1.2));

    assertEquals(
        Optional.of("EMU"),
        TrainTagHelper.readTagValue(store.properties(), TrainConfigResolver.TAG_TRAIN_TYPE));
    assertEquals(
        Optional.of("0.8"),
        TrainTagHelper.readTagValue(store.properties(), TrainConfigResolver.TAG_TRAIN_ACCEL_BPS2));
    assertEquals(
        Optional.of("1.2"),
        TrainTagHelper.readTagValue(store.properties(), TrainConfigResolver.TAG_TRAIN_DECEL_BPS2));
    assertTrue(
        TrainTagHelper.readTagValue(store.properties(), TrainConfigResolver.TAG_TRAIN_CONFIG_AT)
            .isPresent());
  }

  private static ConfigManager.ConfigView defaultConfig() {
    return new ConfigManager.ConfigView(
        4,
        false,
        "zh_CN",
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("data/fetarute.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000)),
        new ConfigManager.GraphSettings(8.0),
        new ConfigManager.AutoStationSettings("BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.2f),
        new ConfigManager.RuntimeSettings(10, 2, 3, 4.0, 6.0),
        new ConfigManager.TrainConfigSettings(
            "emu",
            new ConfigManager.TrainTypeSettings(0.8, 1.0),
            new ConfigManager.TrainTypeSettings(0.7, 0.9),
            new ConfigManager.TrainTypeSettings(0.6, 0.8),
            new ConfigManager.TrainTypeSettings(0.9, 1.1)));
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

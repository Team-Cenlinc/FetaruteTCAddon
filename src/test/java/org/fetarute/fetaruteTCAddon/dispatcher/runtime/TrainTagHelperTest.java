package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainTagHelperTest {

  @Test
  void writeTagSkipsWhenSameValueAlreadyPresent() {
    List<String> tags = new ArrayList<>(List.of("FTA_TRAIN_NAME=T1", "OTHER=1"));
    TrainProperties properties = properties(tags);

    TrainTagHelper.writeTag(properties, "FTA_TRAIN_NAME", "T1");

    verify(properties, never()).removeTags(any(String[].class));
    verify(properties, never()).addTags(any(String[].class));
    assertEquals(List.of("FTA_TRAIN_NAME=T1", "OTHER=1"), tags);
  }

  @Test
  void writeTagReplacesChangedValue() {
    List<String> tags = new ArrayList<>(List.of("FTA_TRAIN_NAME=T1", "OTHER=1"));

    TrainTagHelper.writeTag(properties(tags), "FTA_TRAIN_NAME", "T2");

    assertEquals(List.of("OTHER=1", "FTA_TRAIN_NAME=T2"), tags);
  }

  @Test
  void writeTagCollapsesDuplicateKeysEvenWhenOneMatches() {
    List<String> tags = new ArrayList<>(List.of("FTA_TRAIN_NAME=T1", "fta_train_name=T0"));

    TrainTagHelper.writeTag(properties(tags), "FTA_TRAIN_NAME", "T1");

    assertEquals(List.of("FTA_TRAIN_NAME=T1"), tags);
  }

  @Test
  void writeTagRewritesSameValueWithDifferentSpelling() {
    List<String> tags = new ArrayList<>(List.of(" FTA_TRAIN_NAME = T1 "));

    TrainTagHelper.writeTag(properties(tags), "FTA_TRAIN_NAME", "T1");

    assertEquals(List.of("FTA_TRAIN_NAME=T1"), tags);
  }

  @Test
  void writeTagAddsMissingKey() {
    List<String> tags = new ArrayList<>();

    TrainTagHelper.writeTag(properties(tags), "FTA_TRAIN_NAME", "T1");

    assertEquals(List.of("FTA_TRAIN_NAME=T1"), tags);
  }

  private static TrainProperties properties(List<String> tags) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.hasTags()).thenAnswer(inv -> !tags.isEmpty());
    when(properties.getTags()).thenAnswer(inv -> List.copyOf(tags));
    doAnswer(
            inv -> {
              for (Object arg : inv.getArguments()) {
                if (arg instanceof String s) {
                  tags.add(s);
                }
              }
              return null;
            })
        .when(properties)
        .addTags(any(String[].class));
    doAnswer(
            inv -> {
              for (Object arg : inv.getArguments()) {
                if (arg instanceof String s) {
                  tags.remove(s);
                }
              }
              return null;
            })
        .when(properties)
        .removeTags(any(String[].class));
    return properties;
  }
}

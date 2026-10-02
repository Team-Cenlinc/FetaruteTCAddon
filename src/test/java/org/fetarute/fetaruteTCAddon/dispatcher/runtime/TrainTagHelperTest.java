package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrainTagHelperTest {

  @Test
  void writeTagSkipsWhenEveryCartAlreadyHasSameValue() {
    List<Set<String>> carts =
        List.of(cart("FTA_TRAIN_NAME=T1", "OTHER=1"), cart("FTA_TRAIN_NAME=T1", "OTHER=1"));
    TrainProperties properties = properties(carts);

    TrainTagHelper.writeTag(properties, "FTA_TRAIN_NAME", "T1");

    verify(properties, never()).removeTags(any(String[].class));
    verify(properties, never()).addTags(any(String[].class));
  }

  @Test
  void writeTagFillsCartThatLacksTheTag() {
    Set<String> tagged = cart("FTA_TRAIN_NAME=T1");
    Set<String> joined = cart();

    TrainTagHelper.writeTag(properties(List.of(tagged, joined)), "FTA_TRAIN_NAME", "T1");

    assertEquals(Set.of("FTA_TRAIN_NAME=T1"), tagged);
    assertEquals(Set.of("FTA_TRAIN_NAME=T1"), joined);
  }

  @Test
  void writeTagReplacesChangedValue() {
    Set<String> tags = cart("FTA_TRAIN_NAME=T1", "OTHER=1");

    TrainTagHelper.writeTag(properties(List.of(tags)), "FTA_TRAIN_NAME", "T2");

    assertEquals(Set.of("OTHER=1", "FTA_TRAIN_NAME=T2"), tags);
  }

  @Test
  void writeTagCollapsesDuplicateKeysEvenWhenOneMatches() {
    Set<String> tags = cart("FTA_TRAIN_NAME=T1", "fta_train_name=T0");

    TrainTagHelper.writeTag(properties(List.of(tags)), "FTA_TRAIN_NAME", "T1");

    assertEquals(Set.of("FTA_TRAIN_NAME=T1"), tags);
  }

  @Test
  void writeTagRewritesSameValueWithDifferentSpelling() {
    Set<String> tags = cart(" FTA_TRAIN_NAME = T1 ");

    TrainTagHelper.writeTag(properties(List.of(tags)), "FTA_TRAIN_NAME", "T1");

    assertEquals(Set.of("FTA_TRAIN_NAME=T1"), tags);
  }

  @Test
  void writeTagAddsMissingKey() {
    Set<String> tags = cart();

    TrainTagHelper.writeTag(properties(List.of(tags)), "FTA_TRAIN_NAME", "T1");

    assertEquals(Set.of("FTA_TRAIN_NAME=T1"), tags);
  }

  private static Set<String> cart(String... tags) {
    return new LinkedHashSet<>(List.of(tags));
  }

  /** 列车级增删作用到每节车厢、读取取并集，与 TrainCarts 一致。 */
  private static TrainProperties properties(List<Set<String>> carts) {
    List<CartProperties> cartProperties = new ArrayList<>();
    for (Set<String> tags : carts) {
      CartProperties cart = mock(CartProperties.class);
      when(cart.getTags()).thenAnswer(inv -> Set.copyOf(tags));
      cartProperties.add(cart);
    }
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.iterator()).thenAnswer(inv -> cartProperties.iterator());
    when(properties.hasTags()).thenAnswer(inv -> carts.stream().anyMatch(t -> !t.isEmpty()));
    when(properties.getTags())
        .thenAnswer(
            inv -> {
              Set<String> union = new LinkedHashSet<>();
              carts.forEach(union::addAll);
              return union;
            });
    doAnswer(
            inv -> {
              for (Object arg : inv.getArguments()) {
                if (arg instanceof String s) {
                  carts.forEach(tags -> tags.add(s));
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
                  carts.forEach(tags -> tags.remove(s));
                }
              }
              return null;
            })
        .when(properties)
        .removeTags(any(String[].class));
    return properties;
  }
}

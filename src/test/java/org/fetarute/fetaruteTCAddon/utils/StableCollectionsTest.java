package org.fetarute.fetaruteTCAddon.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link StableCollections} 的顺序契约。
 *
 * <p>输入刻意用八个元素的逆序：{@code Set.copyOf} 对它给出的顺序由每个 JVM 随机一次的 SALT 决定，恰好等于插入序的概率约 1/40320，所以若实现退回
 * {@code Set.copyOf}，这里几乎必然失败。
 */
class StableCollectionsTest {

  private static final List<String> REVERSED = List.of("h", "g", "f", "e", "d", "c", "b", "a");

  @Test
  void setCopyKeepsInsertionOrderAndDropsLaterDuplicates() {
    List<String> withDuplicates = new ArrayList<>(REVERSED);
    withDuplicates.add("e");
    withDuplicates.add("h");

    assertEquals(REVERSED, List.copyOf(StableCollections.copyInInsertionOrder(withDuplicates)));
  }

  @Test
  void mapCopyKeepsInsertionOrder() {
    Map<String, Integer> source = new LinkedHashMap<>();
    for (int i = 0; i < REVERSED.size(); i++) {
      source.put(REVERSED.get(i), i);
    }

    Map<String, Integer> copy = StableCollections.copyInInsertionOrder(source);

    assertEquals(REVERSED, List.copyOf(copy.keySet()));
    assertEquals(source, copy);
  }

  @Test
  void sortedCopyIgnoresSourceOrder() {
    Set<String> hashOrdered = new HashSet<>(REVERSED);

    assertEquals(
        List.of("a", "b", "c", "d", "e", "f", "g", "h"),
        List.copyOf(StableCollections.copySorted(hashOrdered, Comparator.naturalOrder())));
  }

  /** 比较器判等的不同元素保持来源中的相对顺序——所以比较器应当与 equals 一致，这里只是把"稳定"钉住。 */
  @Test
  void sortedCopyIsStableForComparatorTies() {
    List<String> source = List.of("bb", "a", "cc", "b", "aa", "c");

    assertEquals(
        List.of("a", "b", "c", "bb", "cc", "aa"),
        List.copyOf(StableCollections.copySorted(source, Comparator.comparingInt(String::length))));
  }

  @Test
  void rejectsNullsLikeTheImmutableCopiesItReplaces() {
    assertThrows(
        NullPointerException.class,
        () -> StableCollections.copyInInsertionOrder(Arrays.asList("a", null)));
    assertThrows(
        NullPointerException.class,
        () -> StableCollections.copySorted(Arrays.asList("a", null), Comparator.naturalOrder()));
    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("a", null);
    assertThrows(
        NullPointerException.class, () -> StableCollections.copyInInsertionOrder(nullValue));
  }

  @Test
  void copiesAreUnmodifiable() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> StableCollections.copyInInsertionOrder(REVERSED).add("z"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> StableCollections.copySorted(REVERSED, Comparator.naturalOrder()).remove("a"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> StableCollections.copyInInsertionOrder(Map.of("a", 1)).put("b", 2));
  }
}

package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link TrainNameNormalizer} 的 TrainCarts 临时别名归一化测试。 */
class TrainNameNormalizerTest {

  @Test
  void nestedSplitAliasesResolveToCanonicalOwner() {
    assertEquals("train-main", TrainNameNormalizer.normalizeKey("Train-Main~a~B"));
    assertTrue(TrainNameNormalizer.sameLogicalTrain("train-main~a~b", "TRAIN-MAIN"));
  }

  @Test
  void invalidOrLongSuffixRemainsPartOfTrainName() {
    assertEquals("train-main~split", TrainNameNormalizer.normalizeKey("train-main~split"));
    assertEquals("train-main~a-b", TrainNameNormalizer.normalizeKey("train-main~a-b"));
  }
}

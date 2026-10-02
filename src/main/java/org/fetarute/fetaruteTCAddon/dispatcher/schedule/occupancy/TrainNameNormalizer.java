package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.Locale;

/** 列车名归一化工具，统一处理大小写与 TrainCarts split 临时别名。 */
public final class TrainNameNormalizer {

  private TrainNameNormalizer() {}

  public static String normalizeKey(String trainName) {
    if (trainName == null) {
      return "";
    }
    String trimmed = trainName.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    String normalized = trimmed.toLowerCase(Locale.ROOT);
    int split = normalized.lastIndexOf('~');
    while (split > 0
        && split + 1 < normalized.length()
        && looksLikeSplitSuffix(normalized.substring(split + 1))) {
      normalized = normalized.substring(0, split);
      split = normalized.lastIndexOf('~');
    }
    return normalized;
  }

  public static boolean sameLogicalTrain(String first, String second) {
    String left = normalizeKey(first);
    String right = normalizeKey(second);
    return !left.isEmpty() && left.equals(right);
  }

  private static boolean looksLikeSplitSuffix(String suffix) {
    if (suffix.length() > 3) {
      return false;
    }
    for (int i = 0; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (!Character.isLetterOrDigit(c)) {
        return false;
      }
    }
    return true;
  }
}

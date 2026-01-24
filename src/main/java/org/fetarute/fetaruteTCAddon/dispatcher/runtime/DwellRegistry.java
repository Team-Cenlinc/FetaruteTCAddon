package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 记录列车停站的剩余停站时间（秒）。
 *
 * <p>HUD 与 ETA 通过该表判断 AT_STATION 状态与停站耗时。
 */
public final class DwellRegistry {

  private final ConcurrentMap<String, DwellEntry> entries = new ConcurrentHashMap<>();

  public void start(String trainName, int dwellSeconds) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    if (dwellSeconds <= 0) {
      entries.remove(trainName);
      return;
    }
    long now = System.currentTimeMillis();
    long endAtMillis = now + (long) dwellSeconds * 1000L;
    entries.put(trainName, new DwellEntry(endAtMillis));
  }

  public void clear(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    entries.remove(trainName);
  }

  public Optional<Integer> remainingSeconds(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    DwellEntry entry = entries.get(trainName);
    if (entry == null) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    long remainingMillis = entry.endAtMillis() - now;
    if (remainingMillis <= 0L) {
      entries.remove(trainName);
      return Optional.empty();
    }
    int remainingSec = (int) ((remainingMillis + 999L) / 1000L);
    return Optional.of(remainingSec);
  }

  public void retain(Set<String> activeTrainNames) {
    if (activeTrainNames == null || activeTrainNames.isEmpty()) {
      entries.clear();
      return;
    }
    entries.keySet().retainAll(activeTrainNames);
  }

  private record DwellEntry(long endAtMillis) {}
}

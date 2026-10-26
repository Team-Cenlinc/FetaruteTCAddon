package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 运行时列车快照存储：trainId -> {@link TrainRuntimeSnapshot}。 */
public final class TrainSnapshotStore {

  private final ConcurrentMap<String, TrainRuntimeSnapshot> snapshots = new ConcurrentHashMap<>();

  /** 每次写入或移除递增；按它判断两次读取之间快照有没有变过。 */
  private final AtomicLong version = new AtomicLong();

  public Optional<TrainRuntimeSnapshot> getSnapshot(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(snapshots.get(trainName));
  }

  public void update(String trainName, TrainRuntimeSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    snapshots.put(trainName, snapshot);
    version.incrementAndGet();
  }

  public void remove(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    if (snapshots.remove(trainName) != null) {
      version.incrementAndGet();
    }
  }

  public Map<String, TrainRuntimeSnapshot> snapshot() {
    return Map.copyOf(snapshots);
  }

  /** 快照版本：任何一列车的快照写入或移除都会让它变。 */
  public long version() {
    return version.get();
  }
}

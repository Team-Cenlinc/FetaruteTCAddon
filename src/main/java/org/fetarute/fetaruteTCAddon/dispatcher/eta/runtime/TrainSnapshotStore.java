package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 运行时列车快照存储：trainId -> {@link TrainRuntimeSnapshot}。 */
public final class TrainSnapshotStore {

  private final ConcurrentMap<String, TrainRuntimeSnapshot> snapshots = new ConcurrentHashMap<>();

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
  }

  public void remove(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    snapshots.remove(trainName);
  }

  public Map<String, TrainRuntimeSnapshot> snapshot() {
    return Map.copyOf(snapshots);
  }
}

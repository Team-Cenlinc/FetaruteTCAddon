package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 采集图构建过程中的重复 nodeId（仅用于诊断输出，不参与持久化）。 */
final class DuplicateNodeIdCollector {

  private final Map<String, DuplicateNodeId.Occurrence> firstSeen = new HashMap<>();
  private final Map<String, LinkedHashMap<String, DuplicateNodeId.Occurrence>> duplicates =
      new HashMap<>();

  void record(NodeId nodeId, DuplicateNodeId.Occurrence occurrence) {
    Objects.requireNonNull(occurrence, "occurrence");
    if (nodeId == null) {
      return;
    }
    String key = nodeId.value();

    DuplicateNodeId.Occurrence first = firstSeen.get(key);
    if (first == null) {
      firstSeen.put(key, occurrence);
      return;
    }

    if (sameOccurrence(first, occurrence)) {
      return;
    }

    LinkedHashMap<String, DuplicateNodeId.Occurrence> list =
        duplicates.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
    list.putIfAbsent(occKey(first), first);
    list.putIfAbsent(occKey(occurrence), occurrence);
  }

  List<DuplicateNodeId> duplicates() {
    List<DuplicateNodeId> out = new ArrayList<>();
    for (var entry : duplicates.entrySet()) {
      NodeId nodeId = NodeId.of(entry.getKey());
      out.add(new DuplicateNodeId(nodeId, List.copyOf(entry.getValue().values())));
    }
    out.sort(Comparator.comparing(d -> d.nodeId().value()));
    return List.copyOf(out);
  }

  private static boolean sameOccurrence(
      DuplicateNodeId.Occurrence a, DuplicateNodeId.Occurrence b) {
    return a.x() == b.x()
        && a.y() == b.y()
        && a.z() == b.z()
        && a.virtualSign() == b.virtualSign()
        && a.nodeType() == b.nodeType();
  }

  private static String occKey(DuplicateNodeId.Occurrence occurrence) {
    return occurrence.nodeType().name()
        + "@"
        + occurrence.x()
        + ":"
        + occurrence.y()
        + ":"
        + occurrence.z()
        + ":"
        + (occurrence.virtualSign() ? "v" : "r");
  }
}

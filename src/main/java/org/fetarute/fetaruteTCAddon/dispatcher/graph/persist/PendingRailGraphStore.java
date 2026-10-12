package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/** 未激活调度图的本地缓存，与生效中的 SQL 快照隔离。 */
public final class PendingRailGraphStore {
  private static final int FORMAT_VERSION = 2;
  private static final Gson GSON = new Gson();
  private final Path directory;

  public PendingRailGraphStore(Path directory) {
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  /** 候选及其合并基线；基线变化后必须重新构建，不能覆盖后来更新的图。 */
  public record Candidate(
      RailGraphBuildResult result, Optional<Instant> baseBuiltAt, String baseNodeSignature) {
    public Candidate {
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(baseBuiltAt, "baseBuiltAt");
      Objects.requireNonNull(baseNodeSignature, "baseNodeSignature");
    }

    /** 按 SQL 的毫秒精度校验生效图版本，并确认等待期间节点注册表未变化。 */
    public boolean matchesBase(Optional<Instant> builtAt, String nodeSignature) {
      return baseBuiltAt.map(Instant::toEpochMilli).equals(builtAt.map(Instant::toEpochMilli))
          && baseNodeSignature.equals(nodeSignature);
    }
  }

  /** 原子替换该世界的候选；只保留拓扑与 Zone 局部证据，普通轨道采样不进入缓存。 */
  public void save(UUID worldId, Candidate candidate) throws IOException {
    var result = candidate.result();
    if (!(result.graph() instanceof RailGraphInterlockingSupport support)
        || !support.interlockingState().available()) {
      throw new IOException("候选图缺少稀疏联锁状态");
    }
    var state = support.interlockingState();
    var snapshot =
        new RailInterlockingSnapshotRecord(
            worldId,
            RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION,
            RailInterlockingEdgeSignature.of(state.expectedEdges()),
            state.coverage(),
            state.exactZones());
    var nodes = result.nodes().stream().map(NodeData::from).toList();
    var edges =
        result.graph().edges().stream()
            .map(
                edge ->
                    new EdgeData(
                        edge.id().a().value(),
                        edge.id().b().value(),
                        edge.lengthBlocks(),
                        edge.baseSpeedLimit(),
                        edge.bidirectional()))
            .toList();
    var data =
        new Data(
            FORMAT_VERSION,
            worldId.toString(),
            result.builtAt().toString(),
            result.nodeSignature(),
            candidate.baseBuiltAt().map(Instant::toString).orElse(null),
            candidate.baseNodeSignature(),
            nodes,
            edges,
            RailInterlockingSnapshotCodec.encode(snapshot));
    Files.createDirectories(directory);
    Path temp = Files.createTempFile(directory, "candidate-", ".tmp");
    try {
      Files.writeString(temp, GSON.toJson(data));
      try {
        Files.move(
            temp,
            file(worldId),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temp, file(worldId), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** 重载候选拓扑和稀疏联锁；兼容旧版缓存但忽略普通轨道足迹，损坏记录拒绝激活。 */
  public Optional<Candidate> load(UUID worldId) throws IOException {
    Path file = file(worldId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      Data data = GSON.fromJson(Files.readString(file), Data.class);
      if ((data.version() != 1 && data.version() != FORMAT_VERSION)
          || !worldId.toString().equals(data.world())) {
        throw new IllegalArgumentException("候选图格式或世界不匹配");
      }
      List<RailNodeRecord> nodes = data.nodes().stream().map(node -> node.record(worldId)).toList();
      List<RailEdgeRecord> edges = data.edges().stream().map(edge -> edge.record(worldId)).toList();
      if (!data.signature().equals(RailGraphSignature.signatureForNodes(nodes))) {
        throw new IllegalArgumentException("候选图节点签名不匹配");
      }
      var state = RailInterlockingSnapshotCodec.decode(worldId, data.interlocking()).orElseThrow();
      var expectedEdges = edges.stream().map(RailEdgeRecord::edgeId).collect(Collectors.toSet());
      if (state.formatVersion() != RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION
          || !state.edgeSignature().equals(RailInterlockingEdgeSignature.of(expectedEdges))) {
        throw new IllegalArgumentException("候选图联锁格式或 Edge 签名不匹配");
      }
      var graph = RailGraphService.buildGraphFromRecords(nodes, edges, Optional.of(state));
      if (graph.nodes().size() != nodes.size() || graph.edges().size() != edges.size()) {
        throw new IllegalArgumentException("候选图端点缺失或记录重复");
      }
      // 普通图加载器将损坏的 Zone 引用或覆盖计数降为 sentinel；候选不能发布这种降级结果。
      var restoredState = ((RailGraphInterlockingSupport) graph).interlockingState();
      if (!restoredState.coverage().equals(state.coverage())
          || !restoredState.exactZones().equals(state.zones())) {
        throw new IllegalArgumentException("候选图联锁证据损坏");
      }
      var result =
          new RailGraphBuildResult(
              graph, Instant.parse(data.builtAt()), data.signature(), nodes, List.of(), List.of());
      return Optional.of(
          new Candidate(
              result,
              Optional.ofNullable(data.baseBuiltAt()).map(Instant::parse),
              data.baseNodeSignature()));
    } catch (RuntimeException ex) {
      throw new IOException("候选图缓存损坏: " + file, ex);
    }
  }

  /** 激活或明确删除生效图后清除候选，避免将旧结果重新发布。 */
  public void delete(UUID worldId) throws IOException {
    Files.deleteIfExists(file(worldId));
  }

  private Path file(UUID worldId) {
    return directory.resolve(Objects.requireNonNull(worldId, "worldId") + ".json");
  }

  private record Data(
      int version,
      String world,
      String builtAt,
      String signature,
      String baseBuiltAt,
      String baseNodeSignature,
      List<NodeData> nodes,
      List<EdgeData> edges,
      String interlocking) {}

  private record NodeData(
      String id, NodeType type, int x, int y, int z, String destination, WaypointData waypoint) {
    static NodeData from(RailNodeRecord node) {
      return new NodeData(
          node.nodeId().value(),
          node.nodeType(),
          node.x(),
          node.y(),
          node.z(),
          node.trainCartsDestination().orElse(null),
          node.waypointMetadata().map(WaypointData::from).orElse(null));
    }

    RailNodeRecord record(UUID world) {
      return new RailNodeRecord(
          world,
          NodeId.of(id),
          type,
          x,
          y,
          z,
          Optional.ofNullable(destination),
          Optional.ofNullable(waypoint).map(WaypointData::metadata));
    }
  }

  private record WaypointData(
      String operator,
      String origin,
      String destination,
      int track,
      String sequence,
      WaypointKind kind) {
    static WaypointData from(WaypointMetadata metadata) {
      return new WaypointData(
          metadata.operator(),
          metadata.originStation(),
          metadata.destinationStation().orElse(null),
          metadata.trackNumber(),
          metadata.sequence().orElse(null),
          metadata.kind());
    }

    WaypointMetadata metadata() {
      return new WaypointMetadata(
          operator,
          origin,
          Optional.ofNullable(destination),
          track,
          Optional.ofNullable(sequence),
          kind);
    }
  }

  private record EdgeData(String a, String b, int length, double speed, boolean bidirectional) {
    RailEdgeRecord record(UUID world) {
      return new RailEdgeRecord(
          world, EdgeId.undirected(NodeId.of(a), NodeId.of(b)), length, speed, bidirectional);
    }
  }
}

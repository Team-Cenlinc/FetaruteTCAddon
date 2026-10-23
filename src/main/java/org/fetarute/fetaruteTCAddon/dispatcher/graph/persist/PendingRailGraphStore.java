package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.build.RailGraphSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 未激活调度图的本地缓存，与生效中的 SQL 快照隔离。
 *
 * <p>格式 v2 起写盘；v3 加逐边足迹与开头的节点、区间计数（{@link #summary} 只读这几项）。v2 仍可读取，没有足迹与计数。
 *
 * <p>线程：{@link #save} 可在后台线程调用；落盘那一步与 {@link #delete} 互斥，{@code stillWanted} 在同一把锁里判定，
 * 被取代的保存不会在清理之后再写出文件。
 */
public final class PendingRailGraphStore {
  private static final int FORMAT_VERSION = 3;
  private static final int OLDEST_READABLE_VERSION = 2;
  private static final Gson GSON = new Gson();

  /** 写在文件开头的元数据，顺序固定，状态显示读到计数即止。 */
  private static final List<String> HEADER_FIELDS =
      List.of("version", "world", "builtAt", "nodeCount", "edgeCount");

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

  /**
   * 状态显示用的候选摘要。
   *
   * @param nodeCount 节点数
   * @param edgeCount 区间数
   * @param builtAt 候选的构建时间
   */
  public record Summary(int nodeCount, int edgeCount, Instant builtAt) {
    public Summary {
      Objects.requireNonNull(builtAt, "builtAt");
    }
  }

  /** 原子替换该世界的候选，见 {@link #save(UUID, Candidate, BooleanSupplier)}。 */
  public void save(UUID worldId, Candidate candidate) throws IOException {
    save(worldId, candidate, () -> true);
  }

  /**
   * 原子替换该世界的候选：拓扑、Zone 局部证据与逐边足迹。
   *
   * <p>逐边足迹必须带上：激活时整份写库（rail_edges 按世界整表替换），候选没有足迹就会把库里的足迹清空， 覆盖索引从此不可用，尾部保护与 Phase 4 保守到下一次
   * build。覆盖不可用的候选不写足迹（没有可写的），读回后照旧保守。
   *
   * <p>序列化与写临时文件不持锁；落盘前在锁内再问一次 {@code stillWanted}，为假时丢弃临时文件。
   *
   * @param stillWanted 这份候选是否仍是该世界最新要保存的（没被更新的保存或清理取代）
   * @return 是否已落盘
   */
  public boolean save(UUID worldId, Candidate candidate, BooleanSupplier stillWanted)
      throws IOException {
    var result = candidate.result();
    if (!(result.graph() instanceof RailGraphInterlockingSupport support)
        || !support.interlockingState().available()) {
      throw new IOException("候选图缺少稀疏联锁状态");
    }
    var state = support.interlockingState();
    var footprints =
        state.cellCoverageAvailable()
            ? state.footprintCellsByEdge()
            : java.util.Map.<EdgeId, java.util.Set<RailFootprintCell>>of();
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
                        edge.bidirectional(),
                        RailEdgeFootprintCodec.encode(
                            footprints.getOrDefault(edge.id(), java.util.Set.of()))))
            .toList();
    var data =
        new Data(
            FORMAT_VERSION,
            worldId.toString(),
            result.builtAt().toString(),
            nodes.size(),
            edges.size(),
            result.nodeSignature(),
            candidate.baseBuiltAt().map(Instant::toString).orElse(null),
            candidate.baseNodeSignature(),
            nodes,
            edges,
            RailInterlockingSnapshotCodec.encode(snapshot));
    Files.createDirectories(directory);
    Path temp = Files.createTempFile(directory, "candidate-", ".tmp");
    try {
      Files.writeString(temp, GSON.toJson(headerFirst(GSON.toJsonTree(data).getAsJsonObject())));
      synchronized (this) {
        if (!stillWanted.getAsBoolean()) {
          return false;
        }
        try {
          Files.move(
              temp,
              file(worldId),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
          Files.move(temp, file(worldId), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static JsonObject headerFirst(JsonObject json) {
    JsonObject ordered = new JsonObject();
    for (String field : HEADER_FIELDS) {
      ordered.add(field, json.get(field));
    }
    json.entrySet().stream()
        .filter(entry -> !HEADER_FIELDS.contains(entry.getKey()))
        .forEach(entry -> ordered.add(entry.getKey(), entry.getValue()));
    return ordered;
  }

  /**
   * 读候选的节点数、区间数与构建时间，供 {@code /fta graph status} 显示。
   *
   * <p>只流式读取文件开头的元数据，不解码拓扑与联锁、不建图；旧缓存没有计数时退回读整份候选（{@link #load}）。
   *
   * @throws IOException 文件读不了、格式或世界不匹配
   */
  public Optional<Summary> summary(UUID worldId) throws IOException {
    Path file = file(worldId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    Map<String, String> header = new HashMap<>();
    try (JsonReader reader = new JsonReader(Files.newBufferedReader(file))) {
      reader.beginObject();
      while (reader.hasNext() && header.size() < HEADER_FIELDS.size()) {
        String name = reader.nextName();
        if (!HEADER_FIELDS.contains(name)) {
          // 计数写在拓扑之前：先遇到别的字段就是没有计数的旧缓存。
          break;
        }
        header.put(name, reader.nextString());
      }
    } catch (RuntimeException ex) {
      throw new IOException("候选图缓存损坏: " + file, ex);
    }
    if (header.size() == HEADER_FIELDS.size()) {
      try {
        checkVersionAndWorld(Integer.parseInt(header.get("version")), header.get("world"), worldId);
        return Optional.of(
            new Summary(
                Integer.parseInt(header.get("nodeCount")),
                Integer.parseInt(header.get("edgeCount")),
                Instant.parse(header.get("builtAt"))));
      } catch (RuntimeException ex) {
        throw new IOException("候选图缓存损坏: " + file, ex);
      }
    }
    return load(worldId)
        .map(
            candidate ->
                new Summary(
                    candidate.result().graph().nodes().size(),
                    candidate.result().graph().edges().size(),
                    candidate.result().builtAt()));
  }

  private static void checkVersionAndWorld(int version, String world, UUID worldId) {
    if (version < OLDEST_READABLE_VERSION
        || version > FORMAT_VERSION
        || !worldId.toString().equals(world)) {
      throw new IllegalArgumentException("候选图格式或世界不匹配");
    }
  }

  /** 重载候选拓扑、稀疏联锁与逐边足迹；v2 缓存没有足迹，读回后覆盖索引不可用。损坏记录拒绝激活。 */
  public Optional<Candidate> load(UUID worldId) throws IOException {
    Path file = file(worldId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      Data data = GSON.fromJson(Files.readString(file), Data.class);
      checkVersionAndWorld(data.version(), data.world(), worldId);
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
  public synchronized void delete(UUID worldId) throws IOException {
    Files.deleteIfExists(file(worldId));
  }

  private Path file(UUID worldId) {
    return directory.resolve(Objects.requireNonNull(worldId, "worldId") + ".json");
  }

  /**
   * @param nodeCount 节点数；v2 没有这一栏
   * @param edgeCount 区间数；v2 没有这一栏
   */
  private record Data(
      int version,
      String world,
      String builtAt,
      Integer nodeCount,
      Integer edgeCount,
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

  /**
   * @param footprint 逐边足迹（{@link RailEdgeFootprintCodec} 写法）；v2 缓存没有这一栏，读回为空
   */
  private record EdgeData(
      String a, String b, int length, double speed, boolean bidirectional, String footprint) {
    RailEdgeRecord record(UUID world) {
      return new RailEdgeRecord(
          world,
          EdgeId.undirected(NodeId.of(a), NodeId.of(b)),
          length,
          speed,
          bidirectional,
          RailEdgeFootprintCodec.decode(footprint));
    }
  }
}

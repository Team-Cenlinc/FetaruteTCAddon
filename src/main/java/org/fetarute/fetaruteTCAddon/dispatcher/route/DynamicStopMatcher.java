package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphPlatformSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * DYNAMIC stop 匹配工具：解析 DYNAMIC 规范并判断 NodeId 是否匹配。
 *
 * <h3>DYNAMIC 格式</h3>
 *
 * <pre>{@code
 * DYNAMIC:OP:TYPE:NAME:[1,3,5-7]
 * }</pre>
 *
 * <p>各部分含义：
 *
 * <ul>
 *   <li>{@code OP}：运营商代码（如 SURC）
 *   <li>{@code TYPE}：节点类型，{@code S} 表示 Station，{@code D} 表示 Depot
 *   <li>{@code NAME}：站点/车库名称（如 PPK、OFL）
 *   <li>{@code [1,3,5-7]}：股道允许集合；单值、冒号区间与省略选择器继续兼容
 * </ul>
 *
 * <h3>格式示例</h3>
 *
 * <ul>
 *   <li>{@code DYNAMIC:SURC:S:PPK:[1:3]} - Station PPK，轨道范围 1-3
 *   <li>{@code DYNAMIC:SURC:D:OFL:[1:2]} - Depot OFL，轨道范围 1-2
 *   <li>{@code DYNAMIC:SURC:S:PPK} - Station PPK，不限该站现有数字股道
 *   <li>{@code DYNAMIC:SURC:PPK:[1:3]} - 旧格式兼容（默认 Station 类型）
 * </ul>
 *
 * <h3>匹配规则</h3>
 *
 * <p>NodeId（如 {@code SURC:S:PPK:2}）匹配 DYNAMIC 规范的条件：
 *
 * <ol>
 *   <li>{@code operatorCode} 匹配（忽略大小写）
 *   <li>{@code nodeType} 匹配（S/D）
 *   <li>{@code nodeName} 匹配（忽略大小写）
 *   <li>{@code track} 属于允许集合
 * </ol>
 *
 * <h3>咽喉节点区分</h3>
 *
 * <p>本工具区分站点本体（4 段）与咽喉节点（5 段）：
 *
 * <ul>
 *   <li>{@code OP:S:STATION:TRACK} - 站点本体（4 段）
 *   <li>{@code OP:S:STATION:TRACK:SEQ} - 站咽喉（5 段）
 * </ul>
 *
 * <p>容错匹配时，咽喉与站点本体不会互相匹配，避免 waypoint 被误认为是车站 stop。
 *
 * @see DynamicSpec
 * @see #parseDynamicSpec(RouteStop)
 * @see #matches(NodeId, DynamicSpec)
 */
public final class DynamicStopMatcher {

  /** 动作指令前缀正则：CHANGE/DYNAMIC/ACTION/CRET/DSTY */
  private static final Pattern ACTION_PREFIX_PATTERN =
      Pattern.compile("^(CHANGE|DYNAMIC|ACTION|CRET|DSTY)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern DYNAMIC_DIRECTIVE_PATTERN =
      Pattern.compile("(?:^|\\s)DYNAMIC(?:$|[\\s:])", Pattern.CASE_INSENSITIVE);

  private static final Pattern DYNAMIC_TOKEN_PATTERN =
      Pattern.compile("(?:^|(?<=\\s))DYNAMIC(?:\\s+|:)(\\S+)", Pattern.CASE_INSENSITIVE);

  /** 候选股道数的安全上限，防止规范范围写得过大时逐条寻路卡服。 */
  public static final int MAX_TRACK_CANDIDATES = 20;

  private DynamicStopMatcher() {}

  /**
   * DYNAMIC 规范在图上实际存在的候选股道节点（按股道号升序）。
   *
   * <p>允许集合只用于筛选真实节点，不展开数值空间。省略选择器时允许本站现有的全部数字股道； 实际寻路预算限制为数字顺序前 20 个合法节点，与集合成员资格分离。
   *
   * <p>选台（{@code DynamicPlatformAllocator}）与尚未选台时的 ETA 估算共用这一份枚举。
   *
   * @param spec DYNAMIC 规范
   * @param graph 调度图；为空时没有候选
   * @return 图上存在的候选节点，至多 {@link #MAX_TRACK_CANDIDATES} 个
   */
  public static List<NodeId> candidateNodes(DynamicSpec spec, RailGraph graph) {
    return candidateNodes(spec, graph, message -> {});
  }

  /** 实际候选超过预算时输出诊断；已索引的快照只访问本站节点，未知图实现保留扫描兼容。 */
  public static List<NodeId> candidateNodes(
      DynamicSpec spec, RailGraph graph, java.util.function.Consumer<String> diagnostic) {
    if (spec == null || graph == null) {
      return List.of();
    }
    List<NodeId> preview =
        sortedMatchingNodes(spec, knownNodeStream(spec, graph))
            .limit(MAX_TRACK_CANDIDATES + 1L)
            .toList();
    if (preview.size() > MAX_TRACK_CANDIDATES) {
      if (diagnostic != null) {
        diagnostic.accept(
            "DYNAMIC_CANDIDATE_BUDGET spec="
                + spec.payload()
                + " evaluated="
                + MAX_TRACK_CANDIDATES
                + " additionalCandidates=true");
      }
      return List.copyOf(preview.subList(0, MAX_TRACK_CANDIDATES));
    }
    return preview;
  }

  /**
   * 从实际节点集合中筛出所有合法股道；不展开数值区间，也不把候选预算混入集合资格。
   *
   * @param spec DYNAMIC 声明
   * @param nodes 图或牌子注册表中的实际节点
   * @return 保留实际 NodeId、按数字股道号与节点名稳定排序的候选
   */
  public static List<NodeId> matchingNodes(DynamicSpec spec, Collection<NodeId> nodes) {
    if (spec == null || nodes == null) {
      return List.of();
    }
    return sortedMatchingNodes(spec, nodes.stream()).toList();
  }

  /**
   * 图中全部合法节点，供资格检查与容量通知使用；查询索引但不施加寻路评估预算。
   *
   * @param spec DYNAMIC 声明
   * @param graph 生产快照或包装视图；未知兼容实现才扫描节点
   * @return 保留实际 NodeId、按数字股道稳定排序的完整资格集合
   */
  public static List<NodeId> matchingNodes(DynamicSpec spec, RailGraph graph) {
    return spec == null || graph == null
        ? List.of()
        : sortedMatchingNodes(spec, knownNodeStream(spec, graph)).toList();
  }

  private static java.util.stream.Stream<NodeId> knownNodeStream(
      DynamicSpec spec, RailGraph graph) {
    return graph instanceof RailGraphPlatformSupport indexed
        ? indexed.platformNodes(spec.toTerminalKey()).stream()
        : graph.nodes().stream().filter(Objects::nonNull).map(RailNode::id);
  }

  private static java.util.stream.Stream<NodeId> sortedMatchingNodes(
      DynamicSpec spec, java.util.stream.Stream<NodeId> nodes) {
    return nodes
        .filter(node -> matches(node, spec))
        .distinct()
        .sorted(
            Comparator.comparingInt((NodeId node) -> trackNumber(node).orElseThrow())
                .thenComparing(NodeId::value));
  }

  /** 四段行为节点中的正整数股道号；五段咽喉不具有停靠资格。 */
  public static OptionalInt trackNumber(NodeId node) {
    if (node == null) {
      return OptionalInt.empty();
    }
    String[] parts = node.value().split(":", -1);
    return parts.length == 4 ? parsePositiveInt(parts[3]) : OptionalInt.empty();
  }

  /**
   * 判断 stop 是否为 DYNAMIC 类型（notes 中包含 DYNAMIC 指令）。
   *
   * @param stop RouteStop
   * @return true 如果 stop 的 notes 包含 DYNAMIC 指令
   */
  public static boolean isDynamicStop(RouteStop stop) {
    if (stop == null || stop.notes().isEmpty()) {
      return false;
    }
    String raw = stop.notes().orElse("");
    return DYNAMIC_DIRECTIVE_PATTERN.matcher(raw).find();
  }

  /**
   * 解析 stop 中的 DYNAMIC 规范。
   *
   * @param stop RouteStop
   * @return DYNAMIC 规范（如果存在）
   */
  public static Optional<DynamicSpec> parseDynamicSpec(RouteStop stop) {
    if (stop == null || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    Optional<String> target = findDirectiveTarget(stop, "DYNAMIC");
    if (target.isEmpty()) {
      return Optional.empty();
    }
    return parseDynamicSpec(target.get());
  }

  /**
   * 解析 DYNAMIC 规范字符串。
   *
   * @param raw 原始字符串（可能带有 DYNAMIC: 前缀）
   * @return 解析结果
   */
  public static Optional<DynamicSpec> parseDynamicSpec(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    // 去掉可能的 DYNAMIC: 前缀
    if (trimmed.regionMatches(true, 0, "DYNAMIC", 0, "DYNAMIC".length())) {
      String rest = trimmed.substring("DYNAMIC".length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      trimmed = rest;
    }
    if (trimmed.isBlank()) {
      return Optional.empty();
    }

    // 解析格式: OP:S:STATION:[range] 或 OP:D:DEPOT:[range] 或 OP:STATION:[range]
    String[] parts = trimmed.split(":", -1);
    if (parts.length < 2) {
      return Optional.empty();
    }

    String operatorCode;
    String nodeType; // "S" or "D"
    String nodeName;
    String rangeRaw;

    if (parts.length >= 3 && (parts[1].equalsIgnoreCase("S") || parts[1].equalsIgnoreCase("D"))) {
      // 新格式: OP:S:STATION:... 或 OP:D:DEPOT:...
      operatorCode = parts[0].trim();
      nodeType = parts[1].trim().toUpperCase(Locale.ROOT);
      nodeName = parts[2].trim();
      // 剩余部分作为 range（可能是 "1" 或 "[1:3]" 或 "1:3"）
      if (parts.length > 3) {
        rangeRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 3, parts.length)).trim();
      } else {
        rangeRaw = "";
      }
    } else {
      // 旧格式兼容: OP:STATION:[range]
      operatorCode = parts[0].trim();
      nodeType = "S"; // 默认 Station
      nodeName = parts[1].trim();
      if (parts.length > 2) {
        rangeRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length)).trim();
      } else {
        rangeRaw = "";
      }
    }

    boolean explicitType =
        parts.length >= 3 && (parts[1].equalsIgnoreCase("S") || parts[1].equalsIgnoreCase("D"));
    boolean selectorDeclared = parts.length > (explicitType ? 3 : 2);
    if (operatorCode.isBlank() || nodeName.isBlank() || (selectorDeclared && rangeRaw.isBlank())) {
      return Optional.empty();
    }

    return (rangeRaw.isBlank() ? Optional.of(TrackSelection.all()) : TrackSelection.parse(rangeRaw))
        .map(selection -> new DynamicSpec(operatorCode, nodeType, nodeName, selection));
  }

  /**
   * 判断实际 nodeId 是否匹配 DYNAMIC 规范。
   *
   * <p>匹配规则：
   *
   * <ul>
   *   <li>operator 必须匹配（忽略大小写）
   *   <li>nodeType 必须匹配（S/D）
   *   <li>nodeName 必须匹配（忽略大小写）
   *   <li>track 必须属于允许集合
   * </ul>
   *
   * @param nodeId 实际 nodeId（如 "SURC:D:OFL:1"）
   * @param spec DYNAMIC 规范
   * @return true 如果匹配
   */
  public static boolean matches(NodeId nodeId, DynamicSpec spec) {
    if (nodeId == null || spec == null || nodeId.value() == null) {
      return false;
    }
    return matches(nodeId.value(), spec);
  }

  /**
   * 判断实际 nodeId 字符串是否匹配 DYNAMIC 规范。
   *
   * @param nodeIdValue 实际 nodeId 值（如 "SURC:D:OFL:1"）
   * @param spec DYNAMIC 规范
   * @return true 如果匹配
   */
  public static boolean matches(String nodeIdValue, DynamicSpec spec) {
    if (nodeIdValue == null || nodeIdValue.isBlank() || spec == null) {
      return false;
    }
    String[] parts = nodeIdValue.split(":", -1);
    // 必须是 4 段格式（OP:S/D:NAME:TRACK），排除咽喉（5 段）
    if (parts.length != 4) {
      return false;
    }

    String operator = parts[0].trim();
    String nodeType = parts[1].trim().toUpperCase(Locale.ROOT);
    String nodeName = parts[2].trim();
    String trackStr = parts[3].trim();

    // 检查 operator
    if (!operator.equalsIgnoreCase(spec.operatorCode())) {
      return false;
    }
    // 检查 nodeType
    if (!nodeType.equals(spec.nodeType())) {
      return false;
    }
    // 检查 nodeName
    if (!nodeName.equalsIgnoreCase(spec.nodeName())) {
      return false;
    }
    // 检查 track
    OptionalInt trackOpt = parsePositiveInt(trackStr);
    if (trackOpt.isEmpty()) {
      return false;
    }
    int track = trackOpt.getAsInt();
    return spec.tracks().contains(track);
  }

  /**
   * 判断实际 nodeId 是否匹配 stop（支持 DYNAMIC 和普通 nodeId）。
   *
   * @param nodeId 实际 nodeId
   * @param stop RouteStop
   * @return true 如果匹配
   */
  public static boolean matchesStop(NodeId nodeId, RouteStop stop) {
    if (nodeId == null || stop == null) {
      return false;
    }
    if (isDynamicStop(stop)) {
      Optional<DynamicSpec> spec = parseDynamicSpec(stop);
      return spec.isPresent()
          && (matches(nodeId, spec.get()) || matchesFixedDepotDirective(nodeId, stop, spec.get()));
    }
    // 先检查普通 waypointNodeId 匹配
    if (stop.waypointNodeId().isPresent()) {
      String waypointId = stop.waypointNodeId().get();
      if (nodeId.value() != null && nodeId.value().equalsIgnoreCase(waypointId)) {
        return true;
      }
      // 支持同站不同轨道的容错匹配（但咽喉 vs 站点本体不允许）
      boolean nodeIsThroat = isThroatFormat(nodeId.value());
      boolean stopIsThroat = isThroatFormat(waypointId);
      if (nodeIsThroat != stopIsThroat) {
        // 咽喉 vs 站点本体：不允许容错匹配
        // 继续检查 DYNAMIC
      } else {
        Optional<String> nodeKey = extractStationKey(nodeId.value());
        Optional<String> stopKey = extractStationKey(waypointId);
        if (nodeKey.isPresent() && stopKey.isPresent() && nodeKey.get().equals(stopKey.get())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 判断普通站点匹配是否可以使用此实际节点；DYNAMIC 的 UUID、站码与固定节点入口都必须先经过该资格闸。
   *
   * <p>CRET/DSTY 固定 Depot 附带其他车站的 DYNAMIC 动作时，保留原有固定 Depot 身份，不把出库点解释成车站。
   */
  public static boolean allowsStopNode(NodeId node, RouteStop stop) {
    return stop != null && (!isDynamicStop(stop) || matchesStop(node, stop));
  }

  /** 固定 CRET/DSTY Depot 携带另一个站的 DYNAMIC 动作时，当前停靠身份仍是固定 Depot。 */
  public static boolean isFixedDepotDirectiveTarget(String node, RouteStop stop) {
    if (node == null || node.isBlank() || stop == null) {
      return false;
    }
    return parseDynamicSpec(stop)
        .filter(spec -> matchesFixedDepotDirective(NodeId.of(node), stop, spec))
        .isPresent();
  }

  private static boolean matchesFixedDepotDirective(NodeId node, RouteStop stop, DynamicSpec spec) {
    if (node.value().split(":", -1).length != 4) {
      return false;
    }
    Optional<String> actualKey = extractStationKey(node.value());
    for (String prefix : List.of("CRET", "DSTY")) {
      Optional<String> fixed = RouteStopDirectives.target(stop, prefix);
      if (fixed.isEmpty()) {
        continue;
      }
      String[] parts = fixed.get().split(":", -1);
      if (parts.length != 4 || !"D".equalsIgnoreCase(parts[1])) {
        continue;
      }
      Optional<String> fixedKey = extractStationKey(fixed.get());
      if (fixedKey.isPresent()
          && !fixedKey.get().equals(spec.toTerminalKey())
          && actualKey.filter(fixedKey.get()::equals).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 比较停靠定义证据：保留动作、停车方式与时间等字段，仅归一化 DYNAMIC 的等价集合写法。
   *
   * <p>选台缓存与 effective-node 覆盖必须共用该规则；不能只比较 RouteId，也不能忽略 CHANGE 等其他动作。
   */
  public static boolean sameDefinition(Optional<RouteStop> before, Optional<RouteStop> after) {
    if (before.equals(after) || before.isEmpty() || after.isEmpty()) {
      return before.equals(after);
    }
    RouteStop old = before.get();
    RouteStop current = after.get();
    return Objects.equals(old.routeId(), current.routeId())
        && old.sequence() == current.sequence()
        && old.stationId().equals(current.stationId())
        && old.waypointNodeId().equals(current.waypointNodeId())
        && old.dwellSeconds().equals(current.dwellSeconds())
        && old.passType() == current.passType()
        && semanticNotes(old.notes()).equals(semanticNotes(current.notes()));
  }

  private static String semanticNotes(Optional<String> notes) {
    String raw = notes.orElse("").replace("\r\n", "\n").replace('\r', '\n');
    java.util.regex.Matcher matcher = DYNAMIC_TOKEN_PATTERN.matcher(raw);
    StringBuilder normalized = new StringBuilder();
    while (matcher.find()) {
      String canonical =
          parseDynamicSpec(matcher.group(1))
              .map(spec -> "DYNAMIC:" + spec.payload().toUpperCase(Locale.ROOT))
              .orElse(matcher.group());
      matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(canonical));
    }
    matcher.appendTail(normalized);
    return normalized.toString();
  }

  /**
   * 判断 NodeId 值是否为咽喉格式（5 段，且第二段是 S/D）。
   *
   * <p>格式：{@code Operator:S/D:Name:Track:Seq}
   */
  private static boolean isThroatFormat(String nodeIdValue) {
    if (nodeIdValue == null || nodeIdValue.isBlank()) {
      return false;
    }
    String[] parts = nodeIdValue.split(":");
    if (parts.length != 5) {
      return false;
    }
    String typeMarker = parts[1].toUpperCase(Locale.ROOT);
    return "S".equals(typeMarker) || "D".equals(typeMarker);
  }

  /**
   * 从 nodeId 中提取 Station/Depot 级别的 key（不含 track）。
   *
   * <p>格式：{@code OP:S:STATION} 或 {@code OP:D:DEPOT}
   *
   * @param nodeIdValue nodeId 值
   * @return Station/Depot key
   */
  public static Optional<String> extractStationKey(String nodeIdValue) {
    if (nodeIdValue == null || nodeIdValue.isBlank()) {
      return Optional.empty();
    }
    String[] parts = nodeIdValue.split(":", -1);
    if (parts.length < 4) {
      return Optional.empty();
    }
    String operator = parts[0].trim().toLowerCase(Locale.ROOT);
    String nodeType = parts[1].trim().toLowerCase(Locale.ROOT);
    String nodeName = parts[2].trim().toLowerCase(Locale.ROOT);
    if (operator.isEmpty() || nodeName.isEmpty()) {
      return Optional.empty();
    }
    if (!"s".equals(nodeType) && !"d".equals(nodeType)) {
      return Optional.empty();
    }
    return Optional.of(operator + ":" + nodeType + ":" + nodeName);
  }

  /**
   * 从 DYNAMIC 规范中生成 Station/Depot 级别的 key。
   *
   * @param spec DYNAMIC 规范
   * @return Station/Depot key
   */
  public static String specToStationKey(DynamicSpec spec) {
    if (spec == null) {
      return "";
    }
    return spec.operatorCode().toLowerCase(Locale.ROOT)
        + ":"
        + spec.nodeType().toLowerCase(Locale.ROOT)
        + ":"
        + spec.nodeName().toLowerCase(Locale.ROOT);
  }

  private static Optional<String> findDirectiveTarget(RouteStop stop, String prefix) {
    if (stop == null || prefix == null || prefix.isBlank() || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    String raw = stop.notes().orElse("");
    for (String line : raw.split("[\r\n]+")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      // 特殊处理 "CRET DYNAMIC:..." 格式
      if ("DYNAMIC".equalsIgnoreCase(prefix)) {
        java.util.regex.Matcher dynamicMatcher = DYNAMIC_DIRECTIVE_PATTERN.matcher(trimmed);
        if (dynamicMatcher.find()) {
          String afterDynamic = trimmed.substring(dynamicMatcher.end()).trim();
          if (!afterDynamic.isBlank()) {
            return Optional.of(afterDynamic.split("\\s+", 2)[0]);
          }
        }
        continue;
      }
      String normalizedAction = normalizeActionLine(trimmed);
      String actualPrefix = firstSegment(normalizedAction).trim();
      if (!actualPrefix.equalsIgnoreCase(prefix)) {
        continue;
      }
      String rest = normalizedAction.substring(actualPrefix.length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      if (rest.isBlank()) {
        continue;
      }
      return Optional.of(rest);
    }
    return Optional.empty();
  }

  private static String firstSegment(String line) {
    if (line == null || line.isEmpty()) {
      return "";
    }
    int idx = 0;
    while (idx < line.length()) {
      char ch = line.charAt(idx);
      if (Character.isWhitespace(ch) || ch == ':') {
        break;
      }
      idx++;
    }
    return line.substring(0, idx);
  }

  private static String normalizeActionLine(String line) {
    String trimmed = line.trim();
    java.util.regex.Matcher matcher = ACTION_PREFIX_PATTERN.matcher(trimmed);
    if (!matcher.find()) {
      return trimmed;
    }
    String prefix = matcher.group(1).toUpperCase(Locale.ROOT);
    String rest = trimmed.substring(prefix.length()).trim();
    if (rest.isEmpty()) {
      return prefix;
    }
    if (rest.startsWith(":")) {
      rest = rest.substring(1).trim();
    }
    if ("CRET".equals(prefix) || "DSTY".equals(prefix)) {
      return rest.isEmpty() ? prefix : prefix + " " + rest;
    }
    return prefix + ":" + rest;
  }

  private static OptionalInt parsePositiveInt(String raw) {
    if (raw == null) {
      return OptionalInt.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      int value = Integer.parseInt(trimmed);
      return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  /**
   * DYNAMIC 规范。
   *
   * @param operatorCode 运营商代码
   * @param nodeType 节点类型："S" 表示 Station，"D" 表示 Depot
   * @param nodeName 站点/车库名称
   * @param tracks 股道允许集合；不限股道是明确状态
   */
  public record DynamicSpec(
      String operatorCode, String nodeType, String nodeName, TrackSelection tracks) {
    public DynamicSpec {
      Objects.requireNonNull(operatorCode, "operatorCode");
      Objects.requireNonNull(nodeType, "nodeType");
      Objects.requireNonNull(nodeName, "nodeName");
      Objects.requireNonNull(tracks, "tracks");
      operatorCode = operatorCode.trim();
      nodeType = nodeType.trim().toUpperCase(Locale.ROOT);
      nodeName = nodeName.trim();
    }

    /** 兼容旧调用：显式范围即非 unbounded。 */
    public DynamicSpec(
        String operatorCode, String nodeType, String nodeName, int fromTrack, int toTrack) {
      this(operatorCode, nodeType, nodeName, TrackSelection.between(fromTrack, toTrack));
    }

    /** 兼容显式区间或未声明范围的构造入口；最终仅保存集合值。 */
    public DynamicSpec(
        String operatorCode,
        String nodeType,
        String nodeName,
        int fromTrack,
        int toTrack,
        boolean unbounded) {
      this(
          operatorCode,
          nodeType,
          nodeName,
          unbounded ? TrackSelection.all() : TrackSelection.between(fromTrack, toTrack));
    }

    /** 是否省略了股道选择器。 */
    public boolean unbounded() {
      return tracks.isUnbounded();
    }

    /** 大小写不影响站点身份；保留原文用于构造 NodeId 与展示。 */
    @Override
    public boolean equals(Object value) {
      return value instanceof DynamicSpec other
          && operatorCode.equalsIgnoreCase(other.operatorCode)
          && nodeType.equalsIgnoreCase(other.nodeType)
          && nodeName.equalsIgnoreCase(other.nodeName)
          && tracks.equals(other.tracks);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          operatorCode.toLowerCase(Locale.ROOT),
          nodeType.toLowerCase(Locale.ROOT),
          nodeName.toLowerCase(Locale.ROOT),
          tracks);
    }

    /** 可重新解析的完整 payload（不含 DYNAMIC 前缀）；不限范围省略尾段。 */
    public String payload() {
      return operatorCode
          + ":"
          + nodeType
          + ":"
          + nodeName
          + tracks.formatSelector().map(selector -> ":" + selector).orElse("");
    }

    /** 是否为 Depot 类型。 */
    public boolean isDepot() {
      return "D".equalsIgnoreCase(nodeType);
    }

    /** 是否为 Station 类型。 */
    public boolean isStation() {
      return "S".equalsIgnoreCase(nodeType);
    }

    /** 股道节点名的前缀 {@code OP:S/D:NAME:}（运营商、类型、站名照规范原样、去掉首尾空白）。 */
    String trackPrefix() {
      return operatorCode.trim() + ":" + nodeType.trim() + ":" + nodeName.trim() + ":";
    }

    /**
     * 这个站（车库）第 {@code track} 条股道的节点：选台候选与叫车指定站台用同一个写法。
     *
     * @param track 股道号
     */
    public NodeId nodeIdForTrack(int track) {
      return NodeId.of(trackPrefix() + track);
    }

    /**
     * 生成 placeholder nodeId（用于 spawn plan 和 layover 匹配）。
     *
     * <p>使用最小声明股道作为占位；不限范围用 1 作占位，不能据此授权停靠。
     *
     * @return placeholder nodeId
     */
    public String toPlaceholderNodeId() {
      return operatorCode
          + ":"
          + nodeType.toUpperCase(Locale.ROOT)
          + ":"
          + nodeName
          + ":"
          + tracks.firstDeclaredTrack().orElse(1);
    }

    /**
     * 生成 Station/Depot 级别的 terminalKey（用于 layover 匹配，不含 track）。
     *
     * <p>格式：{@code op:s/d:name}（小写）
     *
     * @return terminalKey
     */
    public String toTerminalKey() {
      return operatorCode.toLowerCase(Locale.ROOT)
          + ":"
          + nodeType.toLowerCase(Locale.ROOT)
          + ":"
          + nodeName.toLowerCase(Locale.ROOT);
    }
  }
}

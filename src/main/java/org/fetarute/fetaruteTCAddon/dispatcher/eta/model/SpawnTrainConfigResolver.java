package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * 从 Route CRET depot 推断未发车列车的配置（加减速）。
 *
 * <h2>解析优先级</h2>
 *
 * <ol>
 *   <li>Route 首站 CRET 指向的 depot
 *   <li>depot 牌子第四行的 spawn pattern → 解析 savedTrain 获取 TrainType
 *   <li>若无法解析，fallback 到 config 默认的 TrainType
 * </ol>
 *
 * <h2>用途</h2>
 *
 * <p>ETA 计算在列车未发车时需要知道加减速参数。此工具从 depot 配置推断，而非使用全局默认。
 */
public final class SpawnTrainConfigResolver {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final SignNodeRegistry signNodeRegistry;
  private final ConfigManager.ConfigView config;

  public SpawnTrainConfigResolver(
      SignNodeRegistry signNodeRegistry, ConfigManager.ConfigView config) {
    this.signNodeRegistry = Objects.requireNonNull(signNodeRegistry, "signNodeRegistry");
    this.config = Objects.requireNonNull(config, "config");
  }

  /**
   * 根据 Route 推断未发车列车的配置。
   *
   * @param route Route 实体
   * @param stops Route 对应的停靠列表（用于解析 CRET）
   * @return 推断的列车配置，若无法推断则使用默认配置
   */
  public TrainConfig resolveForRoute(Route route, List<RouteStop> stops) {
    if (route == null || stops == null || stops.isEmpty()) {
      return defaultConfig();
    }

    // 1. 查找 CRET depot
    Optional<String> cretDepot = findCretDepotId(stops);
    if (cretDepot.isEmpty()) {
      return defaultConfig();
    }

    // 2. 从 depot 牌子解析 spawn pattern
    Optional<String> pattern = resolveDepotSpawnPattern(cretDepot.get(), route);
    if (pattern.isEmpty()) {
      return defaultConfig();
    }

    // 3. 从 pattern 推断 TrainType
    Optional<TrainType> trainType = inferTrainTypeFromPattern(pattern.get());
    TrainType type = trainType.orElse(config.trainConfigSettings().defaultTrainType());

    // 4. 根据 TrainType 获取配置
    ConfigManager.TrainTypeSettings settings = config.trainConfigSettings().forType(type);
    return new TrainConfig(type, settings.accelBps2(), settings.decelBps2());
  }

  /**
   * 根据 depot NodeId 和 Route 解析 spawn pattern。
   *
   * @param depotNodeId depot 节点 ID
   * @param route Route 实体（可能在 metadata 中有 pattern）
   * @return spawn pattern 字符串
   */
  public Optional<String> resolveDepotSpawnPattern(String depotNodeId, Route route) {
    // 优先使用 Route metadata 中的 spawn_train_pattern
    if (route != null && route.metadata() != null) {
      Object value = route.metadata().get("spawn_train_pattern");
      if (value instanceof String raw && !raw.isBlank()) {
        return Optional.of(raw.trim());
      }
    }

    // 其次从 depot 牌子读取
    return readPatternFromDepotSign(depotNodeId);
  }

  /** 从 depot 牌子第四行读取 spawn pattern。 */
  private Optional<String> readPatternFromDepotSign(String depotNodeIdStr) {
    if (depotNodeIdStr == null || depotNodeIdStr.isBlank()) {
      return Optional.empty();
    }

    NodeId depotNodeId = NodeId.of(depotNodeIdStr);
    Optional<SignNodeRegistry.SignNodeInfo> infoOpt =
        signNodeRegistry.snapshotInfos().values().stream()
            .filter(info -> info != null && info.definition() != null)
            .filter(info -> depotNodeId.equals(info.definition().nodeId()))
            .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
            .findFirst();

    if (infoOpt.isEmpty()) {
      return Optional.empty();
    }

    SignNodeRegistry.SignNodeInfo info = infoOpt.get();
    World world = Bukkit.getWorld(info.worldId());
    if (world == null) {
      return Optional.empty();
    }

    Block block = world.getBlockAt(info.x(), info.y(), info.z());
    if (!(block.getState() instanceof Sign sign)) {
      return Optional.empty();
    }

    return readPatternFromSign(sign);
  }

  private Optional<String> readPatternFromSign(Sign sign) {
    return readPatternFromSide(sign, Side.FRONT).or(() -> readPatternFromSide(sign, Side.BACK));
  }

  private Optional<String> readPatternFromSide(Sign sign, Side side) {
    SignSide view = sign.getSide(side);
    String header = PLAIN_TEXT.serialize(view.line(0)).trim();
    SignActionHeader parsed = SignActionHeader.parse(header);
    if (parsed == null || (!parsed.isTrain() && !parsed.isCart())) {
      return Optional.empty();
    }
    String type = PLAIN_TEXT.serialize(view.line(1)).trim().toLowerCase(Locale.ROOT);
    if (!"depot".equals(type)) {
      return Optional.empty();
    }
    String pattern = PLAIN_TEXT.serialize(view.line(3)).trim();
    return pattern.isEmpty() ? Optional.empty() : Optional.of(pattern);
  }

  /**
   * 从 spawn pattern 推断 TrainType。
   *
   * <p>解析规则：
   *
   * <ul>
   *   <li>若 pattern 匹配已知 savedTrain 命名约定（如包含 emu/dmu/diesel/electric），则推断类型
   *   <li>否则返回 empty，由调用方使用默认类型
   * </ul>
   */
  private Optional<TrainType> inferTrainTypeFromPattern(String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return Optional.empty();
    }

    String lower = pattern.toLowerCase(Locale.ROOT);

    // 按常见命名约定推断
    if (lower.contains("emu") || lower.contains("electric_multiple")) {
      return Optional.of(TrainType.EMU);
    }
    if (lower.contains("dmu") || lower.contains("diesel_multiple")) {
      return Optional.of(TrainType.DMU);
    }
    if (lower.contains("diesel") && (lower.contains("push") || lower.contains("pull"))) {
      return Optional.of(TrainType.DIESEL_PUSH_PULL);
    }
    if (lower.contains("electric") && lower.contains("loco")) {
      return Optional.of(TrainType.ELECTRIC_LOCO);
    }

    // 无法推断，返回 empty
    return Optional.empty();
  }

  private Optional<String> findCretDepotId(List<RouteStop> stops) {
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    RouteStop firstStop = stops.get(0);
    return SpawnDirectiveParser.findDirectiveTarget(firstStop, "CRET");
  }

  private TrainConfig defaultConfig() {
    TrainType type = config.trainConfigSettings().defaultTrainType();
    ConfigManager.TrainTypeSettings settings = config.trainConfigSettings().forType(type);
    return new TrainConfig(type, settings.accelBps2(), settings.decelBps2());
  }
}

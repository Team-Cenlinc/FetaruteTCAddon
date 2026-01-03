package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 保存牌子注册的节点定义，供调度图构建或 TC 路由同步使用。
 *
 * <p>本注册表以“世界 UUID + 方块坐标”作为唯一键，确保多世界不冲突；同时提供按 {@link NodeId} 查询用于冲突检测与诊断。
 */
public final class SignNodeRegistry {

  private final ConcurrentMap<String, SignNodeInfo> definitions = new ConcurrentHashMap<>();
  private final Consumer<String> debugLogger;

  public SignNodeRegistry() {
    this(message -> {});
  }

  public SignNodeRegistry(Consumer<String> debugLogger) {
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 写入或覆盖指定方块位置的节点定义。
   *
   * <p>注意：同一个方块位置重复建牌会覆盖旧值；而“不同方块但同 NodeId”的冲突由上层在 build 阶段阻止。
   */
  public void put(Block block, SignNodeDefinition definition) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(definition, "definition");
    Location location = block.getLocation();
    World world = location.getWorld();
    if (world == null) {
      throw new IllegalArgumentException("牌子所在方块缺少世界信息");
    }
    put(
        world.getUID(),
        world.getName(),
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ(),
        definition);
    debugLogger.accept("注册节点 " + definition.nodeId().value() + " @ " + formatLocation(block));
  }

  /** 按坐标写入节点定义（用于从存储或扫描结果恢复注册表，不会触发区块加载）。 */
  public void put(
      UUID worldId, String worldName, int x, int y, int z, SignNodeDefinition definition) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(definition, "definition");
    String safeWorldName = worldName == null ? "unknown" : worldName;
    definitions.put(
        key(worldId, x, y, z), new SignNodeInfo(definition, worldId, safeWorldName, x, y, z));
  }

  public Optional<SignNodeDefinition> get(Block block) {
    Objects.requireNonNull(block, "block");
    return Optional.ofNullable(definitions.get(key(block))).map(SignNodeInfo::definition);
  }

  /**
   * 按 NodeId 查找已注册的信息，用于检测“同名节点”冲突。
   *
   * @param nodeId 需要查找的节点 ID
   * @param excludeBlock 可选：排除某个方块（用于“自己更新自己”的场景）
   */
  public Optional<SignNodeInfo> findByNodeId(NodeId nodeId, Block excludeBlock) {
    Objects.requireNonNull(nodeId, "nodeId");
    String excludeKey = excludeBlock != null ? key(excludeBlock) : null;
    return definitions.entrySet().stream()
        .filter(entry -> excludeKey == null || !excludeKey.equals(entry.getKey()))
        .map(Map.Entry::getValue)
        .filter(definition -> definition.definition().nodeId().equals(nodeId))
        .findFirst();
  }

  public Optional<SignNodeDefinition> remove(Block block) {
    Objects.requireNonNull(block, "block");
    SignNodeInfo removed = definitions.remove(key(block));
    if (removed != null) {
      debugLogger.accept(
          "移除节点注册 " + removed.definition().nodeId().value() + " @ " + formatLocation(block));
    }
    return Optional.ofNullable(removed).map(SignNodeInfo::definition);
  }

  /**
   * 返回注册表快照。
   *
   * <p>出于线程安全与封装考虑，这里返回不可变 copy，而不是直接暴露内部 map。
   */
  public Map<String, SignNodeDefinition> snapshot() {
    // 返回快照以保护内部可变状态
    Map<String, SignNodeDefinition> snapshot = new java.util.HashMap<>();
    for (Map.Entry<String, SignNodeInfo> entry : definitions.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().definition());
    }
    return Map.copyOf(snapshot);
  }

  /**
   * 返回包含坐标信息的注册表快照。
   *
   * <p>用于图构建与诊断输出；调用方不得依赖返回值的可变性。
   */
  public Map<String, SignNodeInfo> snapshotInfos() {
    return Map.copyOf(definitions);
  }

  public void clear() {
    definitions.clear();
  }

  /**
   * 将方块位置编码为稳定键。
   *
   * <p>必须使用 block 坐标的离散值，避免浮点位置引入误差；同时使用世界 UUID 而不是 world name，避免改名造成冲突。
   */
  private String key(Block block) {
    Location location = block.getLocation();
    World world = location.getWorld();
    if (world == null) {
      throw new IllegalArgumentException("牌子所在方块缺少世界信息");
    }
    return key(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private String key(UUID worldId, int x, int y, int z) {
    // 直接使用 block 坐标的离散键，不参与浮点运算
    return worldId + ":" + x + ":" + y + ":" + z;
  }

  /** 生成用于日志/提示的可读位置字符串（world + x,y,z）。 */
  private String formatLocation(Block block) {
    Location location = block.getLocation();
    World world = location.getWorld();
    return (world != null ? world.getName() : "unknown")
        + " ("
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ()
        + ")";
  }

  public record SignNodeInfo(
      SignNodeDefinition definition, UUID worldId, String worldName, int x, int y, int z) {
    public SignNodeInfo {
      Objects.requireNonNull(definition, "definition");
      Objects.requireNonNull(worldId, "worldId");
      Objects.requireNonNull(worldName, "worldName");
    }

    /** 以与旧提示一致的格式输出位置（world + x,y,z），供 UI/日志使用。 */
    public String locationText() {
      return worldName + " (" + x + "," + y + "," + z + ")";
    }
  }
}

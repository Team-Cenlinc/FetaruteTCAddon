package org.fetarute.fetaruteTCAddon.dispatcher.graph.portal;

import com.bergerkiller.bukkit.tc.portals.PortalDestination;
import com.bergerkiller.bukkit.tc.portals.TCPortalManager;
import com.bergerkiller.bukkit.tc.portals.plugins.MyWorldsPortalsProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.PortalSignDefinitionParser;

/**
 * 按 MyWorlds 的传送门配置自动连接两边的传送门节点：入口牌子在 MyWorlds 里配置的目的地，经 TrainCarts 解析出目的地轨道， 再找目的地世界里离它最近的传送门节点。
 *
 * <p>MyWorlds 的接口要在主线程调用，且会同步加载目的地区块。没有装 MyWorlds 时什么也不做。
 */
public final class PortalLinkResolver {

  /** 目的地轨道与传送门节点相距不超过这么多格才认。 */
  static final double MATCH_RADIUS_BLOCKS = 4.0;

  /**
   * 解析结果。
   *
   * @param links 连上的
   * @param problems 连不上的原因（给运维看）
   */
  public record Result(List<PortalLink> links, List<String> problems) {
    public Result {
      links = List.copyOf(links);
      problems = List.copyOf(problems);
    }
  }

  private PortalLinkResolver() {}

  /** MyWorlds 传送门是否可用。 */
  public static boolean available() {
    try {
      return TCPortalManager.isAvailable("My_Worlds");
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }

  /** 解析全部已建图世界里的传送门节点。 */
  public static Result resolve(RailGraphService graphs, Instant now) {
    List<PortalLink> links = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    if (!available()) {
      problems.add("MyWorlds 传送门不可用（未安装 MyWorlds，或 TrainCarts 未启用其传送门支持）");
      return new Result(links, problems);
    }
    Map<UUID, RailGraphService.RailGraphSnapshot> snapshots = graphs.snapshotAll();
    for (Map.Entry<UUID, RailGraphService.RailGraphSnapshot> entry : snapshots.entrySet()) {
      World world = Bukkit.getWorld(entry.getKey());
      if (world == null) {
        continue;
      }
      for (RailNode node : entry.getValue().graph().nodes()) {
        if (node.type() != NodeType.PORTAL) {
          continue;
        }
        resolveOne(world, node, graphs, now, problems).ifPresent(links::add);
      }
    }
    return new Result(links, problems);
  }

  private static Optional<PortalLink> resolveOne(
      World world, RailNode node, RailGraphService graphs, Instant now, List<String> problems) {
    Optional<RailBlockPos> signPos =
        PortalSignDefinitionParser.signPos(node.trainCartsDestination());
    if (signPos.isEmpty()) {
      problems.add(node.id().value() + ": 没有记录牌子位置，请重建调度图");
      return Optional.empty();
    }
    Location signLocation =
        new Location(world, signPos.get().x(), signPos.get().y(), signPos.get().z());
    String destinationName;
    PortalDestination destination;
    try {
      destinationName = MyWorldsPortalsProvider.getPortalDestination(signLocation);
      destination =
          destinationName == null
              ? null
              : TCPortalManager.getPortalDestination(world, destinationName);
    } catch (RuntimeException | LinkageError ex) {
      problems.add(node.id().value() + ": 查询 MyWorlds 目的地失败 " + ex.getClass().getSimpleName());
      return Optional.empty();
    }
    if (destinationName == null || destination == null || destination.getRailsBlock() == null) {
      problems.add(node.id().value() + ": MyWorlds 里没有配置目的地，或目的地没有轨道");
      return Optional.empty();
    }
    Block rails = destination.getRailsBlock();
    Optional<RailGraph> targetGraph =
        graphs.getSnapshot(rails.getWorld()).map(RailGraphService.RailGraphSnapshot::graph);
    if (targetGraph.isEmpty()) {
      problems.add(node.id().value() + ": 目的地世界 " + rails.getWorld().getName() + " 还没有调度图");
      return Optional.empty();
    }
    Optional<RailNode> target = nearestPortal(targetGraph.get(), rails.getLocation().toVector());
    if (target.isEmpty() || target.get().id().equals(node.id())) {
      problems.add(node.id().value() + ": 目的地 " + destinationName + " 附近没有传送门节点");
      return Optional.empty();
    }
    return Optional.of(
        new PortalLink(
            world.getUID(),
            node.id(),
            rails.getWorld().getUID(),
            target.get().id(),
            PortalLink.Source.AUTO,
            PortalLink.DEFAULT_TRANSIT_BLOCKS,
            now));
  }

  /** 图里离 {@code position} 最近、且在匹配半径内的传送门节点。 */
  static Optional<RailNode> nearestPortal(RailGraph graph, Vector position) {
    RailNode best = null;
    double bestDistance = MATCH_RADIUS_BLOCKS * MATCH_RADIUS_BLOCKS;
    for (RailNode candidate : graph.nodes()) {
      if (candidate.type() != NodeType.PORTAL) {
        continue;
      }
      double distance = candidate.worldPosition().distanceSquared(position);
      if (distance <= bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    return Optional.ofNullable(best);
  }
}

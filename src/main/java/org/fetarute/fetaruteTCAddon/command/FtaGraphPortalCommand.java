package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLinkResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.GraphSignParsers;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * {@code /fta graph portal}：传送门连接的查看、按 MyWorlds 自动连接、手动连接与删除。
 *
 * <p>只在 {@code graph.cross-world} 开启时可用；传送门节点由建图生成。
 */
public final class FtaGraphPortalCommand {

  private static final String PERMISSION = "fetarute.graph.portal";

  /** 传送门节点补全列表的有效期：逐键补全时不每次遍历整张图。 */
  private static final long PORTAL_IDS_TTL_MILLIS = 5_000L;

  private final FetaruteTCAddon plugin;

  /** 最近一次算出的传送门节点 ID 与算出的时刻（见 {@link #portalNodeIds()}）。 */
  private volatile List<String> portalIds = List.of();

  private volatile long portalIdsAt;

  public FtaGraphPortalCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    var base =
        manager.commandBuilder("fta").literal("graph").literal("portal").permission(PERMISSION);
    manager.command(base.literal("list").handler(ctx -> list(ctx.sender())));
    manager.command(base.literal("scan").handler(ctx -> scan(ctx.sender())));
    // 节点 ID 带冒号（PORTAL:<世界>:x:y:z），客户端按 Brigadier 规则必须加引号，所以用 quotedString 并补全带引号的候选。
    SuggestionProvider<CommandSender> portals =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> candidates(portalNodeIds(), input.lastRemainingToken()));
    SuggestionProvider<CommandSender> linked =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                candidates(
                    plugin.getPortalLinks().links().stream()
                        .map(link -> link.fromNode().value())
                        .toList(),
                    input.lastRemainingToken()));
    manager.command(
        base.literal("link")
            .required("from", StringParser.quotedStringParser(), portals)
            .required("to", StringParser.quotedStringParser(), portals)
            .handler(
                ctx ->
                    link(
                        ctx.sender(),
                        ((String) ctx.get("from")).trim(),
                        ((String) ctx.get("to")).trim())));
    manager.command(
        base.literal("unlink")
            .required("from", StringParser.quotedStringParser(), linked)
            .handler(ctx -> unlink(ctx.sender(), ((String) ctx.get("from")).trim())));
  }

  private boolean ready(CommandSender sender) {
    if (!GraphSignParsers.portalsEnabled()) {
      sender.sendMessage(plugin.getLocaleManager().component("command.graph.portal.disabled"));
      return false;
    }
    return true;
  }

  private void list(CommandSender sender) {
    if (!ready(sender)) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    List<PortalLink> links = plugin.getPortalLinks().links();
    sender.sendMessage(
        locale.component(
            "command.graph.portal.list-header", Map.of("count", String.valueOf(links.size()))));
    for (PortalLink link : links) {
      sender.sendMessage(
          locale.component(
              "command.graph.portal.list-entry",
              Map.of(
                  "from",
                  link.fromNode().value(),
                  "to",
                  link.toNode().value(),
                  "source",
                  link.source().name().toLowerCase(java.util.Locale.ROOT))));
    }
  }

  /** 按 MyWorlds 重新自动连接：手动连接保留不动；这次没解析出来的门保留上次的自动连接；MyWorlds 不可用时什么也不改。先写库（一个事务）、成功后才改内存。 */
  private void scan(CommandSender sender) {
    if (!ready(sender)) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null) {
      sender.sendMessage(locale.component("command.graph.portal.no-graph"));
      return;
    }
    if (!PortalLinkResolver.available()) {
      sender.sendMessage(locale.component("command.graph.portal.myworlds-unavailable"));
      return;
    }
    PortalLinkResolver.Result result = PortalLinkResolver.resolve(graphs, Instant.now());
    PortalLinkRegistry registry = plugin.getPortalLinks();
    Map<NodeId, PortalLink> autoLinks = new LinkedHashMap<>();
    for (PortalLink existing : registry.links()) {
      if (existing.source() == PortalLink.Source.AUTO) {
        autoLinks.put(existing.fromNode(), existing);
      }
    }
    int linked = 0;
    for (PortalLink resolved : result.links()) {
      boolean manual =
          registry
              .from(resolved.fromNode())
              .map(link -> link.source() == PortalLink.Source.MANUAL)
              .orElse(false);
      if (!manual) {
        autoLinks.put(resolved.fromNode(), resolved);
        linked++;
      }
    }
    List<PortalLink> finalAuto = List.copyOf(autoLinks.values());
    if (!persist(
        sender,
        provider -> {
          provider.portalLinks().deleteAuto();
          for (PortalLink link : finalAuto) {
            provider.portalLinks().upsert(link);
          }
        })) {
      return;
    }
    registry.removeAuto();
    finalAuto.forEach(registry::put);
    sender.sendMessage(
        locale.component(
            "command.graph.portal.scanned",
            Map.of(
                "linked",
                String.valueOf(linked),
                "problems",
                String.valueOf(result.problems().size()))));
    for (String problem : result.problems()) {
      sender.sendMessage(
          locale.component("command.graph.portal.problem", Map.of("problem", problem)));
    }
  }

  private void link(CommandSender sender, String from, String to) {
    if (!ready(sender)) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Optional<Located> fromNode = findPortal(from);
    Optional<Located> toNode = findPortal(to);
    if (fromNode.isEmpty() || toNode.isEmpty() || from.equals(to)) {
      sender.sendMessage(locale.component("command.graph.portal.not-found"));
      return;
    }
    PortalLink link =
        new PortalLink(
            fromNode.get().world(),
            fromNode.get().node().id(),
            toNode.get().world(),
            toNode.get().node().id(),
            PortalLink.Source.MANUAL,
            PortalLink.DEFAULT_TRANSIT_BLOCKS,
            Instant.now());
    if (!persist(sender, provider -> provider.portalLinks().upsert(link))) {
      return;
    }
    plugin.getPortalLinks().put(link);
    sender.sendMessage(
        locale.component("command.graph.portal.linked", Map.of("from", from, "to", to)));
  }

  /** 删除连接：两个方向都删（路网把一对门两个方向的连接合成一条边，只删一个方向连不断）。 */
  private void unlink(CommandSender sender, String from) {
    if (!ready(sender)) {
      return;
    }
    PortalLinkRegistry registry = plugin.getPortalLinks();
    Optional<PortalLink> link = registry.from(NodeId.of(from));
    if (link.isEmpty()) {
      sender.sendMessage(plugin.getLocaleManager().component("command.graph.portal.not-found"));
      return;
    }
    List<PortalLink> removed = new ArrayList<>();
    removed.add(link.get());
    registry
        .from(link.get().toNode())
        .filter(back -> back.toNode().equals(link.get().fromNode()))
        .ifPresent(removed::add);
    if (!persist(
        sender,
        provider -> {
          for (PortalLink each : removed) {
            provider.portalLinks().delete(each.fromWorld(), each.fromNode());
          }
        })) {
      return;
    }
    removed.forEach(each -> registry.remove(each.fromNode()));
    sender.sendMessage(
        plugin.getLocaleManager().component("command.graph.portal.unlinked", Map.of("from", from)));
  }

  /** 写库（一个事务）；存储未就绪时只改内存。失败时提示并返回 {@code false}，调用方不再改内存。 */
  private boolean persist(
      CommandSender sender, java.util.function.Consumer<StorageProvider> write) {
    Optional<StorageProvider> provider = storage();
    if (provider.isEmpty()) {
      return true;
    }
    try {
      provider
          .get()
          .transactionManager()
          .execute(
              () -> {
                write.accept(provider.get());
                return null;
              });
      return true;
    } catch (Exception ex) {
      plugin.getLogger().warning("保存传送门连接失败: " + ex.getMessage());
      sender.sendMessage(plugin.getLocaleManager().component("command.graph.portal.save-failed"));
      return false;
    }
  }

  private record Located(UUID world, RailNode node) {}

  /** 各世界路网里的传送门节点 ID，按字母序；结果缓存几秒，逐键补全时不每次遍历全部节点。 */
  private List<String> portalNodeIds() {
    long now = System.currentTimeMillis();
    if (now - portalIdsAt < PORTAL_IDS_TTL_MILLIS) {
      return portalIds;
    }
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null) {
      return List.of();
    }
    java.util.TreeSet<String> ids = new java.util.TreeSet<>();
    for (RailGraphService.RailGraphSnapshot snapshot : graphs.snapshotAll().values()) {
      for (RailNode node : snapshot.graph().nodes()) {
        if (node.type() == NodeType.PORTAL) {
          ids.add(node.id().value());
        }
      }
    }
    portalIds = List.copyOf(ids);
    portalIdsAt = now;
    return portalIds;
  }

  /** 以输入开头（不分大小写、不计开头的引号）的节点 ID，加双引号。 */
  private static List<String> candidates(List<String> ids, String token) {
    String prefix = CommandUx.suggestionPrefix(token);
    return ids.stream()
        .filter(id -> id.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
        .limit(40)
        .map(CommandUx::quoteCommandArgument)
        .toList();
  }

  private Optional<Located> findPortal(String id) {
    RailGraphService graphs = plugin.getRailGraphService();
    if (graphs == null) {
      return Optional.empty();
    }
    NodeId nodeId = NodeId.of(id);
    for (Map.Entry<UUID, RailGraphService.RailGraphSnapshot> entry :
        graphs.snapshotAll().entrySet()) {
      Optional<RailNode> node = entry.getValue().graph().findNode(nodeId);
      if (node.isPresent() && node.get().type() == NodeType.PORTAL) {
        return Optional.of(new Located(entry.getKey(), node.get()));
      }
    }
    return Optional.empty();
  }

  private Optional<StorageProvider> storage() {
    if (plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()) {
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
  }
}

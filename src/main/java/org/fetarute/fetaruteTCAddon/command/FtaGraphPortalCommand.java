package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
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
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.StringParser;

/**
 * {@code /fta graph portal}：传送门连接的查看、按 MyWorlds 自动连接、手动连接与删除。
 *
 * <p>只在 {@code graph.cross-world} 开启时可用；传送门节点由建图生成。
 */
public final class FtaGraphPortalCommand {

  private static final String PERMISSION = "fetarute.graph.portal";

  private final FetaruteTCAddon plugin;

  public FtaGraphPortalCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    var base =
        manager.commandBuilder("fta").literal("graph").literal("portal").permission(PERMISSION);
    manager.command(base.literal("list").handler(ctx -> list(ctx.sender())));
    manager.command(base.literal("scan").handler(ctx -> scan(ctx.sender())));
    manager.command(
        base.literal("link")
            .required("from", StringParser.stringParser())
            .required("to", StringParser.stringParser())
            .optional("transit", DoubleParser.doubleParser(0.5, 512.0))
            .handler(
                ctx ->
                    link(
                        ctx.sender(),
                        ((String) ctx.get("from")).trim(),
                        ((String) ctx.get("to")).trim(),
                        ctx.optional("transit").map(Double.class::cast).orElse(null))));
    manager.command(
        base.literal("unlink")
            .required("from", StringParser.stringParser())
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
                  link.source().name().toLowerCase(java.util.Locale.ROOT),
                  "transit",
                  String.format(java.util.Locale.ROOT, "%.1f", link.transitBlocks()))));
    }
  }

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
    PortalLinkResolver.Result result = PortalLinkResolver.resolve(graphs, Instant.now());
    PortalLinkRegistry registry = plugin.getPortalLinks();
    registry.removeAuto();
    result.links().forEach(registry::put);
    storage()
        .ifPresent(
            provider -> {
              provider.portalLinks().deleteAuto();
              for (PortalLink link : result.links()) {
                provider.portalLinks().upsert(link);
              }
            });
    sender.sendMessage(
        locale.component(
            "command.graph.portal.scanned",
            Map.of(
                "linked",
                String.valueOf(result.links().size()),
                "problems",
                String.valueOf(result.problems().size()))));
    for (String problem : result.problems()) {
      sender.sendMessage(
          locale.component("command.graph.portal.problem", Map.of("problem", problem)));
    }
  }

  private void link(CommandSender sender, String from, String to, Double transit) {
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
            transit == null ? PortalLink.DEFAULT_TRANSIT_BLOCKS : transit,
            Instant.now());
    plugin.getPortalLinks().put(link);
    storage().ifPresent(provider -> provider.portalLinks().upsert(link));
    sender.sendMessage(
        locale.component("command.graph.portal.linked", Map.of("from", from, "to", to)));
  }

  private void unlink(CommandSender sender, String from) {
    if (!ready(sender)) {
      return;
    }
    Optional<PortalLink> link = plugin.getPortalLinks().from(NodeId.of(from));
    if (link.isEmpty()) {
      sender.sendMessage(plugin.getLocaleManager().component("command.graph.portal.not-found"));
      return;
    }
    plugin.getPortalLinks().remove(link.get().fromNode());
    storage()
        .ifPresent(
            provider ->
                provider.portalLinks().delete(link.get().fromWorld(), link.get().fromNode()));
    sender.sendMessage(
        plugin.getLocaleManager().component("command.graph.portal.unlinked", Map.of("from", from)));
  }

  private record Located(UUID world, RailNode node) {}

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

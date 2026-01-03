package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeStorageSynchronizer;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * Depot 牌子（车库行为节点）。
 *
 * <p>用于承载“发车/回库/销毁”等车库行为，因此只接受车库本体（4 段 {@code Operator:D:Depot:Track}）。 车库咽喉属于图节点（Waypoint）职责，不应使用
 * Depot 牌子注册。
 */
public final class DepotSignAction extends AbstractNodeSignAction {

  public DepotSignAction(
      SignNodeRegistry registry,
      Consumer<String> debugLogger,
      LocaleManager locale,
      SignNodeStorageSynchronizer storageSync) {
    super("depot", registry, NodeType.DEPOT, debugLogger, locale, storageSync);
  }

  public DepotSignAction(
      SignNodeRegistry registry, Consumer<String> debugLogger, LocaleManager locale) {
    this(registry, debugLogger, locale, SignNodeStorageSynchronizer.noop());
  }

  @Override
  protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
    return super.parseDefinition(info)
        .filter(
            definition ->
                definition
                    .waypointMetadata()
                    .map(metadata -> metadata.kind() == WaypointKind.DEPOT)
                    .orElse(false));
  }
}

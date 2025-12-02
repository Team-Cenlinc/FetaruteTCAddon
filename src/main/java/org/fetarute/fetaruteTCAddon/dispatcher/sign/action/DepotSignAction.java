package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Depot throat 牌子，写入 Depot 节点并把 destination 提供给 TC。
 */
public final class DepotSignAction extends AbstractNodeSignAction {

    public DepotSignAction(SignNodeRegistry registry, Consumer<String> debugLogger, LocaleManager locale) {
        super("depot", registry, NodeType.DEPOT, debugLogger, locale);
    }

    @Override
    protected Optional<SignNodeDefinition> parseDefinition(SignActionEvent info) {
        return super.parseDefinition(info)
                .filter(definition -> definition.waypointMetadata()
                        .map(metadata -> metadata.kind() == WaypointKind.DEPOT)
                        .orElse(false));
    }
}

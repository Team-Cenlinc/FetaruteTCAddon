package org.fetarute.fetaruteTCAddon;

import com.bergerkiller.bukkit.tc.signactions.SignAction;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.DepotSignAction;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.WaypointSignAction;

import java.util.List;

public final class FetaruteTCAddon extends JavaPlugin {

    private SignNodeRegistry signNodeRegistry;
    private List<SignAction> registeredSignActions;

    @Override
    public void onEnable() {
        // 注册自定义牌子以便 TC 与调度层识别节点
        signNodeRegistry = new SignNodeRegistry();
        registeredSignActions = List.of(
                new DepotSignAction(signNodeRegistry),
                new AutoStationSignAction(signNodeRegistry),
                new WaypointSignAction(signNodeRegistry)
        );
        registeredSignActions.forEach(SignAction::register);
    }

    @Override
    public void onDisable() {
        if (registeredSignActions != null) {
            registeredSignActions.forEach(SignAction::unregister);
        }
        if (signNodeRegistry != null) {
            signNodeRegistry.clear();
        }
    }
}

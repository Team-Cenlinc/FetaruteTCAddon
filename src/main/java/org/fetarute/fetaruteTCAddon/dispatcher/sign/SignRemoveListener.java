package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Map;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 监听牌子拆除，发送提示并清理注册表。 */
public final class SignRemoveListener implements Listener {

  private final SignNodeRegistry registry;
  private final LocaleManager locale;

  public SignRemoveListener(SignNodeRegistry registry, LocaleManager locale) {
    this.registry = registry;
    this.locale = locale;
  }

  @EventHandler
  public void onBlockBreak(BlockBreakEvent event) {
    if (!(event.getBlock().getState() instanceof Sign)) {
      return;
    }
    registry
        .remove(event.getBlock())
        .ifPresent(
            definition -> {
              event
                  .getPlayer()
                  .sendMessage(
                      locale.component(
                          "sign.removed", Map.of("node", definition.nodeId().value())));
            });
  }
}

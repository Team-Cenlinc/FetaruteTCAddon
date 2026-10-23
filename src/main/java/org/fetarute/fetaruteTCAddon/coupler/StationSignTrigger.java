package org.fetarute.fetaruteTCAddon.coupler;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/** 对列车重新触发一条股道的车站牌子：拆挂之后车身没有再压过牌子，新拼成或新拆出的列车要在本站重新走一遍到站、开门、停站与发车。只在主线程调用。 */
final class StationSignTrigger {

  private StationSignTrigger() {}

  /**
   * @return 触发了时为空；否则为失败原因（{@code sign-missing} 或异常）
   */
  static Optional<String> arrive(FetaruteTCAddon plugin, MinecartGroup group, NodeId platform) {
    if (group == null || !group.isValid()) {
      return Optional.of("group-invalid");
    }
    SignNodeRegistry registry = plugin.getSignNodeRegistry();
    Optional<SignNodeRegistry.SignNodeInfo> sign =
        registry == null ? Optional.empty() : registry.findByNodeId(platform, null);
    World world = sign.map(info -> Bukkit.getWorld(info.worldId())).orElse(null);
    if (sign.isEmpty() || world == null) {
      return Optional.of("sign-missing");
    }
    Block block = world.getBlockAt(sign.get().x(), sign.get().y(), sign.get().z());
    try {
      SignAction.executeAll(
          new SignActionEvent(RailLookup.TrackedSign.forRealSign(block, true, null), group),
          SignActionType.GROUP_ENTER);
      return Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.of(ex.toString());
    }
  }
}

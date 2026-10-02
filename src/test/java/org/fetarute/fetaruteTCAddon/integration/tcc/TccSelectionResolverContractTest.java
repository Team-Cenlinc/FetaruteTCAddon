package org.fetarute.fetaruteTCAddon.integration.tcc;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** 当前 TCCoasters 测试依赖与反射适配器之间的 ABI 契约测试。 */
class TccSelectionResolverContractTest {

  @Test
  void currentTccDependencyExposesEveryReflectedSelectionMethod() throws Exception {
    Class<?> pluginType = Class.forName("com.bergerkiller.bukkit.coasters.TCCoasters");
    Class<?> editStateType =
        Class.forName("com.bergerkiller.bukkit.coasters.editor.PlayerEditState");
    Class<?> trackNodeType = Class.forName("com.bergerkiller.bukkit.coasters.tracks.TrackNode");

    assertNotNull(pluginType.getMethod("getEditState", Player.class));
    assertNotNull(editStateType.getMethod("hasEditedNodes"));
    assertNotNull(editStateType.getMethod("getLastEditedNode"));
    assertNotNull(editStateType.getMethod("findLookingAt"));
    assertNotNull(editStateType.getMethod("findLookingAtRailBlock"));
    assertNotNull(trackNodeType.getMethod("getCoaster"));
    assertNotNull(trackNodeType.getMethod("getRailBlock", boolean.class));
    assertNotNull(trackNodeType.getMethod("getPositionBlock"));
  }
}

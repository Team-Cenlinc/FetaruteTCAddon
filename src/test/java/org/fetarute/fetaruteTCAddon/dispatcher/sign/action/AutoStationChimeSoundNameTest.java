package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** 配置里的常量式声音名按原版注册表 key 反查：与 Bukkit 旧 Sound 枚举常量的命名规则一致。 */
class AutoStationChimeSoundNameTest {

  @Test
  void vanillaKeysMapToTheLegacyConstantNames() {
    assertEquals(
        "BLOCK_NOTE_BLOCK_BELL",
        AutoStationDoorController.soundConstantName(
            NamespacedKey.minecraft("block.note_block.bell")));
    assertEquals(
        "ENTITY_EXPERIENCE_ORB_PICKUP",
        AutoStationDoorController.soundConstantName(
            NamespacedKey.minecraft("entity.experience_orb.pickup")));
  }

  /** 资源包的自定义声音不参与常量名匹配，调用方直接按原字符串播放。 */
  @Test
  void customNamespacesHaveNoConstantName() {
    assertNull(
        AutoStationDoorController.soundConstantName(
            new NamespacedKey("fetarute", "door.close_chime")));
    assertNull(AutoStationDoorController.soundConstantName(null));
  }
}

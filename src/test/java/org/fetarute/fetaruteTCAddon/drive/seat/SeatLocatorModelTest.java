package org.fetarute.fetaruteTCAddon.drive.seat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("标记驾驶座：只改车厢自己的模型")
class SeatLocatorModelTest {

  @Test
  @DisplayName("座位配置挂在车厢模型下才算自己的；共用模型里的不算")
  void seatConfigMustBelongToTheCartModel() {
    ConfigurationNode cartModel = new ConfigurationNode();
    ConfigurationNode seat =
        cartModel.getNode("attachments").getNode("0").getNode("attachments").getNode("1");
    ConfigurationNode sharedModel = new ConfigurationNode();
    ConfigurationNode sharedSeat = sharedModel.getNode("attachments").getNode("0");

    assertTrue(SeatLocator.descendsFrom(seat, cartModel));
    assertTrue(SeatLocator.descendsFrom(cartModel, cartModel));
    assertFalse(SeatLocator.descendsFrom(sharedSeat, cartModel));
  }
}

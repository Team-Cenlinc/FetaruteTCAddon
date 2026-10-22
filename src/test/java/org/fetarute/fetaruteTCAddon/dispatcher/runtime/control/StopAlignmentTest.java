package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.attachments.config.AttachmentModel;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StopAlignment 停车对位")
class StopAlignmentTest {

  @Test
  @DisplayName("沿前进方向量：越过为正，未到为负，只看水平面")
  void signedOffsetAlongTravel() {
    Vector stop = new Vector(100.5, 64.0, 200.5);
    Vector east = new Vector(1, 0, 0);
    assertEquals(3.0, StopAlignment.signedOffset(new Vector(103.5, 70.0, 200.5), stop, east), 1e-9);
    assertEquals(-2.0, StopAlignment.signedOffset(new Vector(98.5, 64.0, 201.5), stop, east), 1e-9);
    Vector diagonal = new Vector(1, 0, 1);
    assertEquals(
        Math.sqrt(2.0),
        StopAlignment.signedOffset(new Vector(101.5, 64.0, 201.5), stop, diagonal),
        1e-9);
    assertTrue(Double.isNaN(StopAlignment.signedOffset(stop, stop, new Vector(0, 1, 0))));
  }

  @Test
  @DisplayName("停着的车中心已越过车站牌子：按牌子居中要往回开（拆开后解下的前一段在牌子前方）；未到、正对、量不出时照常居中")
  void centerPastTheSignNeedsBackingUp() {
    org.bukkit.util.Vector sign = new org.bukkit.util.Vector(447.5, 64.0, 1028.5);
    org.bukkit.util.Vector detachedFront = new org.bukkit.util.Vector(453.8, 64.0, 1028.5);
    double ahead =
        StopAlignment.signedOffset(detachedFront, sign, new org.bukkit.util.Vector(1, 0, 0));
    assertTrue(StopAlignment.centerPastStopPoint(ahead), "往东开、车在牌子东边 6.3 格");
    double behind =
        StopAlignment.signedOffset(detachedFront, sign, new org.bukkit.util.Vector(-1, 0, 0));
    assertFalse(StopAlignment.centerPastStopPoint(behind), "往西开时它还没到牌子：往前居中");
    assertFalse(StopAlignment.centerPastStopPoint(0.0));
    assertFalse(StopAlignment.centerPastStopPoint(Double.NaN));
  }

  @Test
  @DisplayName("车头最前端：第一节车厢中心沿前进方向前推半个车体长度，只在水平面上推")
  void frontIsHalfACarAheadOfTheFirstCar() {
    Vector head = new Vector(100.5, 64.0, 200.5);
    Vector front = StopAlignment.front(head, new Vector(3.0, 1.0, 4.0), 5.0);
    assertEquals(103.5, front.getX(), 1e-9);
    assertEquals(64.0, front.getY(), 1e-9);
    assertEquals(204.5, front.getZ(), 1e-9);
    assertEquals(100.5, head.getX(), 1e-9, "不改动传入的车厢中心");
    assertEquals(head, StopAlignment.front(head, null, 5.0), "量不出方向：就是车厢中心");
    assertEquals(head, StopAlignment.front(head, new Vector(1, 0, 0), 0.0), "量不出车体长度：就是车厢中心");
  }

  @Test
  @DisplayName("前推距离取第一节车厢的车体长度（附件模型 cart length）的一半；取不到时为 0")
  void frontOffsetIsHalfTheCartLength() {
    CartProperties properties = mock(CartProperties.class);
    AttachmentModel model = mock(AttachmentModel.class);
    when(properties.getModel()).thenReturn(model);
    when(model.getCartLength()).thenReturn(12.0f);
    assertEquals(6.0, StopAlignment.halfCartLength(properties), 1e-9);

    when(properties.getModel()).thenReturn(null);
    assertEquals(0.0, StopAlignment.halfCartLength(properties), 1e-9);
    assertEquals(0.0, StopAlignment.halfCartLength(null), 1e-9);
  }
}

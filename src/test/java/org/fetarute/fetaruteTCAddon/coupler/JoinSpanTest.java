package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 先到的车停在整列中点前方多少格：按各节车的半车长与车钩长逐节累加。 */
class JoinSpanTest {

  private static final JoinSpan.CarSize TEN = new JoinSpan.CarSize(5.0, 0.25);
  private static final JoinSpan.CarSize TWENTY = new JoinSpan.CarSize(10.0, 0.25);

  @Test
  void twoEqualSingleCarsMeetHalfACarSpacingBehindTheHead() {
    // 中心距 5 + 0.25 + 5 + 0.25 = 10.5，中点在第一节中心后 5.25 格
    assertEquals(5.25, JoinSpan.midpointLead(List.of(TEN), List.of(), 1), 1e-9);
    assertEquals(5.25, JoinSpan.midpointLead(List.of(TEN), List.of(TEN), 1), 1e-9);
  }

  @Test
  void aLongerPartnerAlreadyOnTheLineIsMeasuredAsItIs() {
    // 10 格单节先到，三节 20 格主干挂上来：15.5 + 20.5 + 20.5 = 56.5
    assertEquals(
        28.25, JoinSpan.midpointLead(List.of(TEN), List.of(TWENTY, TWENTY, TWENTY), 3), 1e-9);
    // 还没出车：按先到那列最后一节估
    assertEquals(15.75, JoinSpan.midpointLead(List.of(TEN), List.of(), 3), 1e-9);
  }

  @Test
  void nothingToJoinIsUnknown() {
    assertTrue(Double.isNaN(JoinSpan.midpointLead(List.of(TEN), List.of(), 0)));
    assertTrue(Double.isNaN(JoinSpan.midpointLead(List.of(), List.of(TEN), 1)));
  }
}

package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.standard.type.ExitOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停站下车偏移只改开门车厢")
class AutoStationExitOffsetTest {

  private static final ExitOffset PLATFORM = ExitOffset.create(3.0, 0.0, 0.0, 0.0f, 0.0f);

  /** 车厢属性的下车偏移按内存字段读写。 */
  private static CartProperties cart(ExitOffset initial, AtomicReference<ExitOffset> value) {
    value.set(initial);
    CartProperties cart = mock(CartProperties.class);
    when(cart.getExitOffset()).thenAnswer(inv -> value.get());
    doAnswer(
            inv -> {
              value.set(inv.getArgument(0));
              return null;
            })
        .when(cart)
        .setExitOffset(any());
    return cart;
  }

  @Test
  @DisplayName("只改给定的车厢，发车前各节改回自己原来的值")
  void appliesAndRestoresPerCart() {
    ExitOffset firstOriginal = ExitOffset.create(0.0, 1.0, 0.0, 0.0f, 0.0f);
    ExitOffset secondOriginal = ExitOffset.create(-1.0, 0.0, 0.0, 0.0f, 0.0f);
    AtomicReference<ExitOffset> first = new AtomicReference<>();
    AtomicReference<ExitOffset> second = new AtomicReference<>();
    AtomicReference<ExitOffset> closed = new AtomicReference<>();
    CartProperties closedCart = cart(secondOriginal, closed);
    AutoStationSignAction.ExitOffsetState state =
        new AutoStationSignAction.ExitOffsetState(
            List.of(cart(firstOriginal, first), cart(secondOriginal, second)));

    assertFalse(state.applied());
    state.apply(PLATFORM);
    assertTrue(state.applied());
    assertSame(PLATFORM, first.get());
    assertSame(PLATFORM, second.get());
    verify(closedCart, never()).setExitOffset(any());

    state.restore();
    assertFalse(state.applied());
    assertSame(firstOriginal, first.get());
    assertSame(secondOriginal, second.get());

    state.restore();
    assertSame(firstOriginal, first.get(), "没改过时不再写");
  }

  @Test
  @DisplayName("原来没有偏移时改回默认值；没有偏移可设时什么也不做")
  void restoresDefaultAndIgnoresNull() {
    AtomicReference<ExitOffset> value = new AtomicReference<>();
    AutoStationSignAction.ExitOffsetState state =
        new AutoStationSignAction.ExitOffsetState(List.of(cart(null, value)));

    state.apply(null);
    assertFalse(state.applied());

    state.apply(PLATFORM);
    state.restore();
    assertEquals(ExitOffset.DEFAULT, value.get());
  }
}

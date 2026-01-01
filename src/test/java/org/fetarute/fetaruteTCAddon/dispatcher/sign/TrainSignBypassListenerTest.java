package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TrainSignBypassListenerTest {

  @Test
  void cancelsNonWhitelistedSignsWhenBypassPredicateTrue() {
    TrainSignBypassListener listener = new TrainSignBypassListener(message -> {}, event -> true);

    SignActionEvent event = mock(SignActionEvent.class);
    when(event.isType("destroy")).thenReturn(false);
    when(event.isType("switcher")).thenReturn(false);

    listener.onSignActionEarly(event);

    verify(event).setCancelled(true);
  }

  @Test
  void doesNotCancelDestroySignWhenBypassPredicateTrue() {
    TrainSignBypassListener listener = new TrainSignBypassListener(message -> {}, event -> true);

    SignActionEvent event = mock(SignActionEvent.class);
    when(event.isType("destroy")).thenReturn(true);

    listener.onSignActionEarly(event);

    verify(event, never()).setCancelled(true);
  }

  @Test
  void doesNotCancelSwitcherSignWhenBypassPredicateTrue() {
    TrainSignBypassListener listener = new TrainSignBypassListener(message -> {}, event -> true);

    SignActionEvent event = mock(SignActionEvent.class);
    when(event.isType("destroy")).thenReturn(false);
    when(event.isType("switcher")).thenReturn(true);

    listener.onSignActionEarly(event);

    verify(event, never()).setCancelled(true);
  }

  @Test
  void doesNotCancelWhenBypassPredicateFalse() {
    TrainSignBypassListener listener = new TrainSignBypassListener(message -> {}, event -> false);

    SignActionEvent event = mock(SignActionEvent.class);

    listener.onSignActionEarly(event);

    verify(event, never()).setCancelled(true);
  }

  @Test
  void doesNothingWhenAlreadyCancelled() {
    TrainSignBypassListener listener = new TrainSignBypassListener(message -> {}, event -> true);

    SignActionEvent event = mock(SignActionEvent.class);
    when(event.isCancelled()).thenReturn(true);

    listener.onSignActionEarly(event);

    verify(event, never()).setCancelled(true);
  }

  @Test
  void debugLogOnEarlyWhenDebugPredicateTrue() {
    AtomicInteger debugCount = new AtomicInteger(0);
    TrainSignBypassListener listener =
        new TrainSignBypassListener(
            message -> debugCount.incrementAndGet(),
            ignoredEvent -> true,
            (ignoredEvent, ignoredAction) -> true);

    SignActionEvent event = mock(SignActionEvent.class);
    when(event.isType("destroy")).thenReturn(false);
    when(event.isType("switcher")).thenReturn(false);

    listener.onSignActionEarly(event);
    verify(event).setCancelled(true);

    assertEquals(1, debugCount.get());
  }

  @Test
  void lateHandlerDoesNotDebugLogEvenWhenDebugPredicateTrue() {
    AtomicInteger debugCount = new AtomicInteger(0);
    TrainSignBypassListener listener =
        new TrainSignBypassListener(
            message -> debugCount.incrementAndGet(),
            ignoredEvent -> true,
            (ignoredEvent, ignoredAction) -> true);

    SignActionEvent event = mock(SignActionEvent.class);
    when(event.isType("destroy")).thenReturn(false);
    when(event.isType("switcher")).thenReturn(false);

    listener.onSignActionLate(event);

    verify(event).setCancelled(true);
    verify(event, never()).getAction();
    assertEquals(0, debugCount.get());
  }
}

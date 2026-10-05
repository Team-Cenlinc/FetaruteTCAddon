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
  void portalSignPassesOnlyWhenCrossWorldIsEnabled() {
    TrainSignBypassListener listener = new TrainSignBypassListener(message -> {}, event -> true);
    boolean before = GraphSignParsers.portalsEnabled();
    try {
      GraphSignParsers.setPortalsEnabled(true);
      SignActionEvent enabled = mock(SignActionEvent.class);
      when(enabled.getHeader())
          .thenReturn(com.bergerkiller.bukkit.tc.SignActionHeader.parse("[portal]"));
      listener.onSignActionEarly(enabled);
      verify(enabled, never()).setCancelled(true);

      GraphSignParsers.setPortalsEnabled(false);
      SignActionEvent disabled = mock(SignActionEvent.class);
      when(disabled.getHeader())
          .thenReturn(com.bergerkiller.bukkit.tc.SignActionHeader.parse("[portal]"));
      listener.onSignActionEarly(disabled);
      verify(disabled).setCancelled(true);
    } finally {
      GraphSignParsers.setPortalsEnabled(before);
    }
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

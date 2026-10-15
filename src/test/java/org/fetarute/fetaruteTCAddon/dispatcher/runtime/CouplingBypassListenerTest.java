package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CouplingOperations;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.coupling.CouplingRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 插件自己发起的拆分/联挂不当事故处理：不隔离、不否决、不全网先停车、不按物理身份清理；没登记的照旧。 */
class CouplingBypassListenerTest {

  private CouplingRuntime coupling;
  private RuntimeDispatchListener listener;
  private final AtomicInteger ran = new AtomicInteger();

  @BeforeEach
  void setUp() {
    RuntimeDispatchService service = mock(RuntimeDispatchService.class);
    coupling = new CouplingRuntime(name -> {});
    when(service.coupling()).thenReturn(coupling);
    listener =
        new RuntimeDispatchListener(service, Runnable::run, message -> {}, new AtomicLong()::get);
  }

  private void runAllHandlers(Object first, Object second) {
    listener.handleGroupLinkVeto(first, second, ran::incrementAndGet);
    listener.handleGroupLink(first, second, ran::incrementAndGet);
    listener.handleMemberRemove(first, ran::incrementAndGet);
    listener.handleGroupRemoval(first, ran::incrementAndGet);
  }

  @Test
  void registeredGroupsSkipEveryAccidentPath() {
    Object moving = new Object();
    Object standing = new Object();

    try (CouplingOperations.Scope ignored =
        coupling.operations().begin(List.of(moving, standing))) {
      runAllHandlers(moving, standing);
    }

    assertEquals(0, ran.get());
  }

  @Test
  void unregisteredGroupsTakeEveryAccidentPath() {
    runAllHandlers(new Object(), new Object());

    assertEquals(4, ran.get());
  }

  @Test
  void theBypassEndsWithTheScope() {
    Object source = new Object();
    try (CouplingOperations.Scope ignored = coupling.operations().begin(List.of(source))) {
      listener.handleMemberRemove(source, ran::incrementAndGet);
    }

    listener.handleMemberRemove(source, ran::incrementAndGet);

    assertEquals(1, ran.get(), "范围关闭后同一编组的成员移除照常隔离");
  }
}

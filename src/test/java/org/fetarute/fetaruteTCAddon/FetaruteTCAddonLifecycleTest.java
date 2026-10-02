package org.fetarute.fetaruteTCAddon;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.lang.reflect.Field;
import java.time.Instant;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalEvaluator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

class FetaruteTCAddonLifecycleTest {

  @Test
  void disableClosesInternalAuthorizationWithoutStartingTrainCartsContainment() throws Exception {
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class, CALLS_REAL_METHODS);
    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    TicketAssigner spawnTicketAssigner = mock(TicketAssigner.class);
    SignalEvaluator signalEvaluator = mock(SignalEvaluator.class);
    when(runtimeDispatchService.retryPersistentMaterializedSpawnRollbackRemovals())
        .thenReturn(true);
    when(spawnTicketAssigner.prepareForReplacement(org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(true);
    setField(plugin, "runtimeDispatchService", runtimeDispatchService);
    setField(plugin, "spawnTicketAssigner", spawnTicketAssigner);
    setField(plugin, "signalEvaluator", signalEvaluator);

    try (MockedStatic<FetaruteApi> api = mockStatic(FetaruteApi.class);
        MockedStatic<MinecartGroupStore> groups = mockStatic(MinecartGroupStore.class);
        MockedStatic<TrainPropertiesStore> properties = mockStatic(TrainPropertiesStore.class)) {
      plugin.onDisable();

      api.verify(FetaruteApi::shutdown);
      groups.verifyNoInteractions();
      properties.verifyNoInteractions();
    }

    InOrder shutdownOrder = inOrder(runtimeDispatchService, signalEvaluator);
    shutdownOrder.verify(runtimeDispatchService).beginPluginShutdown();
    shutdownOrder.verify(signalEvaluator).stop();
    shutdownOrder.verify(runtimeDispatchService).setSignalReevaluationRequester(isNull());
    verify(runtimeDispatchService, never()).retryPersistentMaterializedSpawnRollbackRemovals();
    verify(spawnTicketAssigner, never())
        .prepareForReplacement(org.mockito.ArgumentMatchers.any(Instant.class));
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = FetaruteTCAddon.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}

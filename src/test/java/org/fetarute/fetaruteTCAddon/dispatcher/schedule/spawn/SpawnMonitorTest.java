package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class SpawnMonitorTest {

  @Test
  void runSkipsWhenSpawnDisabled() {
    StorageManager storageManager = mock(StorageManager.class);
    ConfigManager configManager = mock(ConfigManager.class);
    TicketAssigner assigner = mock(TicketAssigner.class);

    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawn = mock(ConfigManager.SpawnSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawn);
    when(spawn.enabled()).thenReturn(false);

    SpawnMonitor monitor = new SpawnMonitor(storageManager, configManager, assigner);
    monitor.run();

    verify(assigner, never()).tick(any(), any());
  }

  @Test
  void runSkipsWhenStorageUnavailable() {
    StorageManager storageManager = mock(StorageManager.class);
    ConfigManager configManager = mock(ConfigManager.class);
    TicketAssigner assigner = mock(TicketAssigner.class);

    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawn = mock(ConfigManager.SpawnSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawn);
    when(spawn.enabled()).thenReturn(true);
    when(storageManager.isReady()).thenReturn(false);

    SpawnMonitor monitor = new SpawnMonitor(storageManager, configManager, assigner);
    monitor.run();

    verify(assigner, never()).tick(any(), any());
  }

  @Test
  void runInvokesAssignerWhenReady() {
    StorageManager storageManager = mock(StorageManager.class);
    ConfigManager configManager = mock(ConfigManager.class);
    TicketAssigner assigner = mock(TicketAssigner.class);
    StorageProvider provider = mock(StorageProvider.class);

    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawn = mock(ConfigManager.SpawnSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawn);
    when(spawn.enabled()).thenReturn(true);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    SpawnMonitor monitor = new SpawnMonitor(storageManager, configManager, assigner);
    monitor.run();

    verify(assigner).tick(any(), any());
  }

  @Test
  void runCatchesRuntimeExceptionFromAssigner() {
    StorageManager storageManager = mock(StorageManager.class);
    ConfigManager configManager = mock(ConfigManager.class);
    TicketAssigner assigner = mock(TicketAssigner.class);
    StorageProvider provider = mock(StorageProvider.class);

    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawn = mock(ConfigManager.SpawnSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawn);
    when(spawn.enabled()).thenReturn(true);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
        .when(assigner)
        .tick(any(), any());

    SpawnMonitor monitor = new SpawnMonitor(storageManager, configManager, assigner);
    assertDoesNotThrow(monitor::run);
    verify(assigner).tick(any(), any());
  }
}

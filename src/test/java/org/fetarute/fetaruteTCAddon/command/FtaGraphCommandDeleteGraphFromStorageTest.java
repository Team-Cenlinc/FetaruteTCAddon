package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.World;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailGraphSnapshotRecord;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.api.TransactionCallback;
import org.junit.jupiter.api.Test;

final class FtaGraphCommandDeleteGraphFromStorageTest {

  @Test
  void deleteDoesNotRemoveRailNodesAndOverridesByDefault() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);

    when(snapshotRepo.findByWorld(worldId))
        .thenReturn(Optional.of(new RailGraphSnapshotRecord(worldId, Instant.now(), 0, 0, "sig")));

    StorageTransactionManager txManager = new InlineTransactionManager();
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(txManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getRailGraphService())
        .thenReturn(new RailGraphService(w -> SimpleRailGraph.empty()));
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

    FtaGraphCommand command = new FtaGraphCommand(plugin);

    boolean existed = invokeDeleteGraphFromStorage(command, world, false);
    assertTrue(existed);

    verify(edgeRepo, times(1)).deleteWorld(worldId);
    verify(snapshotRepo, times(1)).delete(worldId);
    verify(nodeRepo, times(0)).deleteWorld(worldId);
    verify(overrideRepo, times(0)).deleteWorld(worldId);
  }

  @Test
  void deleteHardRemovesRailNodesAndOverrides() throws Exception {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");

    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);

    RailNodeRepository nodeRepo = mock(RailNodeRepository.class);
    RailEdgeRepository edgeRepo = mock(RailEdgeRepository.class);
    RailEdgeOverrideRepository overrideRepo = mock(RailEdgeOverrideRepository.class);
    RailGraphSnapshotRepository snapshotRepo = mock(RailGraphSnapshotRepository.class);

    when(snapshotRepo.findByWorld(worldId)).thenReturn(Optional.empty());

    StorageTransactionManager txManager = new InlineTransactionManager();
    StorageProvider provider = mock(StorageProvider.class);
    when(provider.railNodes()).thenReturn(nodeRepo);
    when(provider.railEdges()).thenReturn(edgeRepo);
    when(provider.railEdgeOverrides()).thenReturn(overrideRepo);
    when(provider.railGraphSnapshots()).thenReturn(snapshotRepo);
    when(provider.transactionManager()).thenReturn(txManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(plugin.getRailGraphService())
        .thenReturn(new RailGraphService(w -> SimpleRailGraph.empty()));
    when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

    FtaGraphCommand command = new FtaGraphCommand(plugin);

    boolean existed = invokeDeleteGraphFromStorage(command, world, true);
    assertFalse(existed);

    verify(edgeRepo, times(1)).deleteWorld(worldId);
    verify(snapshotRepo, times(1)).delete(worldId);
    verify(nodeRepo, times(1)).deleteWorld(worldId);
    verify(overrideRepo, times(1)).deleteWorld(worldId);
  }

  private static boolean invokeDeleteGraphFromStorage(
      FtaGraphCommand command, World world, boolean hard) throws Exception {
    Method method =
        FtaGraphCommand.class.getDeclaredMethod(
            "deleteGraphFromStorage", World.class, boolean.class);
    assertTrue(method.trySetAccessible());
    return (boolean) method.invoke(command, world, hard);
  }

  private static final class InlineTransactionManager implements StorageTransactionManager {

    @Override
    public org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction begin()
        throws StorageException {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public <T> T execute(TransactionCallback<T> callback) throws StorageException {
      try {
        return callback.doInTransaction();
      } catch (StorageException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new StorageException("事务执行失败", ex);
      }
    }
  }
}

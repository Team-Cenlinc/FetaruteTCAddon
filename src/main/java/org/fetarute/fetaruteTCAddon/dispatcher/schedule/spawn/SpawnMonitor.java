package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Instant;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/** 自动发车 tick：生成 ticket 并尝试出车。 */
public final class SpawnMonitor implements Runnable {

  private static final Logger LOGGER = Logger.getLogger("FetaruteTCAddon");

  private final StorageManager storageManager;
  private final ConfigManager configManager;
  private final TicketAssigner ticketAssigner;

  public SpawnMonitor(
      StorageManager storageManager, ConfigManager configManager, TicketAssigner ticketAssigner) {
    this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    this.ticketAssigner = Objects.requireNonNull(ticketAssigner, "ticketAssigner");
  }

  @Override
  public void run() {
    ConfigManager.ConfigView view = configManager.current();
    if (view == null || view.spawnSettings() == null || !view.spawnSettings().enabled()) {
      return;
    }
    if (!storageManager.isReady()) {
      return;
    }
    StorageProvider provider = storageManager.provider().orElse(null);
    if (provider == null) {
      return;
    }
    try {
      ticketAssigner.tick(provider, Instant.now());
    } catch (RuntimeException ex) {
      LOGGER.log(Level.SEVERE, "SpawnMonitor tick 执行失败，本轮已跳过", ex);
    }
  }
}

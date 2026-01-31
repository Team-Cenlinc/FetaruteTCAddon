package org.fetarute.fetaruteTCAddon.command;

import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 命令层存储访问工具：统一 StorageProvider 的 ready 判定与错误提示。
 *
 * <p>避免每个 Command 重复写一段 {@code plugin.getStorageManager().isReady()} 的样板代码。
 */
public final class CommandStorageProviders {

  private CommandStorageProviders() {}

  /**
   * 仅在 StorageManager ready 的情况下返回 provider。
   *
   * <p>用于命令执行与补全：未 ready 时返回 empty，调用方可自行决定是否输出提示文案。
   */
  public static Optional<StorageProvider> providerIfReady(FetaruteTCAddon plugin) {
    if (plugin == null
        || plugin.getStorageManager() == null
        || !plugin.getStorageManager().isReady()) {
      return Optional.empty();
    }
    return plugin.getStorageManager().provider();
  }

  /**
   * 获取 ready 的 StorageProvider；未就绪时向用户输出统一错误文案。
   *
   * <p>默认使用语言键 {@code error.storage-unavailable}。
   */
  public static Optional<StorageProvider> readyProvider(
      CommandSender sender, FetaruteTCAddon plugin) {
    Optional<StorageProvider> providerOpt = providerIfReady(plugin);
    if (providerOpt.isEmpty() && sender != null && plugin != null) {
      sender.sendMessage(plugin.getLocaleManager().component("error.storage-unavailable"));
    }
    return providerOpt;
  }
}

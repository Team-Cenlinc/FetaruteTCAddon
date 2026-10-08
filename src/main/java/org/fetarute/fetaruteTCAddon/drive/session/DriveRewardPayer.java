package org.fetarute.fetaruteTCAddon.drive.session;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewardConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;

/**
 * 发驾驶奖励：经验直接给在线的玩家；钱币装了 Vault 时走经济接口（EssentialsX 等会挂在上面），没有 Vault 或经济接口发不出时按配置的控制台命令发。只在服务器主线程使用。
 */
final class DriveRewardPayer {

  private final Consumer<String> warn;

  DriveRewardPayer(Consumer<String> warn) {
    this.warn = warn == null ? message -> {} : warn;
  }

  /**
   * 实际发出去的奖励。
   *
   * @param experience 发出的经验；玩家不在线时为 0
   * @param money 发出的钱币，按经济插件的格式写好；没发钱币时为空
   */
  record Paid(int experience, Optional<String> money) {
    boolean empty() {
      return experience <= 0 && money.isEmpty();
    }
  }

  Paid pay(UUID playerId, String playerName, DriveRewards.Reward reward, DriveRewardConfig config) {
    if (reward == null || reward.empty()) {
      return new Paid(0, Optional.empty());
    }
    int experience = 0;
    Player player = Bukkit.getPlayer(playerId);
    if (reward.experience() > 0 && player != null && player.isOnline()) {
      player.giveExp(reward.experience());
      experience = reward.experience();
    }
    Optional<String> money =
        reward.money() > 0.0
            ? payMoney(playerId, playerName, reward.money(), config.moneyCommand())
            : Optional.empty();
    return new Paid(experience, money);
  }

  private Optional<String> payMoney(
      UUID playerId, String playerName, double amount, String command) {
    if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
      try {
        Optional<String> paid = VaultMoney.deposit(Bukkit.getOfflinePlayer(playerId), amount);
        if (paid.isPresent()) {
          return paid;
        }
      } catch (RuntimeException | LinkageError ex) {
        warn.accept("经 Vault 发驾驶奖励失败，改用配置的命令: " + ex);
      }
    }
    String line = moneyCommandLine(command, playerName, amount);
    if (line.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
          ? Optional.of(DriveRewards.formatAmount(amount))
          : Optional.empty();
    } catch (RuntimeException ex) {
      warn.accept("驾驶奖励命令执行失败: " + line + " (" + ex + ")");
      return Optional.empty();
    }
  }

  /** 填好玩家名与数额的控制台命令（去掉开头的 /）；命令或玩家名为空时为空串。 */
  static String moneyCommandLine(String command, String playerName, double amount) {
    if (command == null || command.isBlank() || playerName == null || playerName.isBlank()) {
      return "";
    }
    String line = command.trim();
    while (line.startsWith("/")) {
      line = line.substring(1);
    }
    return line.replace("{player}", playerName)
        .replace("{amount}", DriveRewards.formatAmount(amount));
  }
}

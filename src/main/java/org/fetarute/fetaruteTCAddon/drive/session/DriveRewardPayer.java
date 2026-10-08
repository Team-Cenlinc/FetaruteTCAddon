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
  private final Hooks hooks;

  DriveRewardPayer(Consumer<String> warn) {
    this(warn, BUKKIT);
  }

  DriveRewardPayer(Consumer<String> warn, Hooks hooks) {
    this.warn = warn == null ? message -> {} : warn;
    this.hooks = hooks;
  }

  /** 发奖励用到的服务器操作；用例里换成假的。 */
  interface Hooks {
    /** 给在线玩家经验；玩家不在线时返回 false。 */
    boolean giveExperience(UUID playerId, int amount);

    /** Vault 是否已启用。 */
    boolean vaultEnabled();

    /** 经 Vault 存入钱币，是否成功。 */
    boolean vaultDeposit(UUID playerId, double amount);

    /** 以控制台身份执行命令，是否找到并执行了。 */
    boolean dispatch(String commandLine);
  }

  private static final Hooks BUKKIT =
      new Hooks() {
        @Override
        public boolean giveExperience(UUID playerId, int amount) {
          Player player = Bukkit.getPlayer(playerId);
          if (player == null || !player.isOnline()) {
            return false;
          }
          player.giveExp(amount);
          return true;
        }

        @Override
        public boolean vaultEnabled() {
          return Bukkit.getPluginManager().isPluginEnabled("Vault");
        }

        @Override
        public boolean vaultDeposit(UUID playerId, double amount) {
          return VaultMoney.deposit(Bukkit.getOfflinePlayer(playerId), amount);
        }

        @Override
        public boolean dispatch(String commandLine) {
          return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine);
        }
      };

  /**
   * 实际发出去的奖励。
   *
   * @param experience 发出的经验；玩家不在线时为 0
   * @param money 发出的钱币数额（两位小数、去掉末尾的 0）；没发钱币时为空
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
    int experience =
        reward.experience() > 0 && hooks.giveExperience(playerId, reward.experience())
            ? reward.experience()
            : 0;
    Optional<String> money =
        reward.money() > 0.0
            ? payMoney(playerId, playerName, reward.money(), config.moneyCommand())
            : Optional.empty();
    return new Paid(experience, money);
  }

  private Optional<String> payMoney(
      UUID playerId, String playerName, double amount, String command) {
    if (hooks.vaultEnabled()) {
      try {
        if (hooks.vaultDeposit(playerId, amount)) {
          return Optional.of(DriveRewards.formatAmount(amount));
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
      return hooks.dispatch(line)
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

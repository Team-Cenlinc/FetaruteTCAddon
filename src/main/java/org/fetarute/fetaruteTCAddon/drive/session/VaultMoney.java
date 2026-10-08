package org.fetarute.fetaruteTCAddon.drive.session;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** 经 Vault 经济接口发钱币。只在 Vault 已启用时才会被用到（本类引用 Vault 的类，没装 Vault 时不能加载）。 */
final class VaultMoney {

  private VaultMoney() {}

  /**
   * 给玩家存入钱币。
   *
   * @return 是否存入成功；没有经济插件挂在 Vault 上时为 false
   */
  static boolean deposit(OfflinePlayer player, double amount) {
    RegisteredServiceProvider<Economy> registration =
        Bukkit.getServicesManager().getRegistration(Economy.class);
    if (registration == null) {
      return false;
    }
    EconomyResponse response = registration.getProvider().depositPlayer(player, amount);
    return response != null && response.transactionSuccess();
  }
}

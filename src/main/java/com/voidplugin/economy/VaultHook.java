package com.voidplugin.economy;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class VaultHook {
    private VaultHook() {
    }

    static void register(VoidEconomyPlugin plugin, EconomyService economy) {
        plugin.getServer().getServicesManager().register(
                net.milkbowl.vault.economy.Economy.class,
                new Provider(plugin, economy),
                plugin,
                ServicePriority.Highest
        );
        plugin.getLogger().info("Registered VoidEconomy as Vault's economy provider.");
    }

    private static final class Provider extends AbstractEconomy {
        private final VoidEconomyPlugin plugin;
        private final EconomyService economy;

        private Provider(VoidEconomyPlugin plugin, EconomyService economy) {
            this.plugin = plugin;
            this.economy = economy;
        }

        @Override public boolean isEnabled() { return plugin.isEnabled(); }
        @Override public String getName() { return "VoidEconomy"; }
        @Override public boolean hasBankSupport() { return false; }
        @Override public int fractionalDigits() { return 2; }
        @Override public String format(double amount) { return economy.format(money(amount)); }
        @Override public String currencyNamePlural() {
            return plugin.getConfig().getString("currency.name-plural", "dollars");
        }
        @Override public String currencyNameSingular() {
            return plugin.getConfig().getString("currency.name-singular", "dollar");
        }

        @Override public boolean hasAccount(String playerName) { return find(playerName) != null; }
        @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }

        @Override public double getBalance(String playerName) {
            UUID id = find(playerName);
            return id == null ? 0D : economy.getBalance(id).doubleValue();
        }
        @Override public double getBalance(String playerName, String worldName) { return getBalance(playerName); }

        @Override public boolean has(String playerName, double amount) {
            return valid(amount) && getBalance(playerName) >= amount;
        }
        @Override public boolean has(String playerName, String worldName, double amount) {
            return has(playerName, amount);
        }

        @Override public EconomyResponse withdrawPlayer(String playerName, double amount) {
            UUID id = find(playerName);
            if (id == null) return failure(0D, "Unknown economy account.");
            if (!valid(amount)) return failure(getBalance(playerName), "Amount must be finite and non-negative.");
            BigDecimal value = money(amount);
            if (value.signum() == 0) return success(0D, economy.getBalance(id), "No funds withdrawn.");
            if (!economy.withdraw(id, value)) return failure(getBalance(playerName), "Insufficient funds.");
            economy.record("VAULT_WITHDRAWAL", id, economy.accountName(id), id, economy.accountName(id), value);
            return success(value.doubleValue(), economy.getBalance(id), "Funds withdrawn.");
        }
        @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            return withdrawPlayer(playerName, amount);
        }

        @Override public EconomyResponse depositPlayer(String playerName, double amount) {
            UUID id = find(playerName);
            if (id == null) return failure(0D, "Unknown economy account.");
            if (!valid(amount)) return failure(getBalance(playerName), "Amount must be finite and non-negative.");
            BigDecimal value = money(amount);
            economy.deposit(id, value);
            if (value.signum() > 0) {
                economy.record("VAULT_DEPOSIT", id, economy.accountName(id), id, economy.accountName(id), value);
            }
            return success(value.doubleValue(), economy.getBalance(id), "Funds deposited.");
        }
        @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            return depositPlayer(playerName, amount);
        }

        @Override public boolean createPlayerAccount(String playerName) {
            if (playerName == null || playerName.isBlank()) return false;
            UUID existing = find(playerName);
            if (existing != null) return false;
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            economy.rememberName(player.getUniqueId(), playerName);
            economy.getBalance(player.getUniqueId());
            return true;
        }
        @Override public boolean createPlayerAccount(String playerName, String worldName) {
            return createPlayerAccount(playerName);
        }

        @Override public EconomyResponse createBank(String name, String player) { return unsupported(); }
        @Override public EconomyResponse deleteBank(String name) { return unsupported(); }
        @Override public EconomyResponse bankBalance(String name) { return unsupported(); }
        @Override public EconomyResponse bankHas(String name, double amount) { return unsupported(); }
        @Override public EconomyResponse bankWithdraw(String name, double amount) { return unsupported(); }
        @Override public EconomyResponse bankDeposit(String name, double amount) { return unsupported(); }
        @Override public EconomyResponse isBankOwner(String name, String playerName) { return unsupported(); }
        @Override public EconomyResponse isBankMember(String name, String playerName) { return unsupported(); }
        @Override public List<String> getBanks() { return Collections.emptyList(); }

        private UUID find(String playerName) {
            UUID known = economy.accountId(playerName);
            if (known != null) return known;
            Player online = Bukkit.getPlayerExact(playerName);
            if (online == null) return null;
            economy.rememberName(online.getUniqueId(), online.getName());
            economy.getBalance(online.getUniqueId());
            return online.getUniqueId();
        }

        private boolean valid(double amount) {
            return Double.isFinite(amount) && amount >= 0D;
        }

        private BigDecimal money(double amount) {
            return BigDecimal.valueOf(amount);
        }

        private EconomyResponse success(double amount, BigDecimal balance, String message) {
            return new EconomyResponse(amount, balance.doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, message);
        }

        private EconomyResponse failure(double balance, String message) {
            return new EconomyResponse(0D, balance, EconomyResponse.ResponseType.FAILURE, message);
        }

        private EconomyResponse unsupported() {
            return new EconomyResponse(0D, 0D, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                    "VoidEconomy does not support bank accounts.");
        }
    }
}

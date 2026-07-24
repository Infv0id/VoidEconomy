package com.voidplugin.economy;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EconomyService {
    private final VoidEconomyPlugin plugin;
    private final File file;
    private final Map<UUID, BigDecimal> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> privateAccounts = new HashSet<>();
    private final Set<UUID> saleNotificationsDisabled = new HashSet<>();
    private final Map<UUID, List<String>> pendingNotifications = new HashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();

    EconomyService(VoidEconomyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
    }

    public BigDecimal getBalance(UUID id) { return balances.computeIfAbsent(id, ignored -> startingBalance()); }
    public void setBalance(UUID id, BigDecimal amount) { balances.put(id, normalize(amount.max(BigDecimal.ZERO))); }
    public void deposit(UUID id, BigDecimal amount) { setBalance(id, getBalance(id).add(amount)); }
    public boolean withdraw(UUID id, BigDecimal amount) {
        BigDecimal value = normalize(amount);
        if (value.signum() <= 0 || getBalance(id).compareTo(value) < 0) return false;
        setBalance(id, getBalance(id).subtract(value));
        return true;
    }

    public boolean transfer(UUID from, UUID to, BigDecimal amount) {
        if (!withdraw(from, amount)) return false;
        deposit(to, amount);
        record("PAYMENT", from, accountName(from), to, accountName(to), amount);
        return true;
    }

    public void reset(UUID id) { balances.put(id, startingBalance()); }
    public void rememberName(UUID id, String name) { if (name != null && !name.isBlank()) names.put(id, name); }
    public String accountName(UUID id) { return names.getOrDefault(id, id.toString()); }
    public boolean isPrivate(UUID id) { return privateAccounts.contains(id); }
    public boolean togglePrivacy(UUID id) {
        if (!privateAccounts.add(id)) { privateAccounts.remove(id); return false; }
        return true;
    }

    public boolean saleNotificationsEnabled(UUID id) { return !saleNotificationsDisabled.contains(id); }
    public boolean toggleSaleNotifications(UUID id) {
        if (!saleNotificationsDisabled.add(id)) { saleNotificationsDisabled.remove(id); return true; }
        return false;
    }
    public void notifySale(UUID id, String message) {
        if (!saleNotificationsEnabled(id)) return;
        org.bukkit.entity.Player player = Bukkit.getPlayer(id);
        if (player != null) player.sendMessage(Text.color(message));
        else pendingNotifications.computeIfAbsent(id, ignored -> new ArrayList<>()).add(message);
    }
    public void deliverPendingNotifications(UUID id) {
        org.bukkit.entity.Player player = Bukkit.getPlayer(id);
        if (player == null || !saleNotificationsEnabled(id)) return;
        List<String> messages = pendingNotifications.remove(id);
        if (messages != null) messages.forEach(message -> player.sendMessage(Text.color(message)));
    }

    public List<Account> leaderboard(int limit) {
        return balances.entrySet().stream()
                .filter(entry -> !privateAccounts.contains(entry.getKey()))
                .filter(entry -> names.containsKey(entry.getKey()))
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> new Account(entry.getKey(), names.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    public void record(String type, UUID actorId, String actorName, UUID targetId, String targetName, BigDecimal amount) {
        rememberName(actorId, actorName);
        rememberName(targetId, targetName);
        transactions.add(new Transaction(Instant.now().toEpochMilli(), type, actorId, actorName,
                targetId, targetName, normalize(amount)));
        int limit = Math.max(20, plugin.getConfig().getInt("transaction-history-limit", 500));
        while (transactions.size() > limit) transactions.remove(0);
    }

    public List<Transaction> transactionsFor(UUID id, int limit) {
        return transactions.stream()
                .filter(transaction -> id.equals(transaction.actorId()) || id.equals(transaction.targetId()))
                .sorted(Comparator.comparingLong(Transaction::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    public String format(BigDecimal amount) {
        String symbol = plugin.getConfig().getString("currency.symbol", "$");
        String number = normalize(amount).toPlainString();
        return plugin.getConfig().getBoolean("currency.symbol-before-amount", true) ? symbol + number : number + symbol;
    }

    public int accountCount() { return balances.size(); }
    public BigDecimal totalAccountBalances() {
        return balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.DOWN);
    }
    public BigDecimal outstandingBankNotes() {
        BigDecimal withdrawn=transactions.stream().filter(t->t.type().equals("BANK_NOTE_WITHDRAWAL")).map(Transaction::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal deposited=transactions.stream().filter(t->t.type().equals("BANK_NOTE_DEPOSIT")).map(Transaction::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        return withdrawn.subtract(deposited).max(BigDecimal.ZERO).setScale(2,RoundingMode.DOWN);
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection balanceSection = data.getConfigurationSection("balances");
        if (balanceSection != null) for (String key : balanceSection.getKeys(false)) try {
            balances.put(UUID.fromString(key), normalize(new BigDecimal(balanceSection.getString(key, "0"))));
        } catch (IllegalArgumentException exception) { plugin.getLogger().warning("Skipped invalid account: " + key); }

        ConfigurationSection nameSection = data.getConfigurationSection("names");
        if (nameSection != null) for (String key : nameSection.getKeys(false)) try {
            names.put(UUID.fromString(key), nameSection.getString(key, key));
        } catch (IllegalArgumentException ignored) { }

        for (String value : data.getStringList("private-accounts")) try {
            privateAccounts.add(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) { }
        for (String value : data.getStringList("sale-notifications-disabled")) try {
            saleNotificationsDisabled.add(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) { }
        ConfigurationSection pending = data.getConfigurationSection("pending-notifications");
        if (pending != null) for (String key : pending.getKeys(false)) try {
            pendingNotifications.put(UUID.fromString(key), new ArrayList<>(pending.getStringList(key)));
        } catch (IllegalArgumentException ignored) { }

        ConfigurationSection history = data.getConfigurationSection("transactions");
        if (history != null) for (String key : history.getKeys(false)) try {
            ConfigurationSection row = history.getConfigurationSection(key);
            if (row == null) continue;
            transactions.add(new Transaction(row.getLong("time"), row.getString("type", "UNKNOWN"),
                    UUID.fromString(row.getString("actor-id", "")), row.getString("actor-name", "Unknown"),
                    UUID.fromString(row.getString("target-id", "")), row.getString("target-name", "Unknown"),
                    normalize(new BigDecimal(row.getString("amount", "0")))));
        } catch (IllegalArgumentException ignored) { }
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        balances.forEach((id, balance) -> data.set("balances." + id, balance.toPlainString()));
        names.forEach((id, name) -> data.set("names." + id, name));
        data.set("private-accounts", privateAccounts.stream().map(UUID::toString).toList());
        data.set("sale-notifications-disabled", saleNotificationsDisabled.stream().map(UUID::toString).toList());
        pendingNotifications.forEach((id, messages) -> data.set("pending-notifications." + id, messages));
        for (int index = 0; index < transactions.size(); index++) {
            Transaction transaction = transactions.get(index);
            String path = "transactions." + index + ".";
            data.set(path + "time", transaction.timestamp());
            data.set(path + "type", transaction.type());
            data.set(path + "actor-id", transaction.actorId().toString());
            data.set(path + "actor-name", transaction.actorName());
            data.set(path + "target-id", transaction.targetId().toString());
            data.set(path + "target-name", transaction.targetName());
            data.set(path + "amount", transaction.amount().toPlainString());
        }
        try { data.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Could not save balances: " + exception.getMessage()); }
    }

    private BigDecimal startingBalance() {
        return normalize(BigDecimal.valueOf(plugin.getConfig().getDouble("starting-balance", 100.0)));
    }
    private BigDecimal normalize(BigDecimal amount) { return amount.setScale(2, RoundingMode.DOWN); }

    public record Account(UUID id, String name, BigDecimal balance) { }
    public record Transaction(long timestamp, String type, UUID actorId, String actorName,
                              UUID targetId, String targetName, BigDecimal amount) { }
}

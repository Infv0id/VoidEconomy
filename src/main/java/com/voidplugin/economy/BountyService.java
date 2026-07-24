package com.voidplugin.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BountyService {
    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final File file;
    private final Map<UUID, Bounty> bounties = new HashMap<>();

    BountyService(VoidEconomyPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
    }

    synchronized BigDecimal amount(UUID targetId) {
        Bounty bounty = bounties.get(targetId);
        return bounty == null ? BigDecimal.ZERO.setScale(2) : bounty.amount();
    }

    synchronized PlacementResult place(UUID placerId, String placerName, UUID targetId,
                                       String targetName, BigDecimal amount) {
        BigDecimal value = normalize(amount);
        if (!economy.withdraw(placerId, value)) return PlacementResult.INSUFFICIENT_FUNDS;
        Bounty existing = bounties.get(targetId);
        BigDecimal total = existing == null ? value : existing.amount().add(value);
        bounties.put(targetId, new Bounty(targetId, targetName, normalize(total)));
        economy.rememberName(placerId, placerName);
        economy.rememberName(targetId, targetName);
        economy.record("BOUNTY_PLACED", placerId, placerName, targetId, targetName, value);
        economy.save();
        save();
        return PlacementResult.SUCCESS;
    }

    synchronized BigDecimal claim(UUID targetId, UUID killerId, String killerName) {
        if (targetId.equals(killerId)) return BigDecimal.ZERO;
        Bounty bounty = bounties.remove(targetId);
        if (bounty == null || bounty.amount().signum() <= 0) return BigDecimal.ZERO;
        save();
        economy.deposit(killerId, bounty.amount());
        economy.rememberName(killerId, killerName);
        economy.record("BOUNTY_CLAIMED", targetId, bounty.targetName(), killerId, killerName, bounty.amount());
        economy.save();
        return bounty.amount();
    }

    synchronized List<Bounty> top(int limit) {
        return bounties.values().stream()
                .filter(bounty -> bounty.amount().signum() > 0)
                .sorted(Comparator.comparing(Bounty::amount).reversed())
                .limit(limit)
                .toList();
    }

    synchronized void load() {
        bounties.clear();
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = data.getConfigurationSection("bounties");
        if (root == null) return;
        for (String key : root.getKeys(false)) loadRow(root, key);
    }

    synchronized void loadPlayer(UUID playerId) {
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = data.getConfigurationSection("bounties");
        if (root != null && root.contains(playerId.toString())) loadRow(root, playerId.toString());
    }

    private void loadRow(ConfigurationSection root, String key) {
        try {
            UUID id = UUID.fromString(key);
            ConfigurationSection row = root.getConfigurationSection(key);
            if (row == null) return;
            bounties.put(id, new Bounty(id, row.getString("name", "Unknown"),
                    normalize(new BigDecimal(row.getString("amount", "0")))));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Skipped invalid bounty entry " + key);
        }
    }

    synchronized void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Bounty bounty : bounties.values()) {
            String path = "bounties." + bounty.targetId() + ".";
            data.set(path + "name", bounty.targetName());
            data.set(path + "amount", bounty.amount().toPlainString());
        }
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save bounties.yml: " + exception.getMessage());
        }
    }

    private BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.DOWN);
    }

    enum PlacementResult { SUCCESS, INSUFFICIENT_FUNDS }
    record Bounty(UUID targetId, String targetName, BigDecimal amount) { }
}

package com.voidplugin.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.math.BigDecimal;
import java.util.*;

final class EconomyCommand implements CommandExecutor, TabCompleter {
    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    EconomyCommand(VoidEconomyPlugin plugin, EconomyService economy) { this.plugin = plugin; this.economy = economy; }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args); case "pay" -> pay(sender, args);
            case "eco" -> admin(sender, args); default -> false;
        };
    }

    private boolean balance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voideconomy.balance")) return fail(sender, "no-permission");
        OfflinePlayer target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) return fail(sender, "players-only"); target = player;
        } else {
            if (!sender.hasPermission("voideconomy.balance.others")) return fail(sender, "no-permission");
            target = find(args[0]); if (target == null) return fail(sender, "player-not-found");
        }
        sender.sendMessage(Text.color("&6" + name(target) + "&7 has &a" + economy.format(economy.getBalance(target.getUniqueId())) + "&7."));
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return fail(sender, "players-only");
        if (!sender.hasPermission("voideconomy.pay")) return fail(sender, "no-permission");
        if (args.length != 2) return false;
        OfflinePlayer target = find(args[0]); if (target == null) return fail(sender, "player-not-found");
        if (target.getUniqueId().equals(player.getUniqueId())) return fail(sender, "cannot-pay-self");
        BigDecimal amount = amount(args[1]); if (amount == null) return fail(sender, "invalid-amount");
        if (!economy.transfer(player.getUniqueId(), target.getUniqueId(), amount)) return fail(sender, "insufficient-funds");
        sender.sendMessage(Text.color("&aYou paid &6" + name(target) + " &a" + economy.format(amount) + "."));
        if (target.getPlayer() != null) target.getPlayer().sendMessage(Text.color("&aYou received " + economy.format(amount) + " from &6" + player.getName() + "&a."));
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voideconomy.admin")) return fail(sender, "no-permission");
        if (args.length < 2 || !List.of("give", "take", "set", "reset").contains(args[0].toLowerCase())) return false;
        String action = args[0].toLowerCase(); OfflinePlayer target = find(args[1]);
        if (target == null) return fail(sender, "player-not-found");
        BigDecimal recordedAmount;
        if (action.equals("reset")) {
            economy.reset(target.getUniqueId());
            recordedAmount = economy.getBalance(target.getUniqueId());
        }
        else {
            if (args.length != 3) return false;
            BigDecimal value = amount(args[2]); if (value == null) return fail(sender, "invalid-amount");
            recordedAmount = value;
            switch (action) {
                case "give" -> economy.deposit(target.getUniqueId(), value);
                case "take" -> economy.setBalance(target.getUniqueId(), economy.getBalance(target.getUniqueId()).subtract(value));
                case "set" -> economy.setBalance(target.getUniqueId(), value);
            }
        }
        UUID actorId = sender instanceof Player player ? player.getUniqueId() : new UUID(0L, 0L);
        economy.record("ADMIN_" + action.toUpperCase(Locale.ROOT), actorId, sender.getName(),
                target.getUniqueId(), name(target), recordedAmount);
        sender.sendMessage(Text.color("&aUpdated &6" + name(target) + "&a. New balance: " + economy.format(economy.getBalance(target.getUniqueId()))));
        return true;
    }

    private OfflinePlayer find(String value) {
        Player online = Bukkit.getPlayerExact(value); if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(value);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }
    private BigDecimal amount(String value) {
        try { BigDecimal result = new BigDecimal(value); return result.signum() > 0 && result.scale() <= 2 ? result : null; }
        catch (NumberFormatException exception) { return null; }
    }
    private String name(OfflinePlayer player) { return player.getName() == null ? player.getUniqueId().toString() : player.getName(); }
    private boolean fail(CommandSender sender, String key) { sender.sendMessage(plugin.message(key)); return true; }

    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        List<String> choices = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("eco") && args.length == 1) choices.addAll(List.of("give", "take", "set", "reset"));
        else if ((command.getName().equalsIgnoreCase("pay") && args.length == 1)
                || (command.getName().equalsIgnoreCase("balance") && args.length == 1)
                || (command.getName().equalsIgnoreCase("eco") && args.length == 2))
            Bukkit.getOnlinePlayers().forEach(player -> choices.add(player.getName()));
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        return choices.stream().filter(value -> value.toLowerCase().startsWith(prefix)).toList();
    }
}

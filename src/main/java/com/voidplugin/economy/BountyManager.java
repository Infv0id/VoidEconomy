package com.voidplugin.economy;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BountyManager implements Listener, CommandExecutor {
    private static final int BACK = 45;
    private static final int CLOSE = 53;

    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final BountyService bounties;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    BountyManager(VoidEconomyPlugin plugin, EconomyService economy, BountyService bounties) {
        this.plugin = plugin;
        this.economy = economy;
        this.bounties = bounties;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("players-only"));
            return true;
        }
        if (!player.hasPermission("voideconomy.bounty")) {
            player.sendMessage(plugin.message("no-permission"));
            return true;
        }
        openBountyHub(player);
        return true;
    }

    private void openBountyHub(Player player) {
        BountyHolder holder = new BountyHolder(View.HUB, null, null);
        Inventory menu = Bukkit.createInventory(holder, 54, Component.text("Bounty Board", NamedTextColor.DARK_RED));
        holder.inventory = menu;
        fill(menu);
        menu.setItem(20, button(Material.WRITABLE_BOOK, "Place a Bounty", NamedTextColor.RED,
                "Choose a player and bounty amount",
                "Minimum: " + economy.format(minimum())));
        menu.setItem(22, head(player.getUniqueId(), "Your Bounty", NamedTextColor.GOLD,
                economy.format(bounties.amount(player.getUniqueId()))));
        menu.setItem(24, button(Material.TARGET, "Top Bounties", NamedTextColor.YELLOW,
                "View the most wanted players"));
        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) menu.setItem(31, button(Material.CLOCK, "Placement Cooldown",
                NamedTextColor.GRAY, remaining + " seconds remaining"));
        menu.setItem(BACK, button(Material.ARROW, "Back to Hub", NamedTextColor.YELLOW));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    private void openTop(Player player) {
        BountyHolder holder = new BountyHolder(View.TOP, null, null);
        Inventory menu = Bukkit.createInventory(holder, 54, Component.text("Most Wanted Players", NamedTextColor.DARK_RED));
        holder.inventory = menu;
        List<BountyService.Bounty> top = bounties.top(45);
        holder.visible = new HashMap<>();
        for (int index = 0; index < top.size(); index++) {
            BountyService.Bounty bounty = top.get(index);
            menu.setItem(index, head(bounty.targetId(), "#" + (index + 1) + " " + bounty.targetName(),
                    NamedTextColor.RED, "Bounty: " + economy.format(bounty.amount()),
                    "Click to add to this bounty"));
            holder.visible.put(index, bounty.targetId());
        }
        if (top.isEmpty()) menu.setItem(22, button(Material.COBWEB, "No Active Bounties", NamedTextColor.GRAY));
        menu.setItem(BACK, button(Material.ARROW, "Back to Bounty Board", NamedTextColor.YELLOW));
        menu.setItem(49, button(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    private void openConfirmation(Player player, UUID targetId, String targetName, BigDecimal amount) {
        BigDecimal remaining = economy.getBalance(player.getUniqueId()).subtract(amount);
        if (remaining.signum() < 0) {
            player.sendMessage(text("You do not have enough money.", NamedTextColor.RED));
            openBountyHub(player);
            return;
        }
        BountyHolder holder = new BountyHolder(View.CONFIRM, targetId, targetName);
        holder.amount = amount;
        Inventory menu = Bukkit.createInventory(holder, 54, Component.text("Confirm Bounty", NamedTextColor.DARK_RED));
        holder.inventory = menu;
        fill(menu);
        menu.setItem(20, button(Material.LIME_CONCRETE, "Confirm Bounty", NamedTextColor.GREEN,
                "Place " + economy.format(amount) + " on " + targetName));
        menu.setItem(22, head(targetId, targetName, NamedTextColor.RED,
                "New bounty contribution: " + economy.format(amount),
                "Current bounty: " + economy.format(bounties.amount(targetId)),
                "New total bounty: " + economy.format(bounties.amount(targetId).add(amount)),
                "Remaining balance: " + economy.format(remaining)));
        menu.setItem(24, button(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        menu.setItem(BACK, button(Material.ARROW, "Back", NamedTextColor.YELLOW));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BountyHolder holder)) return;
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isShiftClick() || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!event.getClick().isLeftClick() && !event.getClick().isRightClick()) return;
        int slot = event.getRawSlot();
        if (slot == CLOSE) { player.closeInventory(); return; }
        switch (holder.view) {
            case HUB -> {
                if (slot == 20) beginPlacement(player, null);
                else if (slot == 24) openTop(player);
                else if (slot == BACK) player.performCommand("menu");
            }
            case TOP -> {
                UUID targetId = holder.visible.get(slot);
                if (targetId != null) {
                    BountyService.Bounty target = bounties.top(1000).stream()
                            .filter(bounty -> bounty.targetId().equals(targetId)).findFirst().orElse(null);
                    if (target != null) beginAmountPrompt(player, target.targetId(), target.targetName());
                } else if (slot == BACK) openBountyHub(player);
                else if (slot == 49) openTop(player);
            }
            case CONFIRM -> {
                if (slot == 20) completePlacement(player, holder);
                else if (slot == 24 || slot == BACK) openBountyHub(player);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof BountyHolder
                && event.getRawSlots().stream().anyMatch(slot -> slot < 54)) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    private void beginPlacement(Player player, OfflinePlayer preset) {
        if (!player.hasPermission("voideconomy.bounty.place")) {
            player.sendMessage(plugin.message("no-permission"));
            return;
        }
        if (preset != null) {
            beginAmountPrompt(player, preset.getUniqueId(), name(preset));
            return;
        }
        prompts.put(player.getUniqueId(), new Prompt(PromptType.TARGET, null, null));
        player.closeInventory();
        player.sendMessage(text("Type the username of the bounty target.", NamedTextColor.YELLOW));
        player.sendMessage(text("Your next chat message will not be public.", NamedTextColor.GRAY));
    }

    private void beginAmountPrompt(Player player, UUID targetId, String targetName) {
        if (!validTarget(player, Bukkit.getOfflinePlayer(targetId))) return;
        long remaining = bounties.amount(targetId).signum() > 0 ? 0 : cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(text("Wait " + remaining + " seconds before creating another bounty.", NamedTextColor.RED));
            openBountyHub(player);
            return;
        }
        prompts.put(player.getUniqueId(), new Prompt(PromptType.AMOUNT, targetId, targetName));
        player.closeInventory();
        player.sendMessage(text("Type the bounty amount. Minimum: " + economy.format(minimum()), NamedTextColor.YELLOW));
        player.sendMessage(text("Your next chat message will not be public.", NamedTextColor.GRAY));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> acceptPrompt(event.getPlayer(), prompt, input));
    }

    private void acceptPrompt(Player player, Prompt prompt, String input) {
        if (!player.isOnline()) return;
        if (prompt.type() == PromptType.TARGET) {
            OfflinePlayer target = findPlayer(input);
            if (target == null || !validTarget(player, target)) {
                if (target == null) player.sendMessage(text("Player not found.", NamedTextColor.RED));
                openBountyHub(player);
                return;
            }
            beginAmountPrompt(player, target.getUniqueId(), name(target));
            return;
        }
        BigDecimal amount = parseAmount(input);
        if (amount == null || amount.compareTo(minimum()) < 0) {
            player.sendMessage(text("The bounty must be at least " + economy.format(minimum()) + ".", NamedTextColor.RED));
            openBountyHub(player);
            return;
        }
        openConfirmation(player, prompt.targetId(), prompt.targetName(), amount);
    }

    private void completePlacement(Player player, BountyHolder holder) {
        boolean addingToExisting = bounties.amount(holder.targetId).signum() > 0;
        long remaining = addingToExisting ? 0 : cooldownRemaining(player.getUniqueId());
        if (remaining > 0 || !validTarget(player, Bukkit.getOfflinePlayer(holder.targetId))) {
            openBountyHub(player);
            return;
        }
        BountyService.PlacementResult result = bounties.place(player.getUniqueId(), player.getName(),
                holder.targetId, holder.targetName, holder.amount);
        if (result == BountyService.PlacementResult.INSUFFICIENT_FUNDS) {
            player.sendMessage(text("You do not have enough money.", NamedTextColor.RED));
        } else {
            if (!addingToExisting) cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            player.sendMessage(text("Added " + economy.format(holder.amount) + " to " + holder.targetName
                    + ". New bounty total: " + economy.format(bounties.amount(holder.targetId)) + ".", NamedTextColor.GREEN));
            Player target = Bukkit.getPlayer(holder.targetId);
            if (target != null) target.sendMessage(text(player.getName() + " placed a "
                    + economy.format(holder.amount) + " bounty on you.", NamedTextColor.RED));
        }
        if (result == BountyService.PlacementResult.SUCCESS) openTop(player);
        else openBountyHub(player);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        BigDecimal claimed = bounties.claim(victim.getUniqueId(), killer.getUniqueId(), killer.getName());
        if (claimed.signum() > 0) {
            killer.sendMessage(text("You claimed " + economy.format(claimed) + " for eliminating "
                    + victim.getName() + ".", NamedTextColor.GOLD));
            victim.sendMessage(text(killer.getName() + " claimed your " + economy.format(claimed)
                    + " bounty.", NamedTextColor.RED));
            Bukkit.getServer().sendMessage(text(killer.getName() + " claimed a " + economy.format(claimed)
                    + " bounty on " + victim.getName() + "!", NamedTextColor.GOLD));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bounties.loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bounties.save();
        prompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        bounties.save();
        prompts.remove(event.getPlayer().getUniqueId());
    }

    private boolean validTarget(Player placer, OfflinePlayer target) {
        if (target.getUniqueId().equals(placer.getUniqueId())) {
            placer.sendMessage(text("You cannot place a bounty on yourself.", NamedTextColor.RED));
            return false;
        }
        if (plugin.getConfig().getBoolean("bounty.protect-server-operators", true) && target.isOp()) {
            placer.sendMessage(text("Server operators are protected from bounties.", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private long cooldownRemaining(UUID playerId) {
        long last = cooldowns.getOrDefault(playerId, 0L);
        long cooldown = Math.max(0, plugin.getConfig().getLong("bounty.placement-cooldown-seconds", 60));
        return Math.max(0, cooldown - ((System.currentTimeMillis() - last) / 1000));
    }

    private BigDecimal minimum() {
        return BigDecimal.valueOf(plugin.getConfig().getDouble("bounty.minimum-amount", 100));
    }

    private BigDecimal parseAmount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value);
            return amount.signum() > 0 && amount.scale() <= 2 ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private OfflinePlayer findPlayer(String value) {
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(value);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }

    private String name(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private void fill(Inventory menu) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = 0; slot < menu.getSize(); slot++) menu.setItem(slot, filler);
    }

    private ItemStack head(UUID id, String title, NamedTextColor color, String... lore) {
        ItemStack item = button(Material.PLAYER_HEAD, title, color, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String title, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(title, color));
        if (lore.length > 0)
            meta.lore(java.util.Arrays.stream(lore).map(line -> text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private enum View { HUB, TOP, CONFIRM }
    private enum PromptType { TARGET, AMOUNT }
    private record Prompt(PromptType type, UUID targetId, String targetName) { }

    private static final class BountyHolder implements InventoryHolder {
        private final View view;
        private final UUID targetId;
        private final String targetName;
        private BigDecimal amount;
        private Inventory inventory;
        private Map<Integer, UUID> visible = Map.of();
        private BountyHolder(View view, UUID targetId, String targetName) {
            this.view = view;
            this.targetId = targetId;
            this.targetName = targetName;
        }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}

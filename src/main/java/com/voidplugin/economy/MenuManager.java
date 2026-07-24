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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MenuManager implements Listener, CommandExecutor {
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final Map<Integer, BigDecimal> PAYMENT_AMOUNTS = Map.of(
            20, new BigDecimal("10"), 21, new BigDecimal("50"), 22, new BigDecimal("100"),
            23, new BigDecimal("500"), 24, new BigDecimal("1000"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final BankNoteService bankNotes;
    private final AuctionService auctions;
    private final OrderService orders;
    private final SellService sell;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    MenuManager(VoidEconomyPlugin plugin, EconomyService economy, BankNoteService bankNotes,
                AuctionService auctions, OrderService orders, SellService sell) {
        this.plugin = plugin;
        this.economy = economy;
        this.bankNotes = bankNotes;
        this.auctions = auctions;
        this.orders = orders;
        this.sell = sell;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("players-only"));
            return true;
        }
        economy.rememberName(player.getUniqueId(), player.getName());
        switch (command.getName().toLowerCase()) {
            case "menu" -> {
                if (!player.hasPermission("voideconomy.menu")) return deny(player);
                openHub(player);
            }
            case "bank" -> {
                if (!player.hasPermission("voideconomy.bank")) return deny(player);
                openBank(player);
            }
            case "baltop" -> {
                if (!player.hasPermission("voideconomy.baltop")) return deny(player);
                openLeaderboard(player);
            }
            case "balprivacy" -> togglePrivacy(player);
            case "admin" -> {
                if (!player.hasPermission("voideconomy.admin")) return deny(player);
                handleAdminCommand(player, args);
            }
            case "ve" -> {
                if (args.length>0&&args[0].equalsIgnoreCase("admin")) {
                    if (!player.hasPermission("voideconomy.admin")) return deny(player);
                    handleAdminCommand(player, Arrays.copyOfRange(args,1,args.length));
                } else {
                    player.sendMessage(text("/sell, /bank, /auction, /orders, /bounty, /pay",NamedTextColor.GOLD));
                    player.sendMessage(text("Administrators: /ve admin stats or /ve admin sell",NamedTextColor.YELLOW));
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private void handleAdminCommand(Player admin, String[] args) {
        if (args.length == 0) { openAdminHome(admin); return; }
        if (args[0].equalsIgnoreCase("stats") || args[0].equalsIgnoreCase("statistics")) {
            if(!admin.hasPermission("voideconomy.admin.statistics")){deny(admin);return;}
            String period=args.length>1?args[1]:"all";openStatistics(admin,period);return;
        }
        if (args[0].equalsIgnoreCase("sell")) {
            if(!admin.hasPermission("voideconomy.admin.sell")){deny(admin);return;}
            if(args.length==1||args[1].equalsIgnoreCase("list")){admin.performCommand("sell prices");return;}
            String action=args[1].toLowerCase();
            if(action.equals("freezeall")||action.equals("unfreezeall")){sell.setFrozen(action.equals("freezeall"));admin.sendMessage(text("Sell market freeze state updated.",NamedTextColor.GREEN));return;}
            if(action.equals("resetall")){sell.markets().forEach(m->sell.reset(m.material));admin.sendMessage(text("All sell prices reset.",NamedTextColor.GREEN));return;}
            if(args.length<3){admin.sendMessage(text("/ve admin sell <action> <material> [value]",NamedTextColor.YELLOW));return;}
            Material material=Material.matchMaterial(args[2]);String value=args.length>3?args[3]:"";
            if(action.equals("inspect")&&material!=null){SellService.Market m=sell.market(material);admin.sendMessage(text(m==null?"Not sellable.":material+": current "+economy.format(m.current)+", base "+economy.format(m.base)+", minimum "+economy.format(m.minimum)+", sold "+m.totalSold, m==null?NamedTextColor.RED:NamedTextColor.GREEN));return;}
            boolean success=material!=null&&(action.equals("add")?sell.add(material):sell.configure(material,action.equals("remove")?"disable":action,value));
            admin.sendMessage(text(success?"Sell market updated.":"Invalid sell material, action, or value.",success?NamedTextColor.GREEN:NamedTextColor.RED));return;
        }
        if (args[0].equalsIgnoreCase("auction") || args[0].equalsIgnoreCase("auctions")) {
            openAdminAuctions(admin, 0); return;
        }
        if (args[0].equalsIgnoreCase("order") || args[0].equalsIgnoreCase("orders")) {
            openAdminOrders(admin, 0); return;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            admin.sendMessage(text("VoidEconomy configuration reloaded.", NamedTextColor.GREEN));
            openAdminHome(admin);
            return;
        }
        if (args.length < 2) {
            admin.sendMessage(text("Usage: /admin <inspect|give|take|set|reset> <player> [amount]", NamedTextColor.YELLOW));
            return;
        }
        OfflinePlayer target = findPlayer(args[1]);
        if (target == null) { admin.sendMessage(plugin.message("player-not-found")); return; }
        String targetName = playerName(target);
        if (args[0].equalsIgnoreCase("inspect")) { openAdminAccount(admin, target.getUniqueId(), targetName); return; }
        PromptType action = switch (args[0].toLowerCase()) {
            case "give" -> PromptType.ADMIN_GIVE;
            case "take" -> PromptType.ADMIN_TAKE;
            case "set" -> PromptType.ADMIN_SET;
            case "reset" -> PromptType.ADMIN_RESET;
            default -> null;
        };
        if (action == null) { admin.sendMessage(text("Unknown admin action.", NamedTextColor.RED)); return; }
        if (action == PromptType.ADMIN_RESET) {
            applyAdmin(admin, target.getUniqueId(), targetName, action, BigDecimal.ZERO); return;
        }
        if (args.length < 3) { prompt(admin, action, target.getUniqueId(), targetName); return; }
        BigDecimal amount = parseAmount(args[2], action == PromptType.ADMIN_SET);
        if (amount == null) { admin.sendMessage(plugin.message("invalid-amount")); return; }
        applyAdmin(admin, target.getUniqueId(), targetName, action, amount);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        economy.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        economy.deliverPendingNotifications(event.getPlayer().getUniqueId());
    }

    void openHub(Player player) {
        Inventory menu = create(MenuType.HUB, null, null, null, "Server Hub", NamedTextColor.DARK_GRAY, 54);
        fill(menu);
        menu.setItem(20, item(Material.GOLD_INGOT, "Bank", NamedTextColor.GOLD,
                "View your balance, payments, and history"));
        menu.setItem(21, item(Material.CHEST, "Auction House", NamedTextColor.AQUA,
                "Browse and sell player items"));
        menu.setItem(22, item(Material.WRITABLE_BOOK, "Buy Orders", NamedTextColor.GREEN,
                "Post requests or deliver materials for money"));
        menu.setItem(23,item(Material.EMERALD,"Server Sell Market",NamedTextColor.GREEN,
                "Sell configured resources at floating prices"));
        menu.setItem(24, item(Material.TARGET, "Bounties", NamedTextColor.RED,
                "Place bounties and view top targets"));
        menu.setItem(CLOSE_SLOT, closeItem());
        player.openInventory(menu);
    }

    void openBank(Player player) {
        Inventory menu = create(MenuType.BANK, null, null, null, "Bank", NamedTextColor.DARK_GREEN, 54);
        fill(menu);
        menu.setItem(19, item(Material.EMERALD, "Pay Player", NamedTextColor.GREEN,
                "Send money to another player"));
        menu.setItem(20, accountItem(player));
        menu.setItem(21, item(Material.BOOK, "Recent Transactions", NamedTextColor.AQUA,
                "View your latest account activity"));
        menu.setItem(22, item(Material.MAP, "Withdraw Bank Notes", NamedTextColor.GREEN,
                "Convert balance into physical currency",
                "All notes use whole-number values"));
        menu.setItem(23, item(Material.GOLDEN_HELMET, "Richest Players", NamedTextColor.GOLD,
                "Open the public balance leaderboard"));
        menu.setItem(24, privacyItem(player));
        boolean notifications = economy.saleNotificationsEnabled(player.getUniqueId());
        menu.setItem(25, item(notifications ? Material.BELL : Material.GRAY_DYE,
                "Marketplace Notifications: " + (notifications ? "ON" : "OFF"),
                notifications ? NamedTextColor.GREEN : NamedTextColor.RED,
                "Auction sales and fulfilled buy orders",
                "Click to toggle"));
        menu.setItem(BACK_SLOT, backItem("Back to Hub"));
        menu.setItem(CLOSE_SLOT, closeItem());
        player.openInventory(menu);
    }

    private void openAmountMenu(Player player, UUID targetId, String targetName) {
        Inventory menu = create(MenuType.PAY_AMOUNT, targetId, targetName, null,
                "Choose Payment Amount", NamedTextColor.DARK_GREEN, 54);
        fill(menu);
        PAYMENT_AMOUNTS.forEach((slot, amount) -> menu.setItem(slot,
                item(Material.GOLD_NUGGET, economy.format(amount), NamedTextColor.GOLD,
                        "Pay " + targetName, "A confirmation screen comes next")));
        menu.setItem(31, item(Material.NAME_TAG, "Custom Amount", NamedTextColor.AQUA,
                "Type any valid amount in chat"));
        menu.setItem(BACK_SLOT, backItem("Back to Bank"));
        menu.setItem(13, item(Material.PLAYER_HEAD, "Recipient: " + targetName, NamedTextColor.GREEN,
                "Choose an amount above"));
        menu.setItem(CLOSE_SLOT, closeItem());
        player.openInventory(menu);
    }

    private void openConfirmation(Player player, UUID targetId, String targetName, BigDecimal amount) {
        BigDecimal remaining = economy.getBalance(player.getUniqueId()).subtract(amount);
        if (remaining.signum() < 0) {
            player.sendMessage(text("You do not have enough money.", NamedTextColor.RED));
            openAmountMenu(player, targetId, targetName);
            return;
        }
        Inventory menu = create(MenuType.PAY_CONFIRM, targetId, targetName, amount,
                "Confirm Payment", NamedTextColor.DARK_GREEN, 54);
        fill(menu);
        menu.setItem(20, item(Material.LIME_CONCRETE, "Confirm", NamedTextColor.GREEN,
                "Pay " + targetName + " " + economy.format(amount)));
        menu.setItem(22, item(Material.PAPER, "Payment Summary", NamedTextColor.GOLD,
                "Recipient: " + targetName, "Amount: " + economy.format(amount),
                "Remaining: " + economy.format(remaining)));
        menu.setItem(24, item(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED,
                "Return without sending money"));
        menu.setItem(BACK_SLOT, backItem("Back to Amounts"));
        menu.setItem(CLOSE_SLOT, closeItem());
        player.openInventory(menu);
    }

    private void openHistory(Player viewer, UUID accountId, String accountName, boolean adminView) {
        Inventory menu = create(adminView ? MenuType.ADMIN_HISTORY : MenuType.HISTORY,
                accountId, accountName, null, "Recent Transactions", NamedTextColor.DARK_AQUA, 54);
        fill(menu);
        List<EconomyService.Transaction> history = economy.transactionsFor(accountId, 45);
        for (int index = 0; index < history.size(); index++) {
            EconomyService.Transaction transaction = history.get(index);
            boolean sent = accountId.equals(transaction.actorId());
            String title = switch (transaction.type()) {
                case "PAYMENT" -> sent ? "Paid " + transaction.targetName() : "Received from " + transaction.actorName();
                case "ADMIN_GIVE" -> "Admin deposit by " + transaction.actorName();
                case "ADMIN_TAKE" -> "Admin withdrawal by " + transaction.actorName();
                case "ADMIN_SET" -> "Balance set by " + transaction.actorName();
                case "ADMIN_RESET" -> "Balance reset by " + transaction.actorName();
                case "AUCTION_PURCHASE" -> sent
                        ? "Bought auction from " + transaction.targetName()
                        : "Auction sold to " + transaction.actorName();
                case "BOUNTY_PLACED" -> sent
                        ? "Bounty placed on " + transaction.targetName()
                        : "Bounty added by " + transaction.actorName();
                case "BOUNTY_CLAIMED" -> sent
                        ? "Bounty claimed by " + transaction.targetName()
                        : "Claimed bounty on " + transaction.actorName();
                case "BANK_NOTE_WITHDRAWAL" -> "Withdrew physical bank notes";
                case "BANK_NOTE_DEPOSIT" -> "Deposited physical bank notes";
                case "SELL_GENERATED" -> "Sold resources to the server market";
                default -> transaction.type();
            };
            NamedTextColor color = transaction.type().equals("PAYMENT") && sent ? NamedTextColor.RED : NamedTextColor.GREEN;
            menu.setItem(index, item(sent ? Material.RED_DYE : Material.LIME_DYE, title, color,
                    economy.format(transaction.amount()), DATE.format(Instant.ofEpochMilli(transaction.timestamp()))));
        }
        if (history.isEmpty()) menu.setItem(22, item(Material.PAPER, "No Transactions Yet", NamedTextColor.GRAY));
        menu.setItem(45, backItem(adminView ? "Back to Admin Account" : "Back to Bank"));
        menu.setItem(53, closeItem());
        viewer.openInventory(menu);
    }

    private void openLeaderboard(Player player) {
        Inventory menu = create(MenuType.LEADERBOARD, null, null, null,
                "Richest Players", NamedTextColor.GOLD, 54);
        fill(menu);
        List<EconomyService.Account> accounts = economy.leaderboard(45);
        for (int index = 0; index < accounts.size(); index++) {
            EconomyService.Account account = accounts.get(index);
            menu.setItem(index, head(account.id(), "#" + (index + 1) + " " + account.name(), NamedTextColor.GOLD,
                    "Balance: " + economy.format(account.balance())));
        }
        menu.setItem(45, backItem("Back to Hub"));
        menu.setItem(40, privacyItem(player));
        menu.setItem(53, closeItem());
        player.openInventory(menu);
    }

    private void openBankNoteWithdrawal(Player player) {
        Inventory menu = create(MenuType.BANK_NOTE_WITHDRAW, null, null, null,
                "Withdraw Bank Notes", NamedTextColor.DARK_GREEN, 54);
        fill(menu);
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        int[] values = {1, 5, 10, 50, 100, 500, 1000};
        for (int index = 0; index < values.length; index++) {
            int denomination = values[index];
            ItemStack note = bankNotes.createNote(denomination, 1);
            ItemMeta meta = note.getItemMeta();
            List<Component> lore = new java.util.ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            lore.add(text("Left-click: withdraw 1 note", NamedTextColor.YELLOW));
            lore.add(text("Right-click: withdraw 10 notes", NamedTextColor.AQUA));
            meta.lore(lore);
            note.setItemMeta(meta);
            menu.setItem(slots[index], note);
        }
        menu.setItem(31, item(Material.PLAYER_HEAD, "Available Balance", NamedTextColor.GOLD,
                economy.format(economy.getBalance(player.getUniqueId()))));
        menu.setItem(BACK_SLOT, backItem("Back to Bank"));
        menu.setItem(CLOSE_SLOT, closeItem());
        player.openInventory(menu);
    }

    private void openAdminAccount(Player admin, UUID targetId, String targetName) {
        Inventory menu = create(MenuType.ADMIN_ACCOUNT, targetId, targetName, null,
                "Admin: " + targetName, NamedTextColor.DARK_RED, 54);
        fill(menu);
        menu.setItem(20, item(Material.LIME_DYE, "Give Money", NamedTextColor.GREEN, "Enter an amount in chat"));
        menu.setItem(21, item(Material.RED_DYE, "Take Money", NamedTextColor.RED, "Enter an amount in chat"));
        menu.setItem(22, item(Material.PLAYER_HEAD, targetName, NamedTextColor.AQUA,
                "Balance: " + economy.format(economy.getBalance(targetId))));
        menu.setItem(23, item(Material.COMPARATOR, "Set Balance", NamedTextColor.YELLOW, "Enter the new balance in chat"));
        menu.setItem(24, item(Material.TNT, "Reset Balance", NamedTextColor.RED,
                "Reset to the configured starting balance"));
        menu.setItem(31, item(Material.WRITABLE_BOOK, "Transaction History", NamedTextColor.AQUA,
                "View this player's account activity"));
        menu.setItem(BACK_SLOT, backItem("Back to Hub"));
        menu.setItem(40, item(Material.SPYGLASS, "Search Another Player", NamedTextColor.YELLOW));
        menu.setItem(CLOSE_SLOT, closeItem());
        admin.openInventory(menu);
    }

    private void openAdminHome(Player admin) {
        Inventory menu = create(MenuType.ADMIN_HOME, null, null, null,
                "VoidEconomy Administration", NamedTextColor.DARK_RED, 54);
        fill(menu);
        menu.setItem(19, item(Material.SPYGLASS, "Manage Player", NamedTextColor.YELLOW,
                "Search balances, transactions, and account controls"));
        menu.setItem(20, item(Material.CHEST, "Auction Administration", NamedTextColor.AQUA,
                "View all listings and expire active auctions",
                "Command: /admin auctions"));
        menu.setItem(21, item(Material.WRITABLE_BOOK, "Order Administration", NamedTextColor.GREEN,
                "View all orders and cancel active orders",
                "Command: /admin orders"));
        menu.setItem(22, item(Material.TARGET, "Bounty Administration", NamedTextColor.RED,
                "Use /admin inspect <player>",
                "Bounty contributions and claims are recorded"));
        menu.setItem(23,item(Material.EMERALD,"Sell Market Administration",NamedTextColor.GREEN,
                "View floating prices","Command: /ve admin sell"));
        menu.setItem(24,item(Material.FILLED_MAP,"Economy Statistics",NamedTextColor.AQUA,
                "Supply, generation, destruction, and net change","Command: /ve admin stats"));
        menu.setItem(25, item(Material.COMPARATOR, "Reload Configuration", NamedTextColor.GOLD,
                "Reload item exclusions, prices, and limits"));
        menu.setItem(BACK_SLOT, backItem("Back to Hub"));
        menu.setItem(CLOSE_SLOT, closeItem());
        admin.openInventory(menu);
    }

    private void openStatistics(Player admin,String period){
        long now=System.currentTimeMillis();long since=switch(period.toLowerCase()){case "today"->LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();case "week"->now-7L*86400000L;default->0L;};
        SellService.Stats stats=sell.stats(since);BigDecimal accounts=economy.totalAccountBalances(),escrow=orders.activeEscrow(),notes=economy.outstandingBankNotes(),supply=accounts.add(escrow).add(notes);
        BigDecimal daily=sell.snapshotBefore(now-86400000L),weekly=sell.snapshotBefore(now-7L*86400000L);
        Inventory menu=create(MenuType.ADMIN_STATS,null,null,null,"Economy Statistics: "+period,NamedTextColor.DARK_AQUA,54);fill(menu);
        menu.setItem(20,item(Material.EMERALD,"Circulating Supply",NamedTextColor.GREEN,"Total: "+economy.format(supply),"Player balances: "+economy.format(accounts),"Buy Order escrow: "+economy.format(escrow),"Physical bank notes: "+economy.format(notes)));
        menu.setItem(22,item(Material.LIME_DYE,"Currency Generated",NamedTextColor.GREEN,economy.format(stats.generated()),"Created only through server sources such as /sell"));
        menu.setItem(24,item(Material.RED_DYE,"Currency Destroyed",NamedTextColor.RED,economy.format(stats.destroyed()),"Auction fees: "+economy.format(stats.auctionFees()),"Order fees: "+economy.format(stats.orderFees()),"Permanently removed; never stored or redistributed"));
        menu.setItem(31,item(stats.net().signum()>=0?Material.LIME_CONCRETE:Material.RED_CONCRETE,"Net Currency Change",stats.net().signum()>=0?NamedTextColor.GREEN:NamedTextColor.RED,(stats.net().signum()>=0?"+":"")+economy.format(stats.net())));
        menu.setItem(33,item(Material.SPYGLASS,"Currency Supply Growth",NamedTextColor.GOLD,
                "24 hours ago: "+economy.format(daily),"Daily growth: "+growth(supply,daily),
                "7 days ago: "+economy.format(weekly),"Weekly growth: "+growth(supply,weekly)));
        menu.setItem(38,item(Material.CLOCK,"Today",NamedTextColor.YELLOW));menu.setItem(40,item(Material.CLOCK,"Last 7 Days",NamedTextColor.YELLOW));menu.setItem(42,item(Material.CLOCK,"All Time",NamedTextColor.YELLOW));
        menu.setItem(BACK_SLOT,backItem("Back to Admin"));menu.setItem(CLOSE_SLOT,closeItem());admin.openInventory(menu);
    }
    private String growth(BigDecimal current,BigDecimal previous){if(previous.signum()<=0)return "Collecting snapshots";
        BigDecimal percent=current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous,2,java.math.RoundingMode.HALF_UP);
        double value=percent.doubleValue(),stable=plugin.getConfig().getDouble("statistics.growth-thresholds.stable-max",3),
                moderate=plugin.getConfig().getDouble("statistics.growth-thresholds.moderate-max",7),
                high=plugin.getConfig().getDouble("statistics.growth-thresholds.high-risk-max",12);
        String status=value<0?"Deflation":value<stable?"Stable":value<moderate?"Moderate Growth":value<high?"High Inflation Risk":"Severe Inflation Risk";
        return (value>=0?"+":"")+percent.toPlainString()+"% ("+status+")";}

    private void openAdminAuctions(Player admin, int requestedPage) {
        Inventory menu = create(MenuType.ADMIN_AUCTIONS, null, null, null,
                "Admin: Auction Listings", NamedTextColor.DARK_RED, 54);
        fill(menu);
        List<AuctionService.Listing> listings = auctions.allListings();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        int pages = Math.max(1, (listings.size() + slots.length - 1) / slots.length);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        ((MenuHolder) menu.getHolder(false)).page = page;
        for (int index = page * slots.length; index < Math.min((page + 1) * slots.length, listings.size()); index++) {
            AuctionService.Listing listing = listings.get(index);
            int displaySlot = slots[index - page * slots.length];
            ItemStack icon = listing.item().clone();
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new java.util.ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(Component.empty());
            lore.add(text("Seller: " + listing.sellerName(), NamedTextColor.GRAY));
            lore.add(text("Price: " + economy.format(listing.price()), NamedTextColor.GOLD));
            lore.add(text("Status: " + listing.status(), NamedTextColor.YELLOW));
            if (listing.status() == AuctionService.Status.ACTIVE)
                lore.add(text("Click to expire and return this listing", NamedTextColor.RED));
            meta.lore(lore);
            icon.setItemMeta(meta);
            menu.setItem(displaySlot, icon);
            ((MenuHolder) menu.getHolder(false)).ids.put(displaySlot, listing.id());
        }
        menu.setItem(38, item(Material.SPECTRAL_ARROW, "Previous Page", NamedTextColor.YELLOW));
        menu.setItem(40, item(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        menu.setItem(42, item(Material.SPECTRAL_ARROW, "Next Page", NamedTextColor.YELLOW));
        menu.setItem(BACK_SLOT, backItem("Back to Admin"));
        menu.setItem(CLOSE_SLOT, closeItem());
        admin.openInventory(menu);
    }

    private void openAdminOrders(Player admin, int requestedPage) {
        Inventory menu = create(MenuType.ADMIN_ORDERS, null, null, null,
                "Admin: Buy Orders", NamedTextColor.DARK_RED, 54);
        fill(menu);
        List<OrderService.BuyOrder> all = orders.allOrders();
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        int pages = Math.max(1, (all.size() + slots.length - 1) / slots.length);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        ((MenuHolder) menu.getHolder(false)).page = page;
        for (int index = page * slots.length; index < Math.min((page + 1) * slots.length, all.size()); index++) {
            OrderService.BuyOrder order = all.get(index);
            int displaySlot = slots[index - page * slots.length];
            menu.setItem(displaySlot, item(order.material(), order.material().name().replace('_', ' '),
                    order.status() == OrderService.Status.ACTIVE ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    "Buyer: " + order.buyerName(), "Progress: " + order.delivered() + "/" + order.quantity(),
                    "Escrow: " + economy.format(order.escrowRemaining()), "Status: " + order.status(),
                    order.status() == OrderService.Status.ACTIVE ? "Click to cancel and refund" : "No admin action available"));
            ((MenuHolder) menu.getHolder(false)).ids.put(displaySlot, order.id());
        }
        menu.setItem(38, item(Material.SPECTRAL_ARROW, "Previous Page", NamedTextColor.YELLOW));
        menu.setItem(40, item(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        menu.setItem(42, item(Material.SPECTRAL_ARROW, "Next Page", NamedTextColor.YELLOW));
        menu.setItem(BACK_SLOT, backItem("Back to Admin"));
        menu.setItem(CLOSE_SLOT, closeItem());
        admin.openInventory(menu);
    }

    private void openAdminMarketplaceConfirm(Player admin, MenuType type, UUID id) {
        Inventory menu = create(type, id, null, null, "Confirm Administrative Action", NamedTextColor.DARK_RED, 54);
        fill(menu);
        String action = type == MenuType.ADMIN_AUCTION_CONFIRM ? "Expire Auction" : "Cancel & Refund Order";
        menu.setItem(20, item(Material.LIME_CONCRETE, "Confirm " + action, NamedTextColor.GREEN,
                "This action is recorded immediately"));
        menu.setItem(24, item(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        menu.setItem(BACK_SLOT, backItem("Back"));
        menu.setItem(CLOSE_SLOT, closeItem());
        admin.openInventory(menu);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isShiftClick() || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!event.getClick().isLeftClick() && !event.getClick().isRightClick()) return;
        int slot = event.getRawSlot();

        if (slot == CLOSE_SLOT) { player.closeInventory(); return; }
        switch (holder.type) {
            case ADMIN_HOME -> {
                if (slot == 19) prompt(player, PromptType.ADMIN_TARGET, null, null);
                else if (slot == 20) openAdminAuctions(player, 0);
                else if (slot == 21) openAdminOrders(player, 0);
                else if (slot == 22) player.performCommand("bounty");
                else if (slot == 23) player.performCommand("sell prices");
                else if (slot == 24) openStatistics(player,"all");
                else if (slot == 25) { plugin.reloadConfig(); player.sendMessage(text("Configuration reloaded.", NamedTextColor.GREEN)); openAdminHome(player); }
                else if (slot == BACK_SLOT) openHub(player);
            }
            case ADMIN_AUCTIONS -> {
                UUID id = holder.ids.get(slot);
                if (id != null) {
                    AuctionService.Listing listing = auctions.get(id);
                    if (listing != null && listing.status() == AuctionService.Status.ACTIVE)
                        openAdminMarketplaceConfirm(player, MenuType.ADMIN_AUCTION_CONFIRM, id);
                } else if (slot == 38) openAdminAuctions(player, holder.page - 1);
                else if (slot == 40) openAdminAuctions(player, holder.page);
                else if (slot == 42) openAdminAuctions(player, holder.page + 1);
                else if (slot == BACK_SLOT) openAdminHome(player);
            }
            case ADMIN_ORDERS -> {
                UUID id = holder.ids.get(slot);
                if (id != null) {
                    OrderService.BuyOrder order = orders.get(id);
                    if (order != null && order.status() == OrderService.Status.ACTIVE)
                        openAdminMarketplaceConfirm(player, MenuType.ADMIN_ORDER_CONFIRM, id);
                } else if (slot == 38) openAdminOrders(player, holder.page - 1);
                else if (slot == 40) openAdminOrders(player, holder.page);
                else if (slot == 42) openAdminOrders(player, holder.page + 1);
                else if (slot == BACK_SLOT) openAdminHome(player);
            }
            case ADMIN_AUCTION_CONFIRM -> {
                if (slot == 20) {
                    boolean success = auctions.adminExpire(holder.targetId);
                    player.sendMessage(text(success ? "Auction expired; the seller can collect the item."
                            : "That auction is no longer active.", success ? NamedTextColor.GREEN : NamedTextColor.RED));
                    openAdminAuctions(player, 0);
                } else if (slot == 24 || slot == BACK_SLOT) openAdminAuctions(player, 0);
            }
            case ADMIN_ORDER_CONFIRM -> {
                if (slot == 20) {
                    OrderService.ActionResult result = orders.adminCancel(holder.targetId);
                    player.sendMessage(text(result.success() ? "Order cancelled and refunded "
                            + economy.format(result.refund()) + "." : result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    openAdminOrders(player, 0);
                } else if (slot == 24 || slot == BACK_SLOT) openAdminOrders(player, 0);
            }
            case ADMIN_STATS -> {
                if(slot==38)openStatistics(player,"today");else if(slot==40)openStatistics(player,"week");
                else if(slot==42)openStatistics(player,"all");else if(slot==BACK_SLOT)openAdminHome(player);
            }
            case HUB -> clickHub(player, slot);
            case BANK -> clickBank(player, slot);
            case PAY_AMOUNT -> clickAmount(player, holder, slot);
            case PAY_CONFIRM -> clickConfirmation(player, holder, slot);
            case HISTORY -> { if (slot == BACK_SLOT) openBank(player); }
            case LEADERBOARD -> {
                if (slot == BACK_SLOT) openBank(player);
                else if (slot == 40) { togglePrivacy(player); openLeaderboard(player); }
            }
            case ADMIN_ACCOUNT -> clickAdmin(player, holder, slot);
            case ADMIN_HISTORY -> {
                if (slot == BACK_SLOT) openAdminAccount(player, holder.targetId, holder.targetName);
            }
            case BANK_NOTE_WITHDRAW -> clickBankNoteWithdrawal(player, slot, event.isRightClick());
        }
    }

    private void clickHub(Player player, int slot) {
        if (slot == 20) openBank(player);
        else if (slot == 21) player.performCommand("auction");
        else if (slot == 24) player.performCommand("bounty");
        else if (slot == 22) player.performCommand("orders");
        else if (slot == 23) player.performCommand("sell");
    }

    private void clickBank(Player player, int slot) {
        if (slot == BACK_SLOT) openHub(player);
        else if (slot == 19) prompt(player, PromptType.PAY_TARGET, null, null);
        else if (slot == 20) openBank(player);
        else if (slot == 21) openHistory(player, player.getUniqueId(), player.getName(), false);
        else if (slot == 23) openLeaderboard(player);
        else if (slot == 24) { togglePrivacy(player); openBank(player); }
        else if (slot == 22) openBankNoteWithdrawal(player);
        else if (slot == 25) {
            boolean enabled = economy.toggleSaleNotifications(player.getUniqueId());
            if (enabled) economy.deliverPendingNotifications(player.getUniqueId());
            economy.save();
            player.sendMessage(text("Marketplace notifications " + (enabled ? "enabled." : "disabled."),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
            openBank(player);
        }
    }

    private void clickAmount(Player player, MenuHolder holder, int slot) {
        if (slot == BACK_SLOT) openBank(player);
        else if (PAYMENT_AMOUNTS.containsKey(slot)) openConfirmation(player, holder.targetId, holder.targetName, PAYMENT_AMOUNTS.get(slot));
        else if (slot == 31) prompt(player, PromptType.PAY_AMOUNT, holder.targetId, holder.targetName);
    }

    private void clickConfirmation(Player player, MenuHolder holder, int slot) {
        if (slot == 20) completePayment(player, holder);
        else if (slot == 24 || slot == BACK_SLOT) openAmountMenu(player, holder.targetId, holder.targetName);
    }

    private void clickAdmin(Player admin, MenuHolder holder, int slot) {
        if (!admin.hasPermission("voideconomy.admin")) { admin.closeInventory(); return; }
        if (slot == BACK_SLOT) openAdminHome(admin);
        else if (slot == 20) prompt(admin, PromptType.ADMIN_GIVE, holder.targetId, holder.targetName);
        else if (slot == 21) prompt(admin, PromptType.ADMIN_TAKE, holder.targetId, holder.targetName);
        else if (slot == 23) prompt(admin, PromptType.ADMIN_SET, holder.targetId, holder.targetName);
        else if (slot == 24) applyAdmin(admin, holder.targetId, holder.targetName, PromptType.ADMIN_RESET, BigDecimal.ZERO);
        else if (slot == 31) openHistory(admin, holder.targetId, holder.targetName, true);
        else if (slot == 40) prompt(admin, PromptType.ADMIN_TARGET, null, null);
    }

    private void clickBankNoteWithdrawal(Player player, int slot, boolean rightClick) {
        if (slot == BACK_SLOT) {
            openBank(player);
            return;
        }
        int denomination = switch (slot) {
            case 19 -> 1;
            case 20 -> 5;
            case 21 -> 10;
            case 22 -> 50;
            case 23 -> 100;
            case 24 -> 500;
            case 25 -> 1000;
            default -> 0;
        };
        if (denomination == 0) return;
        int noteCount = rightClick ? 10 : 1;
        BigDecimal total = BigDecimal.valueOf((long) denomination * noteCount);
        if (!economy.withdraw(player.getUniqueId(), total)) {
            player.sendMessage(text("You do not have enough money to withdraw those notes.", NamedTextColor.RED));
            return;
        }
        ItemStack notes = bankNotes.createNote(denomination, noteCount);
        player.getInventory().addItem(notes).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        economy.record("BANK_NOTE_WITHDRAWAL", player.getUniqueId(), player.getName(),
                player.getUniqueId(), player.getName(), total);
        economy.save();
        player.sendMessage(text("Withdrew " + noteCount + " × $" + denomination
                + " bank note" + (noteCount == 1 ? "" : "s") + ".", NamedTextColor.GREEN));
        openBankNoteWithdrawal(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
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
        if (prompt.type == PromptType.PAY_TARGET || prompt.type == PromptType.ADMIN_TARGET) {
            OfflinePlayer target = findPlayer(input);
            if (target == null || target.getUniqueId().equals(player.getUniqueId()) && prompt.type == PromptType.PAY_TARGET) {
                player.sendMessage(text(target == null ? "Player not found." : "You cannot pay yourself.", NamedTextColor.RED));
                if (prompt.type == PromptType.PAY_TARGET) openBank(player); else openHub(player);
                return;
            }
            economy.rememberName(target.getUniqueId(), playerName(target));
            if (prompt.type == PromptType.PAY_TARGET) openAmountMenu(player, target.getUniqueId(), playerName(target));
            else openAdminAccount(player, target.getUniqueId(), playerName(target));
            return;
        }
        BigDecimal amount = parseAmount(input, prompt.type == PromptType.ADMIN_SET);
        if (amount == null) {
            player.sendMessage(text("Invalid amount. Returning to the previous menu.", NamedTextColor.RED));
            if (prompt.type == PromptType.PAY_AMOUNT) openAmountMenu(player, prompt.targetId, prompt.targetName);
            else openAdminAccount(player, prompt.targetId, prompt.targetName);
            return;
        }
        if (prompt.type == PromptType.PAY_AMOUNT) openConfirmation(player, prompt.targetId, prompt.targetName, amount);
        else applyAdmin(player, prompt.targetId, prompt.targetName, prompt.type, amount);
    }

    private void prompt(Player player, PromptType type, UUID targetId, String targetName) {
        prompts.put(player.getUniqueId(), new Prompt(type, targetId, targetName));
        player.closeInventory();
        String message = switch (type) {
            case PAY_TARGET -> "Type the username of the player you want to pay.";
            case PAY_AMOUNT -> "Type the custom amount you want to pay.";
            case ADMIN_TARGET -> "Type the username of the player to manage.";
            case ADMIN_GIVE -> "Type the amount to give " + targetName + ".";
            case ADMIN_TAKE -> "Type the amount to take from " + targetName + ".";
            case ADMIN_SET -> "Type the new balance for " + targetName + ".";
            case ADMIN_RESET -> "";
        };
        player.sendMessage(text(message, NamedTextColor.YELLOW));
        player.sendMessage(text("Your next chat message will not be public.", NamedTextColor.GRAY));
    }

    private void completePayment(Player sender, MenuHolder holder) {
        if (holder.amount == null || !economy.transfer(sender.getUniqueId(), holder.targetId, holder.amount)) {
            sender.sendMessage(text("You do not have enough money.", NamedTextColor.RED));
            openAmountMenu(sender, holder.targetId, holder.targetName);
            return;
        }
        sender.sendMessage(text("Paid " + holder.targetName + " " + economy.format(holder.amount) + ".", NamedTextColor.GREEN));
        Player recipient = Bukkit.getPlayer(holder.targetId);
        if (recipient != null) recipient.sendMessage(text("You received " + economy.format(holder.amount)
                + " from " + sender.getName() + ".", NamedTextColor.GREEN));
        openBank(sender);
    }

    private void applyAdmin(Player admin, UUID targetId, String targetName, PromptType action, BigDecimal amount) {
        BigDecimal before=economy.getBalance(targetId);
        switch (action) {
            case ADMIN_GIVE -> economy.deposit(targetId, amount);
            case ADMIN_TAKE -> economy.setBalance(targetId, economy.getBalance(targetId).subtract(amount));
            case ADMIN_SET -> economy.setBalance(targetId, amount);
            case ADMIN_RESET -> { economy.reset(targetId); amount = economy.getBalance(targetId); }
            default -> { return; }
        }
        economy.record(action.name(), admin.getUniqueId(), admin.getName(), targetId, targetName, amount);
        BigDecimal delta=economy.getBalance(targetId).subtract(before);
        if(delta.signum()>0)sell.recordAdminChange(delta,true);else if(delta.signum()<0)sell.recordAdminChange(delta.abs(),false);
        economy.save();
        admin.sendMessage(text("Updated " + targetName + ". New balance: "
                + economy.format(economy.getBalance(targetId)), NamedTextColor.GREEN));
        openAdminAccount(admin, targetId, targetName);
    }

    private void togglePrivacy(Player player) {
        boolean hidden = economy.togglePrivacy(player.getUniqueId());
        player.sendMessage(text(hidden ? "Your balance is now hidden from /baltop."
                : "Your balance is now visible on /baltop.", hidden ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
    }

    private Inventory create(MenuType type, UUID targetId, String targetName, BigDecimal amount,
                             String title, NamedTextColor color, int size) {
        MenuHolder holder = new MenuHolder(type, targetId, targetName, amount);
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text(title, color));
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack accountItem(Player player) {
        return head(player.getUniqueId(), "Your Account", NamedTextColor.GREEN,
                "Current balance: " + economy.format(economy.getBalance(player.getUniqueId())));
    }

    private ItemStack privacyItem(Player player) {
        boolean hidden = economy.isPrivate(player.getUniqueId());
        return item(hidden ? Material.REDSTONE_TORCH : Material.LEVER,
                hidden ? "Leaderboard: Hidden" : "Leaderboard: Visible",
                hidden ? NamedTextColor.RED : NamedTextColor.GREEN, "Click to toggle privacy");
    }

    private ItemStack head(UUID id, String name, NamedTextColor color, String... lore) {
        ItemStack item = item(Material.PLAYER_HEAD, name, color, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory) {
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY);
        ItemStack inside = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        int rows = inventory.getSize() / 9;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9, column = slot % 9;
            inventory.setItem(slot, row == 0 || row == rows - 1 || column == 0 || column == 8 ? border : inside);
        }
    }
    private ItemStack backItem(String name) { return item(Material.ARROW, name, NamedTextColor.YELLOW); }
    private ItemStack closeItem() { return item(Material.BARRIER, "Close", NamedTextColor.RED); }
    private ItemStack item(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(label(name, color));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(line -> text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }
    private Component label(String value, NamedTextColor color) { return text(value, color); }
    private Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
    private boolean deny(Player player) { player.sendMessage(plugin.message("no-permission")); return true; }
    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached : null;
    }
    private String playerName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }
    private BigDecimal parseAmount(String value, boolean allowZero) {
        try {
            BigDecimal amount = new BigDecimal(value);
            return (allowZero ? amount.signum() >= 0 : amount.signum() > 0) && amount.scale() <= 2 ? amount : null;
        } catch (NumberFormatException exception) { return null; }
    }

    private enum MenuType { HUB, BANK, PAY_AMOUNT, PAY_CONFIRM, HISTORY, LEADERBOARD, ADMIN_HOME,
        ADMIN_ACCOUNT, ADMIN_HISTORY, ADMIN_AUCTIONS, ADMIN_ORDERS, ADMIN_AUCTION_CONFIRM,
        ADMIN_ORDER_CONFIRM, ADMIN_STATS, BANK_NOTE_WITHDRAW }
    private enum PromptType { PAY_TARGET, PAY_AMOUNT, ADMIN_TARGET, ADMIN_GIVE, ADMIN_TAKE, ADMIN_SET, ADMIN_RESET }
    private record Prompt(PromptType type, UUID targetId, String targetName) { }
    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final UUID targetId;
        private final String targetName;
        private final BigDecimal amount;
        private final java.util.Map<Integer, UUID> ids = new java.util.HashMap<>();
        private int page;
        private Inventory inventory;
        private MenuHolder(MenuType type, UUID targetId, String targetName, BigDecimal amount) {
            this.type = type; this.targetId = targetId; this.targetName = targetName; this.amount = amount;
        }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}

package com.voidplugin.economy;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class VoidEconomyPlugin extends JavaPlugin {
    private EconomyService economy;
    private AuctionService auctions;
    private BountyService bounties;
    private BankNoteService bankNotes;
    private OrderService orders;
    private SellService sell;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        if (Math.abs(getConfig().getDouble("marketplace.transaction-fee-rate", 0.035) - 0.02) < 0.0000001) {
            getConfig().set("marketplace.transaction-fee-rate", 0.035);
            saveConfig();
            getLogger().info("Migrated marketplace transaction fee from 2% to 3.5%.");
        }
        economy = new EconomyService(this);
        economy.load();
        sell = new SellService(this,economy);
        sell.load();
        auctions = new AuctionService(this, economy, sell);
        auctions.load();
        bounties = new BountyService(this, economy);
        bounties.load();
        orders = new OrderService(this, economy, sell);
        orders.load();
        bankNotes = new BankNoteService(this);
        getServer().getPluginManager().registerEvents(new BankNoteListener(economy, bankNotes), this);
        EconomyCommand commands = new EconomyCommand(this, economy);
        register("balance", commands);
        register("pay", commands);
        register("eco", commands);
        MenuManager menus = new MenuManager(this, economy, bankNotes, auctions, orders, sell);
        registerMenuCommand("menu", menus);
        registerMenuCommand("bank", menus);
        registerMenuCommand("baltop", menus);
        registerMenuCommand("balprivacy", menus);
        registerMenuCommand("admin", menus);
        registerMenuCommand("ve", menus);
        getServer().getPluginManager().registerEvents(menus, this);
        AuctionManager auctionManager = new AuctionManager(this, economy, auctions, sell);
        PluginCommand auctionCommand = getCommand("auction");
        if (auctionCommand == null) throw new IllegalStateException("Command missing from plugin.yml: auction");
        auctionCommand.setExecutor(auctionManager);
        getServer().getPluginManager().registerEvents(auctionManager, this);
        BountyManager bountyManager = new BountyManager(this, economy, bounties);
        PluginCommand bountyCommand = getCommand("bounty");
        if (bountyCommand == null) throw new IllegalStateException("Command missing from plugin.yml: bounty");
        bountyCommand.setExecutor(bountyManager);
        getServer().getPluginManager().registerEvents(bountyManager, this);
        OrderManager orderManager = new OrderManager(this, economy, orders);
        PluginCommand orderCommand = getCommand("orders");
        if (orderCommand == null) throw new IllegalStateException("Command missing from plugin.yml: orders");
        orderCommand.setExecutor(orderManager);
        getServer().getPluginManager().registerEvents(orderManager, this);
        SellManager sellManager=new SellManager(this,economy,sell);
        PluginCommand sellCommand=getCommand("sell");
        if(sellCommand==null)throw new IllegalStateException("Command missing from plugin.yml: sell");
        sellCommand.setExecutor(sellManager);sellCommand.setTabCompleter(sellManager);
        getServer().getPluginManager().registerEvents(sellManager,this);
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            VaultHook.register(this, economy);
        } else {
            getLogger().info("Vault not found; Vault economy integration is disabled.");
        }
        long interval = Math.max(20L, getConfig().getLong("save-interval-seconds", 300L) * 20L);
        getServer().getScheduler().runTaskTimer(this, economy::save, interval, interval);
        getServer().getScheduler().runTaskTimer(this, bounties::save, interval, interval);
        getServer().getScheduler().runTaskTimer(this, auctions::refreshExpired, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, orders::refreshExpired, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, sell::save, interval, interval);
        getServer().getScheduler().runTaskTimer(this, () -> sell.recordSnapshot(
                economy.totalAccountBalances().add(orders.activeEscrow()).add(economy.outstandingBankNotes())),
                20L, Math.min(interval,72000L));
        getLogger().info("VoidEconomy enabled with " + economy.accountCount() + " account(s).");
    }

    private void register(String name, EconomyCommand executor) {
        PluginCommand command = getCommand(name);
        if (command == null) throw new IllegalStateException("Command missing from plugin.yml: " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerMenuCommand(String name, MenuManager executor) {
        PluginCommand command = getCommand(name);
        if (command == null) throw new IllegalStateException("Command missing from plugin.yml: " + name);
        command.setExecutor(executor);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (economy != null) economy.save();
        if (auctions != null) auctions.save();
        if (bounties != null) bounties.save();
        if (orders != null) orders.save();
        if (sell != null) sell.save();
    }

    public String message(String key) {
        return Text.color(getConfig().getString("messages.prefix", "")
                + getConfig().getString("messages." + key, key));
    }

    public BankNoteService getBankNoteService() {
        return bankNotes;
    }
}

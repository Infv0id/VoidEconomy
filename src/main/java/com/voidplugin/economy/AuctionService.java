package com.voidplugin.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AuctionService {
    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final SellService sell;
    private final File file;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();

    AuctionService(VoidEconomyPlugin plugin, EconomyService economy, SellService sell) {
        this.plugin = plugin;
        this.economy = economy;
        this.sell = sell;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
    }

    synchronized Listing create(UUID sellerId, String sellerName, ItemStack item, BigDecimal price) {
        refreshExpired();
        if (price.compareTo(sell.minimumAuctionPrice(item)) < 0) return null;
        if (activeCount(sellerId) >= plugin.getConfig().getInt("auction.maximum-active-listings", 10)) return null;
        long now = Instant.now().toEpochMilli();
        long duration = Duration.ofHours(Math.max(1, plugin.getConfig().getLong("auction.duration-hours", 48))).toMillis();
        Listing listing = new Listing(UUID.randomUUID(), sellerId, sellerName, item.clone(), price,
                now, now + duration, Status.ACTIVE, null, null, false, false);
        listings.put(listing.id(), listing);
        economy.rememberName(sellerId, sellerName);
        save();
        return listing;
    }

    synchronized PurchaseResult purchase(UUID listingId, UUID buyerId, String buyerName) {
        refreshExpired();
        Listing listing = listings.get(listingId);
        if (listing == null || listing.status() != Status.ACTIVE) return new PurchaseResult(Result.UNAVAILABLE, null);
        if (listing.sellerId().equals(buyerId)) return new PurchaseResult(Result.OWN_LISTING, null);
        if (!economy.withdraw(buyerId, listing.price())) return new PurchaseResult(Result.INSUFFICIENT_FUNDS, null);
        Listing sold = listing.withSale(buyerId, buyerName);
        listings.put(listingId, sold);
        BigDecimal fee=sell.fee(listing.price());
        BigDecimal net=listing.price().subtract(fee);
        economy.deposit(listing.sellerId(), net);
        sell.recordFee("AUCTION",fee);
        economy.rememberName(buyerId, buyerName);
        economy.record("AUCTION_PURCHASE", buyerId, buyerName, listing.sellerId(), listing.sellerName(), listing.price());
        economy.notifySale(listing.sellerId(), "&8[&6Economy&8] &a" + buyerName + " bought your "
                + listing.item().getAmount() + "x " + pretty(listing.item().getType())
                + ". Gross: " + economy.format(listing.price()) + ", fee: " + economy.format(fee)
                + ", received: " + economy.format(net) + ".");
        economy.save();
        save();
        return new PurchaseResult(Result.SUCCESS, sold.item().clone());
    }

    synchronized ItemStack cancel(UUID listingId, UUID sellerId) {
        refreshExpired();
        Listing listing = listings.get(listingId);
        if (listing == null || !listing.sellerId().equals(sellerId) || listing.status() != Status.ACTIVE) return null;
        listings.put(listingId, listing.withStatus(Status.CANCELLED, true));
        save();
        return listing.item().clone();
    }

    synchronized CollectResult collect(UUID listingId, UUID sellerId) {
        refreshExpired();
        Listing listing = listings.get(listingId);
        if (listing == null || !listing.sellerId().equals(sellerId)) return CollectResult.none();
        if (listing.status() == Status.SOLD && !listing.valueCollected()) {
            listings.put(listingId, listing.withCollected(true));
            save();
            economy.deposit(sellerId, listing.price());
            economy.save();
            return new CollectResult(listing.price(), null);
        }
        if (listing.status() == Status.EXPIRED && !listing.valueCollected()) {
            listings.put(listingId, listing.withCollected(true));
            save();
            return new CollectResult(null, listing.item().clone());
        }
        return CollectResult.none();
    }

    synchronized List<Listing> activeListings() {
        refreshExpired();
        return listings.values().stream().filter(listing -> listing.status() == Status.ACTIVE).toList();
    }

    synchronized List<Listing> sellerListings(UUID sellerId) {
        refreshExpired();
        return listings.values().stream()
                .filter(listing -> listing.sellerId().equals(sellerId))
                .filter(listing -> listing.status() != Status.CANCELLED)
                .sorted(Comparator.comparingLong(Listing::createdAt).reversed())
                .toList();
    }

    synchronized List<Listing> allListings() {
        refreshExpired();
        return listings.values().stream()
                .sorted(Comparator.comparingLong(Listing::createdAt).reversed()).toList();
    }

    synchronized boolean adminExpire(UUID listingId) {
        refreshExpired();
        Listing listing = listings.get(listingId);
        if (listing == null || listing.status() != Status.ACTIVE) return false;
        listings.put(listingId, listing.withStatus(Status.EXPIRED, false));
        save();
        economy.notifySale(listing.sellerId(), "&8[&6Economy&8] &eAn administrator expired your auction listing. "
                + "Open My Listings to collect the returned item.");
        economy.save();
        return true;
    }

    synchronized Listing get(UUID id) {
        refreshExpired();
        return listings.get(id);
    }

    synchronized int activeCount(UUID sellerId) {
        return (int) listings.values().stream()
                .filter(listing -> listing.sellerId().equals(sellerId) && listing.status() == Status.ACTIVE)
                .count();
    }

    synchronized void refreshExpired() {
        long now = Instant.now().toEpochMilli();
        boolean changed = false;
        for (Listing listing : new ArrayList<>(listings.values())) {
            if (listing.status() == Status.ACTIVE && listing.expiresAt() <= now) {
                listings.put(listing.id(), listing.withStatus(Status.EXPIRED, false));
                changed = true;
            }
        }
        if (changed) save();
    }

    synchronized void load() {
        listings.clear();
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = data.getConfigurationSection("listings");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection row = root.getConfigurationSection(key);
                if (row == null) continue;
                ItemStack item = row.getItemStack("item");
                if (item == null || item.getType().isAir()) continue;
                String buyerId = row.getString("buyer-id");
                listings.put(UUID.fromString(key), new Listing(
                        UUID.fromString(key), UUID.fromString(row.getString("seller-id", "")),
                        row.getString("seller-name", "Unknown"), item,
                        new BigDecimal(row.getString("price", "0")), row.getLong("created-at"),
                        row.getLong("expires-at"), Status.valueOf(row.getString("status", "ACTIVE")),
                        buyerId == null ? null : UUID.fromString(buyerId), row.getString("buyer-name"),
                        row.getBoolean("value-collected"), row.getBoolean("item-delivered")));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipped invalid auction listing " + key);
            }
        }
        refreshExpired();
    }

    synchronized void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Listing listing : listings.values()) {
            String path = "listings." + listing.id() + ".";
            data.set(path + "seller-id", listing.sellerId().toString());
            data.set(path + "seller-name", listing.sellerName());
            data.set(path + "item", listing.item());
            data.set(path + "price", listing.price().toPlainString());
            data.set(path + "created-at", listing.createdAt());
            data.set(path + "expires-at", listing.expiresAt());
            data.set(path + "status", listing.status().name());
            data.set(path + "buyer-id", listing.buyerId() == null ? null : listing.buyerId().toString());
            data.set(path + "buyer-name", listing.buyerName());
            data.set(path + "value-collected", listing.valueCollected());
            data.set(path + "item-delivered", listing.itemDelivered());
        }
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save auctions.yml: " + exception.getMessage());
        }
    }

    enum Status { ACTIVE, SOLD, EXPIRED, CANCELLED }
    enum Result { SUCCESS, UNAVAILABLE, OWN_LISTING, INSUFFICIENT_FUNDS }

    record PurchaseResult(Result result, ItemStack item) { }
    record CollectResult(BigDecimal money, ItemStack item) {
        static CollectResult none() { return new CollectResult(null, null); }
        boolean empty() { return money == null && item == null; }
    }

    record Listing(UUID id, UUID sellerId, String sellerName, ItemStack item, BigDecimal price,
                   long createdAt, long expiresAt, Status status, UUID buyerId, String buyerName,
                   boolean valueCollected, boolean itemDelivered) {
        Listing withSale(UUID buyerId, String buyerName) {
            return new Listing(id, sellerId, sellerName, item, price, createdAt, expiresAt,
                    Status.SOLD, buyerId, buyerName, true, true);
        }
        Listing withStatus(Status status, boolean collected) {
            return new Listing(id, sellerId, sellerName, item, price, createdAt, expiresAt,
                    status, buyerId, buyerName, collected, itemDelivered);
        }
        Listing withCollected(boolean collected) {
            return new Listing(id, sellerId, sellerName, item, price, createdAt, expiresAt,
                    status, buyerId, buyerName, collected, itemDelivered);
        }
    }

    private static String pretty(org.bukkit.Material material) {
        String value = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}

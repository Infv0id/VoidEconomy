package com.voidplugin.economy;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class OrderService {
    private static final Set<Material> NON_SURVIVAL_ITEMS = Set.of(
            Material.LIGHT, Material.BARRIER, Material.DEBUG_STICK, Material.KNOWLEDGE_BOOK,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.COMMAND_BLOCK_MINECART, Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID,
            Material.JIGSAW, Material.SPAWNER, Material.BEDROCK, Material.END_PORTAL_FRAME,
            Material.REINFORCED_DEEPSLATE, Material.PETRIFIED_OAK_SLAB, Material.BUDDING_AMETHYST
    );
    private static final Set<String> NON_SURVIVAL_ITEM_NAMES = Set.of(
            "FARMLAND", "DIRT_PATH", "FROSTED_ICE", "CHORUS_PLANT",
            "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "TRIAL_SPAWNER", "VAULT",
            "END_PORTAL", "NETHER_PORTAL", "END_GATEWAY", "FIRE", "SOUL_FIRE",
            "WATER", "LAVA", "BUBBLE_COLUMN", "MOVING_PISTON", "PISTON_HEAD",
            "REDSTONE_WIRE", "TRIPWIRE", "TALL_SEAGRASS", "ATTACHED_MELON_STEM",
            "ATTACHED_PUMPKIN_STEM"
    );
    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final SellService sell;
    private final File file;
    private final List<BuyOrder> orders = new ArrayList<>();

    OrderService(VoidEconomyPlugin plugin, EconomyService economy, SellService sell) {
        this.plugin = plugin;
        this.economy = economy;
        this.sell = sell;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
    }

    synchronized CreateResult create(UUID buyer, String buyerName, ItemStack requestedItem, int quantity,
                                     BigDecimal pricePerItem) {
        refreshExpired();
        Material material = requestedItem.getType();
        if (!allowedMaterials().contains(material)) return new CreateResult(false, "That material is not allowed.", null);
        if (quantity < 1 || quantity > maximumQuantity())
            return new CreateResult(false, "Quantity must be between 1 and " + maximumQuantity() + ".", null);
        BigDecimal unitPrice = money(pricePerItem);
        if (unitPrice.compareTo(minimumPrice()) < 0)
            return new CreateResult(false, "The price per item is below the configured minimum.", null);
        long active = orders.stream().filter(o -> o.buyerId.equals(buyer) && o.status == Status.ACTIVE).count();
        if (active >= maxOrders()) return new CreateResult(false, "You already have the maximum number of active orders.", null);
        BigDecimal escrow = money(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        if (!economy.withdraw(buyer, escrow)) return new CreateResult(false, "You do not have enough money.", null);
        long now = Instant.now().toEpochMilli();
        requestedItem = requestedItem.clone();
        requestedItem.setAmount(1);
        BuyOrder order = new BuyOrder(UUID.randomUUID(), buyer, buyerName, requestedItem, quantity, 0, 0,
                unitPrice, escrow, now, now + expiryMillis(), Status.ACTIVE);
        orders.add(order);
        economy.record("ORDER_ESCROW", buyer, buyerName, buyer, buyerName, escrow);
        economy.save();
        save();
        return new CreateResult(true, "", order);
    }

    synchronized ActionResult cancel(UUID orderId, UUID buyer) {
        refreshExpired();
        BuyOrder order = find(orderId);
        if (order == null || !order.buyerId.equals(buyer) || order.status != Status.ACTIVE)
            return new ActionResult(false, "That order is no longer active.", BigDecimal.ZERO);
        BigDecimal refund = order.escrowRemaining;
        order.escrowRemaining = BigDecimal.ZERO.setScale(2);
        order.status = Status.CANCELLED;
        if (refund.signum() > 0) {
            economy.deposit(buyer, refund);
            economy.record("ORDER_REFUND", buyer, order.buyerName, buyer, order.buyerName, refund);
        }
        economy.save();
        save();
        return new ActionResult(true, "Order cancelled.", refund);
    }

    synchronized DeliveryResult deliver(UUID orderId, UUID seller, String sellerName, int offered) {
        refreshExpired();
        BuyOrder order = find(orderId);
        if (order == null || order.status != Status.ACTIVE)
            return new DeliveryResult(false, "That order is no longer active.", 0, offered, BigDecimal.ZERO);
        if (order.buyerId.equals(seller))
            return new DeliveryResult(false, "You cannot fill your own order.", 0, offered, BigDecimal.ZERO);
        int needed = order.quantity - order.delivered;
        int accepted = Math.min(Math.max(0, offered), needed);
        if (accepted == 0) return new DeliveryResult(false, "Place matching items in the delivery grid first.", 0, offered, BigDecimal.ZERO);
        BigDecimal gross = money(order.pricePerItem.multiply(BigDecimal.valueOf(accepted)));
        if (gross.compareTo(order.escrowRemaining) > 0)
            return new DeliveryResult(false, "The escrow balance is invalid; delivery was stopped safely.", 0, offered, BigDecimal.ZERO);
        order.delivered += accepted;
        order.stash += accepted;
        order.escrowRemaining = money(order.escrowRemaining.subtract(gross));
        BigDecimal fee=sell.fee(gross),payment=money(gross.subtract(fee));
        economy.deposit(seller, payment);
        sell.recordFee("ORDER",fee);
        economy.record("ORDER_DELIVERY", order.buyerId, order.buyerName, seller, sellerName, gross);
        economy.notifySale(order.buyerId, "&8[&6Economy&8] &a" + sellerName + " fulfilled "
                + accepted + "x " + pretty(order.material()) + " from your buy order and earned "
                + economy.format(payment) + " after a " + economy.format(fee) + " fee. The items are ready in your stash.");
        if (order.delivered >= order.quantity) order.status = Status.COMPLETED;
        economy.save();
        save();
        return new DeliveryResult(true, "Delivered " + accepted + " item(s).", accepted, offered - accepted, payment);
    }

    synchronized CollectResult collect(UUID orderId, UUID buyer) {
        BuyOrder order = find(orderId);
        if (order == null || !order.buyerId.equals(buyer) || order.stash < 1)
            return new CollectResult(false, null, 0);
        int amount = order.stash;
        order.stash = 0;
        save();
        return new CollectResult(true, order.requestedItem(), amount);
    }

    synchronized void refreshExpired() {
        long now = Instant.now().toEpochMilli();
        boolean changed = false;
        for (BuyOrder order : orders) {
            if (order.status != Status.ACTIVE || order.expiresAt > now) continue;
            BigDecimal refund = order.escrowRemaining;
            order.escrowRemaining = BigDecimal.ZERO.setScale(2);
            order.status = Status.EXPIRED;
            if (refund.signum() > 0) {
                economy.deposit(order.buyerId, refund);
                economy.record("ORDER_REFUND", order.buyerId, order.buyerName,
                        order.buyerId, order.buyerName, refund);
            }
            changed = true;
        }
        if (changed) { economy.save(); save(); }
    }

    synchronized List<BuyOrder> activeOrders() {
        refreshExpired();
        return orders.stream().filter(o -> o.status == Status.ACTIVE)
                .sorted(Comparator.comparingLong(BuyOrder::createdAt).reversed()).toList();
    }

    synchronized List<BuyOrder> buyerOrders(UUID buyer) {
        refreshExpired();
        return orders.stream().filter(o -> o.buyerId.equals(buyer))
                .sorted(Comparator.comparingLong(BuyOrder::createdAt).reversed()).toList();
    }

    synchronized List<BuyOrder> allOrders() {
        refreshExpired();
        return orders.stream().sorted(Comparator.comparingLong(BuyOrder::createdAt).reversed()).toList();
    }
    synchronized BigDecimal activeEscrow() {
        refreshExpired();
        return orders.stream().filter(o->o.status==Status.ACTIVE).map(o->o.escrowRemaining)
                .reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.DOWN);
    }

    synchronized ActionResult adminCancel(UUID orderId) {
        BuyOrder order = find(orderId);
        if (order == null) return new ActionResult(false, "Order not found.", BigDecimal.ZERO);
        return cancel(orderId, order.buyerId);
    }

    synchronized BuyOrder get(UUID id) { refreshExpired(); return find(id); }
    Set<Material> allowedMaterials() {
        Set<Material> result = new LinkedHashSet<>();
        Set<Material> excluded = new java.util.HashSet<>(NON_SURVIVAL_ITEMS);
        for (String name : plugin.getConfig().getStringList("orders.excluded-materials")) {
            Material material = Material.matchMaterial(name);
            if (material != null) excluded.add(material);
        }
        if (plugin.getConfig().getBoolean("orders.include-all-materials", true)) {
            for (Material material : Material.values()) {
                if (isSurvivalObtainable(material) && !excluded.contains(material)) result.add(material);
            }
            return result;
        }
        for (String name : plugin.getConfig().getStringList("orders.allowed-materials")) {
            Material material = Material.matchMaterial(name);
            if (material != null && isSurvivalObtainable(material) && !excluded.contains(material)) result.add(material);
        }
        if (result.isEmpty()) result.addAll(List.of(Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT,
                Material.IRON_INGOT, Material.COAL, Material.REDSTONE, Material.LAPIS_LAZULI,
                Material.NETHERITE_INGOT, Material.ENDER_PEARL, Material.EXPERIENCE_BOTTLE,
                Material.ENCHANTED_BOOK, Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION,
                Material.OBSIDIAN, Material.OAK_LOG, Material.COBBLESTONE));
        return result;
    }

    private boolean isSurvivalObtainable(Material material) {
        if (!material.isItem() || material.isAir() || NON_SURVIVAL_ITEMS.contains(material)) return false;
        String name = material.name();
        return !name.endsWith("_SPAWN_EGG")
                && !name.startsWith("INFESTED_")
                && !name.startsWith("POTTED_")
                && !name.startsWith("WALL_")
                && !name.contains("COMMAND_BLOCK")
                && !name.contains("STRUCTURE_")
                && !name.contains("JIGSAW")
                && !name.contains("TEST_BLOCK")
                && !name.contains("TEST_INSTANCE")
                && !NON_SURVIVAL_ITEM_NAMES.contains(name);
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection rows = data.getConfigurationSection("orders");
        if (rows == null) return;
        for (String key : rows.getKeys(false)) try {
            String p = "orders." + key + ".";
            orders.add(new BuyOrder(UUID.fromString(key), UUID.fromString(data.getString(p + "buyer-id", "")),
                    data.getString(p + "buyer-name", "Unknown"), loadItem(data, p),
                    data.getInt(p + "quantity"), data.getInt(p + "delivered"), data.getInt(p + "stash"),
                    money(new BigDecimal(data.getString(p + "price-per-item", "0"))),
                    money(new BigDecimal(data.getString(p + "escrow", "0"))), data.getLong(p + "created-at"),
                    data.getLong(p + "expires-at"), Status.valueOf(data.getString(p + "status", "CANCELLED"))));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Skipped invalid order " + key + ": " + exception.getMessage());
        }
        refreshExpired();
    }

    synchronized void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (BuyOrder o : orders) {
            String p = "orders." + o.id + ".";
            data.set(p + "buyer-id", o.buyerId.toString());
            data.set(p + "buyer-name", o.buyerName);
            data.set(p + "material", o.material().name());
            data.set(p + "requested-item", o.requestedItem());
            data.set(p + "quantity", o.quantity);
            data.set(p + "delivered", o.delivered);
            data.set(p + "stash", o.stash);
            data.set(p + "price-per-item", o.pricePerItem.toPlainString());
            data.set(p + "escrow", o.escrowRemaining.toPlainString());
            data.set(p + "created-at", o.createdAt);
            data.set(p + "expires-at", o.expiresAt);
            data.set(p + "status", o.status.name());
        }
        try { data.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Could not save orders: " + exception.getMessage()); }
    }

    private BuyOrder find(UUID id) { return orders.stream().filter(o -> o.id.equals(id)).findFirst().orElse(null); }
    private ItemStack loadItem(YamlConfiguration data, String path) {
        ItemStack item = data.getItemStack(path + "requested-item");
        if (item == null || item.getType().isAir())
            item = new ItemStack(Material.valueOf(data.getString(path + "material", "STONE")));
        item.setAmount(1);
        return item;
    }
    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.DOWN); }
    private BigDecimal minimumPrice() {
        return money(BigDecimal.valueOf(plugin.getConfig().getDouble("orders.minimum-price-per-item", 1)));
    }
    private int maximumQuantity() { return Math.max(1, plugin.getConfig().getInt("orders.maximum-quantity", 2304)); }
    private int maxOrders() { return Math.max(1, plugin.getConfig().getInt("orders.maximum-active-orders", 10)); }
    private long expiryMillis() {
        return Math.max(1, plugin.getConfig().getLong("orders.expiry-days", 7)) * 86_400_000L;
    }
    private static String pretty(Material material) {
        String value = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    enum Status { ACTIVE, COMPLETED, EXPIRED, CANCELLED }
    static final class BuyOrder {
        private final UUID id, buyerId;
        private final String buyerName;
        private final ItemStack requestedItem;
        private final int quantity;
        private int delivered, stash;
        private final BigDecimal pricePerItem;
        private BigDecimal escrowRemaining;
        private final long createdAt, expiresAt;
        private Status status;
        BuyOrder(UUID id, UUID buyerId, String buyerName, ItemStack requestedItem, int quantity, int delivered, int stash,
                 BigDecimal pricePerItem, BigDecimal escrowRemaining, long createdAt, long expiresAt, Status status) {
            this.id=id; this.buyerId=buyerId; this.buyerName=buyerName; this.requestedItem=requestedItem; this.quantity=quantity;
            this.delivered=delivered; this.stash=stash; this.pricePerItem=pricePerItem;
            this.escrowRemaining=escrowRemaining; this.createdAt=createdAt; this.expiresAt=expiresAt; this.status=status;
        }
        UUID id(){return id;} UUID buyerId(){return buyerId;} String buyerName(){return buyerName;}
        Material material(){return requestedItem.getType();}
        ItemStack requestedItem(){return requestedItem.clone();}
        boolean matches(ItemStack item){if(item==null)return false;ItemStack copy=item.clone();copy.setAmount(1);return copy.isSimilar(requestedItem);}
        int quantity(){return quantity;} int delivered(){return delivered;}
        int stash(){return stash;} BigDecimal pricePerItem(){return pricePerItem;}
        BigDecimal escrowRemaining(){return escrowRemaining;} long createdAt(){return createdAt;}
        long expiresAt(){return expiresAt;} Status status(){return status;}
    }
    record CreateResult(boolean success, String message, BuyOrder order) {}
    record ActionResult(boolean success, String message, BigDecimal refund) {}
    record DeliveryResult(boolean success, String message, int accepted, int excess, BigDecimal payment) {}
    record CollectResult(boolean success, ItemStack item, int amount) {}
}

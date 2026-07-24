package com.voidplugin.economy;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class OrderManager implements Listener, CommandExecutor {
    private static final int PAGE_SIZE = 45;
    private static final int[] LIST_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final OrderService orders;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, MarketState> searchPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, MaterialState> materialSearchPrompts = new ConcurrentHashMap<>();

    OrderManager(VoidEconomyPlugin plugin, EconomyService economy, OrderService orders) {
        this.plugin = plugin; this.economy = economy; this.orders = orders;
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.message("players-only")); return true; }
        if (!player.hasPermission("voideconomy.orders")) { player.sendMessage(plugin.message("no-permission")); return true; }
        openHub(player);
        return true;
    }

    private void openHub(Player player) {
        Holder h = new Holder(View.HUB, 0, null); Inventory inv = menu(h, "Buy Orders");
        decorate(inv);
        inv.setItem(20, item(Material.CHEST, "Browse Buy Orders", NamedTextColor.AQUA,
                "Sell materials to other players", "Payment is instant"));
        inv.setItem(22, item(Material.WRITABLE_BOOK, "Create Buy Order", NamedTextColor.GREEN,
                "Choose a material, quantity, and price", "The full cost is held safely in escrow"));
        inv.setItem(24, item(Material.ENDER_CHEST, "My Orders & Stash", NamedTextColor.LIGHT_PURPLE,
                "Track, cancel, and collect your orders"));
        inv.setItem(45, item(Material.ARROW, "Back to Hub", NamedTextColor.YELLOW));
        inv.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(inv);
    }

    private void openMaterials(Player p, MaterialState state) {
        MaterialState selected = state;
        List<Material> materials = orders.allowedMaterials().stream()
                .filter(selected.category::matches)
                .filter(material -> selected.search.isBlank() || pretty(material).toLowerCase(Locale.ROOT)
                        .contains(selected.search.toLowerCase(Locale.ROOT)))
                .sorted(selected.sort.comparator).toList();
        int page = state.page;
        int pages = listPages(materials.size()); page = clamp(page, pages);
        state = state.withPage(page);
        Holder h = new Holder(View.MATERIALS, page, null); h.materialState = state;
        Inventory inv = menu(h, "Choose Order Material"); decorate(inv);
        for (int i = page * LIST_SLOTS.length; i < Math.min(materials.size(), (page + 1) * LIST_SLOTS.length); i++) {
            Material m = materials.get(i); int slot = LIST_SLOTS[i - page * LIST_SLOTS.length];
            inv.setItem(slot, item(m, pretty(m), NamedTextColor.GREEN, "Click to order this material"));
            h.ids.put(slot, m.name());
        }
        inv.setItem(38, item(Material.SPECTRAL_ARROW, "Previous Page", NamedTextColor.YELLOW));
        inv.setItem(39, item(Material.COMPASS, "Category: " + state.category.label, NamedTextColor.AQUA,
                "Click to change item category"));
        inv.setItem(40, item(Material.HOPPER, "Sort: " + state.sort.label, NamedTextColor.GOLD,
                "Click to change item ordering"));
        inv.setItem(41, item(Material.OAK_SIGN, state.search.isBlank() ? "Search Items" : "Search: " + state.search,
                NamedTextColor.YELLOW, "Left-click to search", "Right-click to clear"));
        inv.setItem(42, item(Material.SPECTRAL_ARROW, "Next Page", NamedTextColor.YELLOW));
        inv.setItem(45, item(Material.ARROW, "Back to Orders", NamedTextColor.YELLOW));
        inv.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void openMarket(Player p, int page) {
        openMarket(p, new MarketState(page, OrderSort.NEWEST, OrderCategory.ALL, ""));
    }

    private void openMarket(Player p, MarketState state) {
        MarketState selected = state;
        List<OrderService.BuyOrder> list = orders.activeOrders().stream()
                .filter(o -> !o.buyerId().equals(p.getUniqueId()))
                .filter(o -> selected.category.matches(o.material()))
                .filter(o -> selected.search.isBlank() || pretty(o.material()).toLowerCase(Locale.ROOT)
                        .contains(selected.search.toLowerCase(Locale.ROOT)))
                .sorted(selected.sort.comparator).toList();
        int pages = listPages(list.size()); int page = clamp(state.page, pages);
        state = state.withPage(page);
        Holder h = new Holder(View.MARKET, page, null); h.marketState = state;
        Inventory inv = menu(h, "Active Buy Orders"); decorate(inv);
        populate(inv, h, list, page, true);
        inv.setItem(37, item(Material.SPECTRAL_ARROW, "Previous Page", NamedTextColor.YELLOW));
        inv.setItem(38, item(Material.HOPPER, "Sort: " + state.sort.label, NamedTextColor.GOLD));
        inv.setItem(39, item(Material.COMPASS, "Category: " + state.category.label, NamedTextColor.AQUA));
        inv.setItem(40, item(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        inv.setItem(41, item(Material.OAK_SIGN, state.search.isBlank() ? "Search" : "Search: " + state.search,
                NamedTextColor.YELLOW, "Left-click to search", "Right-click to clear"));
        inv.setItem(42, item(Material.ENDER_CHEST, "My Orders & Stash", NamedTextColor.LIGHT_PURPLE));
        inv.setItem(43, item(Material.SPECTRAL_ARROW, "Next Page", NamedTextColor.YELLOW));
        inv.setItem(45, item(Material.ARROW, "Back to Orders", NamedTextColor.YELLOW));
        inv.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void openMine(Player p, int page) {
        List<OrderService.BuyOrder> list = orders.buyerOrders(p.getUniqueId());
        int pages = listPages(list.size()); page = clamp(page, pages);
        Holder h = new Holder(View.MINE, page, null); Inventory inv = menu(h, "My Buy Orders"); decorate(inv);
        populate(inv, h, list, page, false);
        innerNav(inv, page, pages);
        inv.setItem(40, item(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        inv.setItem(45, item(Material.ARROW, "Back to Orders", NamedTextColor.YELLOW));
        inv.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void populate(Inventory inv, Holder h, List<OrderService.BuyOrder> list, int page, boolean seller) {
        for (int i = page * LIST_SLOTS.length; i < Math.min(list.size(), (page + 1) * LIST_SLOTS.length); i++) {
            OrderService.BuyOrder o = list.get(i); int slot = LIST_SLOTS[i - page * LIST_SLOTS.length];
            List<String> lore = new ArrayList<>(List.of("Buyer: " + o.buyerName(),
                    "Progress: " + o.delivered() + "/" + o.quantity(),
                    "Price each: " + economy.format(o.pricePerItem()),
                    "Remaining escrow: " + economy.format(o.escrowRemaining()),
                    "Status: " + o.status(), "Expires in: " + remaining(o.expiresAt())));
            if (!seller) lore.add("Stash ready: " + o.stash());
            lore.add(seller ? "Click to deliver items" : "Click for order details");
            inv.setItem(slot, specifiedItem(o.requestedItem(), pretty(o.material()), statusColor(o.status()), lore.toArray(String[]::new)));
            h.ids.put(slot, o.id().toString());
        }
        if (list.isEmpty()) inv.setItem(22, item(Material.COBWEB, "No Orders Found", NamedTextColor.GRAY));
    }

    private void openConfirm(Player p, Prompt prompt) {
        BigDecimal total = prompt.price.multiply(BigDecimal.valueOf(prompt.quantity));
        Holder h = new Holder(View.CREATE_CONFIRM, 0, null); h.prompt = prompt;
        Inventory inv = menu(h, "Confirm Buy Order"); decorate(inv);
        inv.setItem(20, item(Material.LIME_CONCRETE, "Confirm Order", NamedTextColor.GREEN,
                "Move " + economy.format(total) + " into escrow"));
        inv.setItem(22, specifiedItem(prompt.item, pretty(prompt.item.getType()), NamedTextColor.GOLD,
                "Quantity: " + prompt.quantity, "Price each: " + economy.format(prompt.price),
                "Total escrow: " + economy.format(total)));
        inv.setItem(24, item(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void openDetails(Player p, UUID id, int returnPage) {
        OrderService.BuyOrder o = orders.get(id);
        if (o == null || !o.buyerId().equals(p.getUniqueId())) { openMine(p, returnPage); return; }
        Holder h = new Holder(View.DETAIL, returnPage, id); Inventory inv = menu(h, "Order Details"); decorate(inv);
        inv.setItem(22, specifiedItem(o.requestedItem(), pretty(o.material()), statusColor(o.status()),
                "Status: " + o.status(), "Progress: " + o.delivered() + "/" + o.quantity(),
                "Price each: " + economy.format(o.pricePerItem()), "Escrow remaining: " + economy.format(o.escrowRemaining()),
                "Stash ready: " + o.stash(), "Expires in: " + remaining(o.expiresAt())));
        if (o.stash() > 0) inv.setItem(20, item(Material.ENDER_CHEST, "Collect Stash", NamedTextColor.GREEN,
                "Collect " + o.stash() + " item(s)", "Overflow drops safely at your feet"));
        if (o.status() == OrderService.Status.ACTIVE) inv.setItem(24, item(Material.RED_CONCRETE,
                "Cancel & Refund", NamedTextColor.RED, "Refund: " + economy.format(o.escrowRemaining())));
        inv.setItem(45, item(Material.ARROW, "Back", NamedTextColor.YELLOW));
        inv.setItem(53, item(Material.BARRIER, "Close", NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void openDelivery(Player p, UUID id, int returnPage) {
        OrderService.BuyOrder o = orders.get(id);
        if (o == null || o.status() != OrderService.Status.ACTIVE || o.buyerId().equals(p.getUniqueId())) {
            p.sendMessage(text("That order cannot be delivered to.", NamedTextColor.RED)); openMarket(p, returnPage); return;
        }
        Holder h = new Holder(View.DELIVERY, returnPage, id); Inventory inv = menu(h, "Deliver " + pretty(o.material()));
        ItemStack border = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = 45; slot < 54; slot++) inv.setItem(slot, border);
        inv.setItem(45, item(Material.ARROW, "Cancel & Return Items", NamedTextColor.YELLOW));
        inv.setItem(48, specifiedItem(o.requestedItem(), "Exact required item", NamedTextColor.AQUA,
                "Needed: " + (o.quantity() - o.delivered()), "Payment each: " + economy.format(o.pricePerItem())));
        inv.setItem(49, item(Material.LIME_CONCRETE, "Submit Delivery", NamedTextColor.GREEN,
                "Partial fills are allowed", "Excess items are returned"));
        inv.setItem(53, item(Material.BARRIER, "Cancel & Return Items", NamedTextColor.RED));
        p.openInventory(inv);
    }

    @EventHandler public void click(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        if (h.view == View.DELIVERY) { deliveryClick(e, p, h); return; }
        e.setCancelled(true);
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) return;
        int s = e.getRawSlot();
        if (s == 53) { p.closeInventory(); return; }
        if (s == 45) { if (h.view == View.HUB) p.performCommand("menu");
            else if (h.view == View.MATERIALS || h.view == View.MARKET || h.view == View.MINE) openHub(p);
            else if (h.view == View.POTION_VARIANTS || h.view == View.ENCHANTMENTS) openMaterials(p,h.materialState);
            else if (h.view == View.ENCHANTMENT_LEVELS) openEnchantments(p,h.baseItem,h.materialState,0);
            else if (h.view == View.DETAIL) openMine(p, h.page); return; }
        if ((s == 43 && h.view == View.MARKET) || (s == 42 && (h.view == View.MATERIALS || h.view == View.MINE))) { page(p, h, h.page + 1); return; }
        if ((s == 37 && h.view == View.MARKET) || (s == 38 && (h.view == View.MATERIALS || h.view == View.MINE))) { page(p, h, h.page - 1); return; }
        switch (h.view) {
            case HUB -> { if (s == 20) openMarket(p, 0); if (s == 22) openMaterials(p, MaterialState.defaults()); if (s == 24) openMine(p, 0); }
            case MATERIALS -> {
                if (s == 39) { openMaterials(p, h.materialState.withCategory(h.materialState.category.next())); return; }
                if (s == 40) { openMaterials(p, h.materialState.withSort(h.materialState.sort.next())); return; }
                if (s == 41 && e.isRightClick()) { openMaterials(p, h.materialState.withSearch("")); return; }
                if (s == 41) {
                    materialSearchPrompts.put(p.getUniqueId(), h.materialState);
                    p.closeInventory();
                    p.sendMessage(text("Type an item name in chat, or cancel.", NamedTextColor.YELLOW));
                    return;
                }
                String name = h.ids.get(s); if (name == null) return;
                chooseVariant(p, Material.valueOf(name), h.materialState);
            }
            case MARKET -> {
                String id = h.ids.get(s);
                if (id != null) openDelivery(p, UUID.fromString(id), h.page);
                else if (s == 38) openMarket(p, h.marketState.withSort(h.marketState.sort.next()));
                else if (s == 39) openMarket(p, h.marketState.withCategory(h.marketState.category.next()));
                else if (s == 40) openMarket(p, h.marketState);
                else if (s == 41 && e.isRightClick()) openMarket(p, h.marketState.withSearch(""));
                else if (s == 41) { searchPrompts.put(p.getUniqueId(), h.marketState); p.closeInventory(); p.sendMessage(text("Type an item name in chat, or cancel.", NamedTextColor.YELLOW)); }
                else if (s == 42) openMine(p, 0);
            }
            case POTION_VARIANTS -> {
                if (s == 45) { openMaterials(p, h.materialState); return; }
                if (s == 38) { openPotionVariants(p, h.baseItem.getType(), h.materialState, h.page - 1); return; }
                if (s == 42) { openPotionVariants(p, h.baseItem.getType(), h.materialState, h.page + 1); return; }
                String typeName=h.ids.get(s); if(typeName==null)return;
                ItemStack selected=h.baseItem.clone(); PotionMeta meta=(PotionMeta)selected.getItemMeta();
                meta.setBasePotionType(PotionType.valueOf(typeName)); selected.setItemMeta(meta); beginQuantity(p,selected);
            }
            case ENCHANTMENTS -> {
                if (s == 45) { openMaterials(p, h.materialState); return; }
                if (s == 38) { openEnchantments(p,h.baseItem,h.materialState,h.page-1); return; }
                if (s == 42) { openEnchantments(p,h.baseItem,h.materialState,h.page+1); return; }
                if (s == 40) { openEnchantments(p,new ItemStack(h.baseItem.getType()),h.materialState,0); return; }
                if (s == 41) {
                    if (h.baseItem.getType() == Material.ENCHANTED_BOOK && selectedEnchantments(h.baseItem).isEmpty()) {
                        p.sendMessage(text("An enchanted book must contain at least one enchantment.", NamedTextColor.RED));
                        return;
                    }
                    beginQuantity(p,h.baseItem); return;
                }
                String key=h.ids.get(s); if(key==null)return;
                Enchantment enchantment=Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.fromString(key));
                if(enchantment!=null)openEnchantmentLevels(p,h.baseItem,h.materialState,enchantment);
            }
            case ENCHANTMENT_LEVELS -> {
                if (s == 45) { openEnchantments(p,h.baseItem,h.materialState,0); return; }
                String levelText=h.ids.get(s); if(levelText==null)return;
                ItemStack selected=h.baseItem.clone(); ItemMeta meta=selected.getItemMeta(); int level=Integer.parseInt(levelText);
                if(meta instanceof EnchantmentStorageMeta stored)stored.addStoredEnchant(h.enchantment,level,true);
                else meta.addEnchant(h.enchantment,level,true);
                selected.setItemMeta(meta); openEnchantments(p,selected,h.materialState,0);
            }
            case MINE -> { String id = h.ids.get(s); if (id != null) openDetails(p, UUID.fromString(id), h.page); }
            case CREATE_CONFIRM -> {
                if (s == 20) {
                    OrderService.CreateResult r = orders.create(p.getUniqueId(), p.getName(), h.prompt.item,
                            h.prompt.quantity, h.prompt.price);
                    p.sendMessage(text(r.success() ? "Buy order posted. Funds are now in escrow." : r.message(),
                            r.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    if (r.success()) openMine(p, 0);
                } else if (s == 24) openHub(p);
            }
            case DETAIL -> {
                if (s == 20) collect(p, h.orderId, h.page);
                if (s == 24) cancel(p, h.orderId, h.page);
            }
            default -> {}
        }
    }

    private void deliveryClick(InventoryClickEvent e, Player p, Holder h) {
        int raw = e.getRawSlot();
        OrderService.BuyOrder o = orders.get(h.orderId);
        if (o == null) { e.setCancelled(true); return; }
        if (raw >= 45 && raw < 54) {
            e.setCancelled(true);
            if (raw == 49) submit(p, h, o);
            else if (raw == 45 || raw == 53) p.closeInventory();
            return;
        }
        ItemStack moving = raw < 45 ? e.getCursor()
                : e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY ? e.getCurrentItem() : null;
        if (raw < 45 && e.getHotbarButton() >= 0) {
            moving = p.getInventory().getItem(e.getHotbarButton());
        }
        if (moving != null && !moving.getType().isAir() && !o.matches(moving)) {
            e.setCancelled(true); p.sendMessage(text("Only " + pretty(o.material()) + " can be delivered here.", NamedTextColor.RED));
        }
    }

    @EventHandler public void drag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder h) || h.view != View.DELIVERY) return;
        OrderService.BuyOrder o = orders.get(h.orderId);
        if (o == null || (e.getRawSlots().stream().anyMatch(s -> s < 45) && !o.matches(e.getOldCursor()))) {
            e.setCancelled(true);
        }
        if (e.getRawSlots().stream().anyMatch(s -> s >= 45 && s < 54)) e.setCancelled(true);
    }

    @EventHandler public void close(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder h) || h.view != View.DELIVERY
                || !(e.getPlayer() instanceof Player p)) return;
        returnGrid(p, e.getInventory(), null);
    }

    @EventHandler public void chat(AsyncChatEvent e) {
        MaterialState materialState = materialSearchPrompts.remove(e.getPlayer().getUniqueId());
        if (materialState != null) {
            e.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
            Bukkit.getScheduler().runTask(plugin, () -> openMaterials(e.getPlayer(),
                    materialState.withSearch(input.equalsIgnoreCase("cancel") ? "" : input)));
            return;
        }
        MarketState searchState = searchPrompts.remove(e.getPlayer().getUniqueId());
        if (searchState != null) {
            e.setCancelled(true);
            String input = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
            Bukkit.getScheduler().runTask(plugin, () -> openMarket(e.getPlayer(),
                    searchState.withSearch(input.equalsIgnoreCase("cancel") ? "" : input)));
            return;
        }
        Prompt prompt = prompts.remove(e.getPlayer().getUniqueId()); if (prompt == null) return;
        e.setCancelled(true); String input = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = e.getPlayer();
            if (input.equalsIgnoreCase("cancel")) { openHub(p); return; }
            try {
                if (prompt.stage == Stage.POTION) {
                    PotionType type = PotionType.valueOf(input.toUpperCase(Locale.ROOT).replace(' ', '_'));
                    ItemMeta meta = prompt.item.getItemMeta();
                    if (!(meta instanceof PotionMeta potionMeta)) throw new IllegalArgumentException();
                    potionMeta.setBasePotionType(type); prompt.item.setItemMeta(potionMeta);
                    beginQuantity(p, prompt.item);
                } else if (prompt.stage == Stage.ENCHANTMENT) {
                    if (input.equalsIgnoreCase("none")) { beginQuantity(p, prompt.item); return; }
                    Enchantment enchantment = Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft(input.toLowerCase(Locale.ROOT).replace(' ', '_')));
                    if (enchantment == null || (prompt.item.getType()!=Material.ENCHANTED_BOOK && !enchantment.canEnchantItem(prompt.item))) throw new IllegalArgumentException();
                    prompts.put(p.getUniqueId(), new Prompt(Stage.ENCHANTMENT_LEVEL, prompt.item, 0, null, enchantment));
                    p.sendMessage(text("Type the enchantment level (1-" + enchantment.getMaxLevel() + ").", NamedTextColor.YELLOW));
                } else if (prompt.stage == Stage.ENCHANTMENT_LEVEL) {
                    int level=Integer.parseInt(input); if(level<1||level>prompt.enchantment.getMaxLevel())throw new IllegalArgumentException();
                    ItemMeta meta=prompt.item.getItemMeta();
                    if(meta instanceof EnchantmentStorageMeta stored)stored.addStoredEnchant(prompt.enchantment,level,true);
                    else meta.addEnchant(prompt.enchantment,level,true);
                    prompt.item.setItemMeta(meta); beginQuantity(p,prompt.item);
                } else if (prompt.stage == Stage.QUANTITY) {
                    int amount = Integer.parseInt(input);
                    if (amount < 1) throw new NumberFormatException();
                    prompts.put(p.getUniqueId(), new Prompt(Stage.PRICE, prompt.item, amount, null, null));
                    p.sendMessage(text("Type the price per item in chat, or type cancel.", NamedTextColor.YELLOW));
                } else {
                    BigDecimal price = new BigDecimal(input);
                    if (price.signum() <= 0 || price.scale() > 2) throw new NumberFormatException();
                    openConfirm(p, new Prompt(Stage.PRICE, prompt.item, prompt.quantity, price, null));
                }
            } catch (RuntimeException ex) {
                prompts.put(p.getUniqueId(), prompt);
                p.sendMessage(text("That selection is not valid. Type cancel to return.", NamedTextColor.RED));
            }
        });
    }

    private void chooseVariant(Player p, Material material, MaterialState returnState) {
        ItemStack item=new ItemStack(material);
        if(material==Material.POTION||material==Material.SPLASH_POTION||material==Material.LINGERING_POTION){
            openPotionVariants(p,material,returnState,0);
            return;
        }
        boolean enchantable=material==Material.ENCHANTED_BOOK||Registry.ENCHANTMENT.stream().anyMatch(enchantment->isApplicable(enchantment,item));
        if(enchantable){
            openEnchantments(p,item,returnState,0);
            return;
        }
        beginQuantity(p,item);
    }

    private void openPotionVariants(Player p,Material material,MaterialState back,int requestedPage){
        List<PotionType> types=java.util.Arrays.stream(PotionType.values()).sorted(Comparator.comparing(Enum::name)).toList();
        int pages=listPages(types.size()),page=clamp(requestedPage,pages);
        Holder h=new Holder(View.POTION_VARIANTS,page,null);h.baseItem=new ItemStack(material);h.materialState=back;
        Inventory inv=menu(h,"Choose Potion Type");decorate(inv);
        for(int i=page*LIST_SLOTS.length;i<Math.min(types.size(),(page+1)*LIST_SLOTS.length);i++){
            PotionType type=types.get(i);ItemStack icon=new ItemStack(material);PotionMeta meta=(PotionMeta)icon.getItemMeta();
            meta.setBasePotionType(type);meta.displayName(text(prettyName(type.name()),NamedTextColor.LIGHT_PURPLE));icon.setItemMeta(meta);
            int slot=LIST_SLOTS[i-page*LIST_SLOTS.length];inv.setItem(slot,icon);h.ids.put(slot,type.name());
        }
        variantNavigation(inv,page,pages);p.openInventory(inv);
    }

    private void openEnchantments(Player p,ItemStack base,MaterialState back,int requestedPage){
        List<Enchantment> values=Registry.ENCHANTMENT.stream()
                .filter(e->base.getType()==Material.ENCHANTED_BOOK||isApplicable(e,base))
                .filter(e->selectedEnchantments(base).keySet().stream()
                        .allMatch(existing->existing.equals(e)||!existing.conflictsWith(e)))
                .sorted(Comparator.<Enchantment>comparingInt(this::enchantmentPriority)
                        .thenComparing(e->e.getKey().asString())).toList();
        int pages=listPages(values.size()),page=clamp(requestedPage,pages);
        Holder h=new Holder(View.ENCHANTMENTS,page,null);h.baseItem=base.clone();h.materialState=back;
        Inventory inv=menu(h,"Choose Enchantment");decorate(inv);
        for(int i=page*LIST_SLOTS.length;i<Math.min(values.size(),(page+1)*LIST_SLOTS.length);i++){
            Enchantment enchantment=values.get(i);ItemStack icon=new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta=(EnchantmentStorageMeta)icon.getItemMeta();meta.addStoredEnchant(enchantment,1,true);
            meta.displayName(text(prettyName(enchantment.getKey().getKey()),NamedTextColor.AQUA));icon.setItemMeta(meta);
            int slot=LIST_SLOTS[i-page*LIST_SLOTS.length];inv.setItem(slot,icon);h.ids.put(slot,enchantment.getKey().asString());
        }
        variantNavigation(inv,page,pages);
        inv.setItem(37,specifiedItem(base,"Current Selection",NamedTextColor.LIGHT_PURPLE,
                selectedEnchantments(base).isEmpty()?"No enchantments selected":"Click enchantments to add or change levels"));
        inv.setItem(40,item(Material.MILK_BUCKET,"Clear Enchantments",NamedTextColor.RED,
                "Remove every selected enchantment"));
        boolean invalidBook=base.getType()==Material.ENCHANTED_BOOK&&selectedEnchantments(base).isEmpty();
        inv.setItem(41,item(invalidBook?Material.RED_CONCRETE:Material.LIME_CONCRETE,
                invalidBook?"Select an Enchantment First":"Finish Enchantments",
                invalidBook?NamedTextColor.RED:NamedTextColor.GREEN,
                selectedEnchantments(base).size()+" enchantment(s) selected",
                invalidBook?"Blank enchanted books cannot be ordered":"Continue to quantity and price"));
        p.openInventory(inv);
    }

    private void openEnchantmentLevels(Player p,ItemStack base,MaterialState back,Enchantment enchantment){
        Holder h=new Holder(View.ENCHANTMENT_LEVELS,0,null);h.baseItem=base.clone();h.materialState=back;h.enchantment=enchantment;
        Inventory inv=menu(h,"Choose Enchantment Level");decorate(inv);
        int[] slots={19,20,21,22,23,24,25};
        for(int level=1;level<=Math.min(enchantment.getMaxLevel(),slots.length);level++){
            inv.setItem(slots[level-1],item(Material.EXPERIENCE_BOTTLE,"Level "+level,NamedTextColor.GREEN));
            h.ids.put(slots[level-1],String.valueOf(level));
        }
        inv.setItem(45,item(Material.ARROW,"Back to Enchantments",NamedTextColor.YELLOW));inv.setItem(53,item(Material.BARRIER,"Close",NamedTextColor.RED));
        p.openInventory(inv);
    }

    private void variantNavigation(Inventory inv,int page,int pages){
        inv.setItem(38,item(Material.SPECTRAL_ARROW,"Previous Page",NamedTextColor.YELLOW));
        inv.setItem(42,item(Material.SPECTRAL_ARROW,"Next Page",NamedTextColor.YELLOW));
        inv.setItem(45,item(Material.ARROW,"Back to Materials",NamedTextColor.YELLOW));
        inv.setItem(53,item(Material.BARRIER,"Close",NamedTextColor.RED));
    }
    private Map<Enchantment,Integer> selectedEnchantments(ItemStack item){
        ItemMeta meta=item.getItemMeta();
        return meta instanceof EnchantmentStorageMeta stored ? stored.getStoredEnchants() : item.getEnchantments();
    }
    private boolean isApplicable(Enchantment enchantment,ItemStack item){
        if(enchantment.canEnchantItem(item))return true;
        String key=enchantment.getKey().getKey(),name=item.getType().name();
        boolean miningTool=name.endsWith("_PICKAXE")||name.endsWith("_AXE")||name.endsWith("_SHOVEL")
                ||name.endsWith("_HOE")||name.equals("SHEARS");
        return miningTool&&(key.equals("silk_touch")||key.equals("fortune")
                ||key.equals("efficiency")||key.equals("unbreaking")||key.equals("mending"));
    }
    private int enchantmentPriority(Enchantment enchantment){
        return switch(enchantment.getKey().getKey()){
            case "silk_touch"->0;case "fortune"->1;case "efficiency"->2;
            case "unbreaking"->3;case "mending"->4;default->10;
        };
    }
    private static String prettyName(String value){String[] words=value.toLowerCase(Locale.ROOT).split("_");StringBuilder out=new StringBuilder();for(String word:words)out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');return out.toString().trim();}
    private void beginQuantity(Player p,ItemStack item){
        p.closeInventory();
        prompts.put(p.getUniqueId(),new Prompt(Stage.QUANTITY,item,0,null,null));
        p.sendMessage(text("Type the quantity in chat, or type cancel.",NamedTextColor.YELLOW));
    }

    private void submit(Player p, Holder h, OrderService.BuyOrder o) {
        int count = 0;
        for (int i=0;i<45;i++) { ItemStack stack=h.inventory.getItem(i); if (o.matches(stack)) count+=stack.getAmount(); }
        if (count < 1) { p.sendMessage(text("Place matching items in the grid first.", NamedTextColor.RED)); return; }
        for (int i=0;i<45;i++) h.inventory.setItem(i, null);
        OrderService.DeliveryResult r = orders.deliver(h.orderId, p.getUniqueId(), p.getName(), count);
        if (!r.success()) give(p, o.requestedItem(), count); else if (r.excess()>0) give(p, o.requestedItem(), r.excess());
        BigDecimal gross=o.pricePerItem().multiply(BigDecimal.valueOf(r.accepted())).setScale(2,java.math.RoundingMode.HALF_UP);
        BigDecimal fee=gross.subtract(r.payment());
        p.sendMessage(text(r.success() ? r.message()+" Gross: "+economy.format(gross)+", fee: "
                +economy.format(fee)+", you received: "+economy.format(r.payment())+"." : r.message(),
                r.success()?NamedTextColor.GREEN:NamedTextColor.RED));
        h.submitted = true; p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> openMarket(p, h.page));
    }

    private void collect(Player p, UUID id, int page) {
        OrderService.CollectResult r=orders.collect(id,p.getUniqueId());
        if (r.success()) { give(p,r.item(),r.amount()); p.sendMessage(text("Stash collected.",NamedTextColor.GREEN)); }
        openDetails(p,id,page);
    }
    private void cancel(Player p, UUID id, int page) {
        OrderService.ActionResult r=orders.cancel(id,p.getUniqueId());
        p.sendMessage(text(r.success() ? r.message()+" Refunded "+economy.format(r.refund())+"." : r.message(),
                r.success()?NamedTextColor.GREEN:NamedTextColor.RED)); openDetails(p,id,page);
    }
    private void returnGrid(Player p, Inventory inv, Material ignored) {
        for(int i=0;i<45;i++){ItemStack s=inv.getItem(i);if(s!=null&&!s.getType().isAir()){inv.setItem(i,null);give(p,s);}}
    }
    private void give(Player p, Material material, int amount) {
        int max=material.getMaxStackSize(); while(amount>0){int n=Math.min(max,amount);give(p,new ItemStack(material,n));amount-=n;}
    }
    private void give(Player p, ItemStack stack) {
        p.getInventory().addItem(stack).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(),left));
    }
    private void give(Player p, ItemStack template, int amount) {
        int max=template.getMaxStackSize(); while(amount>0){ItemStack stack=template.clone();stack.setAmount(Math.min(max,amount));give(p,stack);amount-=stack.getAmount();}
    }
    private void page(Player p, Holder h, int page) {
        if(h.view==View.MATERIALS)openMaterials(p,h.materialState.withPage(page)); else if(h.view==View.MARKET)openMarket(p,h.marketState.withPage(page)); else if(h.view==View.MINE)openMine(p,page);
    }
    private Inventory menu(Holder h,String title){Inventory i=Bukkit.createInventory(h,54,Component.text(title,NamedTextColor.DARK_AQUA));h.inventory=i;return i;}
    private void decorate(Inventory inv){ItemStack border=item(Material.BLACK_STAINED_GLASS_PANE," ",NamedTextColor.DARK_GRAY);ItemStack inside=item(Material.GRAY_STAINED_GLASS_PANE," ",NamedTextColor.GRAY);for(int i=0;i<54;i++){int r=i/9,c=i%9;inv.setItem(i,r==0||r==5||c==0||c==8?border:inside);}}
    private void innerNav(Inventory inv,int page,int pages){inv.setItem(38,item(Material.SPECTRAL_ARROW,"Previous Page",NamedTextColor.YELLOW));inv.setItem(42,item(Material.SPECTRAL_ARROW,"Next Page",NamedTextColor.YELLOW));}
    private void nav(Inventory inv,int page,int pages,String back){inv.setItem(45,item(Material.ARROW,back,NamedTextColor.YELLOW));inv.setItem(46,item(Material.SPECTRAL_ARROW,"Previous Page",NamedTextColor.YELLOW));inv.setItem(49,item(Material.MAP,"Page "+(page+1)+"/"+pages,NamedTextColor.AQUA));inv.setItem(52,item(Material.SPECTRAL_ARROW,"Next Page",NamedTextColor.YELLOW));inv.setItem(53,item(Material.BARRIER,"Close",NamedTextColor.RED));}
    private ItemStack item(Material m,String name,NamedTextColor color,String... lore){ItemStack s=new ItemStack(m);ItemMeta meta=s.getItemMeta();meta.displayName(text(name,color));if(lore.length>0)meta.lore(java.util.Arrays.stream(lore).map(x->text(x,NamedTextColor.GRAY)).toList());s.setItemMeta(meta);return s;}
    private ItemStack specifiedItem(ItemStack template,String name,NamedTextColor color,String... lore){ItemStack s=template.clone();s.setAmount(1);ItemMeta meta=s.getItemMeta();meta.displayName(text(name,color));List<Component> lines=new ArrayList<>(meta.lore()==null?List.of():meta.lore());for(String line:lore)lines.add(text(line,NamedTextColor.GRAY));meta.lore(lines);s.setItemMeta(meta);return s;}
    private Component text(String s,NamedTextColor c){return Component.text(s,c).decoration(TextDecoration.ITALIC,false);}
    private static int pages(int n){return Math.max(1,(n+44)/45);} private static int listPages(int n){return Math.max(1,(n+LIST_SLOTS.length-1)/LIST_SLOTS.length);} private static int clamp(int p,int pages){return Math.max(0,Math.min(p,pages-1));}
    private static String pretty(Material m){String[] words=m.name().toLowerCase(Locale.ROOT).split("_");StringBuilder b=new StringBuilder();for(String w:words)b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');return b.toString().trim();}
    private static String remaining(long at){long s=Math.max(0,Duration.between(Instant.now(),Instant.ofEpochMilli(at)).toSeconds());return (s/86400)+"d "+((s%86400)/3600)+"h";}
    private static NamedTextColor statusColor(OrderService.Status s){return switch(s){case ACTIVE->NamedTextColor.GREEN;case COMPLETED->NamedTextColor.AQUA;case EXPIRED->NamedTextColor.YELLOW;case CANCELLED->NamedTextColor.RED;};}
    private enum View{HUB,MATERIALS,POTION_VARIANTS,ENCHANTMENTS,ENCHANTMENT_LEVELS,MARKET,MINE,CREATE_CONFIRM,DETAIL,DELIVERY} private enum Stage{POTION,ENCHANTMENT,ENCHANTMENT_LEVEL,QUANTITY,PRICE}
    private enum MaterialSort {
        NAME_A_Z("Name A-Z", Comparator.comparing(Material::name)),
        NAME_Z_A("Name Z-A", Comparator.comparing(Material::name).reversed()),
        STACK_SIZE("Stack Size", Comparator.comparingInt(Material::getMaxStackSize).reversed().thenComparing(Material::name));
        final String label; final Comparator<Material> comparator;
        MaterialSort(String label, Comparator<Material> comparator){this.label=label;this.comparator=comparator;}
        MaterialSort next(){return values()[(ordinal()+1)%values().length];}
    }
    private record MaterialState(int page, MaterialSort sort, OrderCategory category, String search) {
        static MaterialState defaults(){return new MaterialState(0,MaterialSort.NAME_A_Z,OrderCategory.ALL,"");}
        MaterialState withPage(int value){return new MaterialState(Math.max(0,value),sort,category,search);}
        MaterialState withSort(MaterialSort value){return new MaterialState(0,value,category,search);}
        MaterialState withCategory(OrderCategory value){return new MaterialState(0,sort,value,search);}
        MaterialState withSearch(String value){return new MaterialState(0,sort,category,value);}
    }
    private enum OrderSort {
        NEWEST("Newest", Comparator.comparingLong(OrderService.BuyOrder::createdAt).reversed()),
        PRICE_LOW("Price: Low to High", Comparator.comparing(OrderService.BuyOrder::pricePerItem)),
        PRICE_HIGH("Price: High to Low", Comparator.comparing(OrderService.BuyOrder::pricePerItem).reversed());
        final String label; final Comparator<OrderService.BuyOrder> comparator;
        OrderSort(String label,Comparator<OrderService.BuyOrder> comparator){this.label=label;this.comparator=comparator;}
        OrderSort next(){return values()[(ordinal()+1)%values().length];}
    }
    private enum OrderCategory {
        ALL("All"), WEAPONS_ARMOR("Weapons & Armor"), TOOLS("Tools"), BLOCKS("Blocks"),
        MAGIC("Enchanted Books & Potions"), CONSUMABLES("Food"), MISC("Miscellaneous");
        final String label; OrderCategory(String label){this.label=label;} OrderCategory next(){return values()[(ordinal()+1)%values().length];}
        boolean matches(Material material){if(this==ALL)return true;String n=material.name();boolean armor=n.endsWith("_SWORD")||n.endsWith("_HELMET")||n.endsWith("_CHESTPLATE")||n.endsWith("_LEGGINGS")||n.endsWith("_BOOTS")||n.equals("BOW")||n.equals("CROSSBOW")||n.equals("TRIDENT")||n.equals("SHIELD");boolean tools=n.endsWith("_PICKAXE")||n.endsWith("_AXE")||n.endsWith("_SHOVEL")||n.endsWith("_HOE")||n.equals("SHEARS")||n.equals("FISHING_ROD");boolean magic=n.equals("ENCHANTED_BOOK")||n.contains("POTION");boolean food=material.isEdible()||n.equals("MILK_BUCKET");return switch(this){case WEAPONS_ARMOR->armor;case TOOLS->tools&&!armor;case BLOCKS->material.isBlock();case MAGIC->magic;case CONSUMABLES->food;case MISC->!armor&&!tools&&!material.isBlock()&&!magic&&!food;default->true;};}
    }
    private record MarketState(int page,OrderSort sort,OrderCategory category,String search){
        MarketState withPage(int v){return new MarketState(Math.max(0,v),sort,category,search);}
        MarketState withSort(OrderSort v){return new MarketState(0,v,category,search);}
        MarketState withCategory(OrderCategory v){return new MarketState(0,sort,v,search);}
        MarketState withSearch(String v){return new MarketState(0,sort,category,v);}
    }
    private record Prompt(Stage stage,ItemStack item,int quantity,BigDecimal price,Enchantment enchantment){}
    private static final class Holder implements InventoryHolder {
        final View view;final int page;final UUID orderId;final Map<Integer,String> ids=new HashMap<>();Inventory inventory;Prompt prompt;boolean submitted;MaterialState materialState;MarketState marketState;ItemStack baseItem;Enchantment enchantment;
        Holder(View view,int page,UUID orderId){this.view=view;this.page=page;this.orderId=orderId;}
        @Override public Inventory getInventory(){return inventory;}
    }
}

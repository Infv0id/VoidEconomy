package com.voidplugin.economy;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

final class AuctionManager implements Listener, CommandExecutor {
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int SORT = 46;
    private static final int FILTER = 47;
    private static final int REFRESH = 48;
    private static final int SEARCH = 49;
    private static final int MY_LISTINGS = 50;
    private static final int CREATE = 51;
    private static final int NEXT = 52;
    private static final int CLOSE = 53;

    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final AuctionService auctions;
    private final SellService sell;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    AuctionManager(VoidEconomyPlugin plugin, EconomyService economy, AuctionService auctions, SellService sell) {
        this.plugin = plugin;
        this.economy = economy;
        this.auctions = auctions;
        this.sell = sell;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("players-only"));
            return true;
        }
        if (!player.hasPermission("voideconomy.auction")) {
            player.sendMessage(plugin.message("no-permission"));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            BigDecimal price = parsePrice(args[1]);
            if (price == null) player.sendMessage(text("Invalid auction price.", NamedTextColor.RED));
            else createFromHand(player, price);
            return true;
        }
        openBrowser(player, BrowserState.defaults());
        return true;
    }

    private void openBrowser(Player player, BrowserState state) {
        List<AuctionService.Listing> results = filteredActive(state);
        int pages = Math.max(1, (results.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(state.page(), pages - 1));
        state = state.withPage(page);
        AuctionHolder holder = new AuctionHolder(View.BROWSER, state, null);
        Inventory menu = Bukkit.createInventory(holder, 54,
                Component.text("Auction House • " + (page + 1) + "/" + pages, NamedTextColor.DARK_AQUA));
        holder.inventory = menu;
        holder.visibleIds = populateListings(menu, results, page, player.getUniqueId(), false);
        menu.setItem(PREVIOUS, button(Material.ARROW, "Previous Page", NamedTextColor.YELLOW));
        menu.setItem(SORT, button(Material.HOPPER, "Sort: " + state.sort().label, NamedTextColor.GOLD,
                "Click to change price ordering"));
        menu.setItem(FILTER, button(Material.COMPASS, "Category: " + state.category().label, NamedTextColor.AQUA,
                "Click to cycle item categories"));
        menu.setItem(REFRESH, button(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        menu.setItem(SEARCH, button(Material.OAK_SIGN, state.search().isBlank() ? "Search" : "Search: " + state.search(),
                NamedTextColor.YELLOW, "Click to enter text", "Right-click to clear"));
        menu.setItem(MY_LISTINGS, button(Material.CHEST, "My Listings", NamedTextColor.LIGHT_PURPLE,
                "Active: " + auctions.activeCount(player.getUniqueId())));
        menu.setItem(CREATE, button(Material.LIME_DYE, "Create Listing", NamedTextColor.GREEN,
                "Hold the item you want to sell"));
        menu.setItem(NEXT, button(Material.ARROW, "Next Page", NamedTextColor.YELLOW));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    private void openPersonal(Player player, PersonalState state) {
        List<AuctionService.Listing> all = auctions.sellerListings(player.getUniqueId());
        PersonalFilter selectedStatus = state.status();
        List<AuctionService.Listing> results = all.stream()
                .filter(listing -> selectedStatus == PersonalFilter.ALL
                        || listing.status().name().equals(selectedStatus.name()))
                .toList();
        int pages = Math.max(1, (results.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(state.page(), pages - 1));
        state = state.withPage(page);
        AuctionHolder holder = new AuctionHolder(View.PERSONAL, null, state);
        Inventory menu = Bukkit.createInventory(holder, 54,
                Component.text("My Auctions • " + state.status().label, NamedTextColor.DARK_PURPLE));
        holder.inventory = menu;
        holder.visibleIds = populateListings(menu, results, page, player.getUniqueId(), true);
        menu.setItem(PREVIOUS, button(Material.ARROW, "Previous Page", NamedTextColor.YELLOW));
        menu.setItem(47, button(Material.COMPASS, "Status: " + state.status().label, NamedTextColor.AQUA,
                "Active, Sold, Expired, or All"));
        menu.setItem(48, button(Material.CLOCK, "Refresh", NamedTextColor.GREEN));
        menu.setItem(49, button(Material.ARROW, "Back to Auction House", NamedTextColor.YELLOW));
        menu.setItem(CREATE, button(Material.LIME_DYE, "Create Listing", NamedTextColor.GREEN));
        menu.setItem(NEXT, button(Material.ARROW, "Next Page", NamedTextColor.YELLOW));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    private Map<Integer, UUID> populateListings(Inventory menu, List<AuctionService.Listing> results,
                                                int page, UUID viewerId, boolean personal) {
        Map<Integer, UUID> visible = new HashMap<>();
        int start = page * PAGE_SIZE;
        int end = Math.min(results.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int slot = index - start;
            AuctionService.Listing listing = results.get(index);
            menu.setItem(slot, listingIcon(listing, viewerId, personal));
            visible.put(slot, listing.id());
        }
        if (visible.isEmpty()) menu.setItem(22, button(Material.COBWEB, "No Listings Found", NamedTextColor.GRAY,
                "Try another filter or create a listing"));
        return visible;
    }

    private ItemStack listingIcon(AuctionService.Listing listing, UUID viewerId, boolean personal) {
        ItemStack icon = listing.item().clone();
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(text("Price: " + economy.format(listing.price()), NamedTextColor.GOLD));
        lore.add(text("Seller: " + listing.sellerName(), NamedTextColor.GRAY));
        lore.add(text("Status: " + statusLabel(listing), statusColor(listing)));
        if (listing.status() == AuctionService.Status.ACTIVE)
            lore.add(text("Expires in: " + remaining(listing.expiresAt()), NamedTextColor.YELLOW));
        if (personal) {
            String action = switch (listing.status()) {
                case ACTIVE -> "Click to manage or cancel";
                case SOLD -> listing.valueCollected() ? "Proceeds already collected" : "Click to collect proceeds";
                case EXPIRED -> listing.valueCollected() ? "Item already collected" : "Click to collect item";
                case CANCELLED -> "";
            };
            if (!action.isBlank()) lore.add(text(action, NamedTextColor.AQUA));
        } else if (!listing.sellerId().equals(viewerId)) {
            lore.add(text("Click to purchase", NamedTextColor.GREEN));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private void openBuyConfirmation(Player player, AuctionService.Listing listing, BrowserState returnState) {
        AuctionHolder holder = new AuctionHolder(View.BUY_CONFIRM, returnState, null);
        holder.listingId = listing.id();
        Inventory menu = Bukkit.createInventory(holder, 54, Component.text("Confirm Auction Purchase", NamedTextColor.DARK_GREEN));
        holder.inventory = menu;
        fill(menu);
        menu.setItem(20, button(Material.LIME_CONCRETE, "Purchase", NamedTextColor.GREEN,
                "Pay " + economy.format(listing.price())));
        menu.setItem(22, listingIcon(listing, player.getUniqueId(), false));
        menu.setItem(24, button(Material.RED_CONCRETE, "Cancel", NamedTextColor.RED));
        menu.setItem(45, button(Material.ARROW, "Back", NamedTextColor.YELLOW));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    private void openManage(Player player, AuctionService.Listing listing, PersonalState returnState) {
        AuctionHolder holder = new AuctionHolder(View.MANAGE, null, returnState);
        holder.listingId = listing.id();
        Inventory menu = Bukkit.createInventory(holder, 54, Component.text("Manage Auction", NamedTextColor.DARK_PURPLE));
        holder.inventory = menu;
        fill(menu);
        menu.setItem(22, listingIcon(listing, player.getUniqueId(), true));
        if (listing.status() == AuctionService.Status.ACTIVE)
            menu.setItem(20, button(Material.RED_CONCRETE, "Cancel Listing", NamedTextColor.RED,
                    "The item is returned immediately"));
        else if (listing.status() == AuctionService.Status.SOLD && !listing.valueCollected())
            menu.setItem(20, button(Material.GOLD_INGOT, "Collect " + economy.format(listing.price()),
                    NamedTextColor.GOLD, "Deposit proceeds into your balance"));
        else if (listing.status() == AuctionService.Status.EXPIRED && !listing.valueCollected())
            menu.setItem(20, button(Material.CHEST, "Collect Expired Item", NamedTextColor.YELLOW));
        menu.setItem(45, button(Material.ARROW, "Back to My Listings", NamedTextColor.YELLOW));
        menu.setItem(CLOSE, button(Material.BARRIER, "Close", NamedTextColor.RED));
        player.openInventory(menu);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof AuctionHolder holder)) return;
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isShiftClick() || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!event.getClick().isLeftClick() && !event.getClick().isRightClick()) return;
        int slot = event.getRawSlot();
        if (slot == CLOSE) { player.closeInventory(); return; }
        switch (holder.view) {
            case BROWSER -> clickBrowser(player, holder, slot, event.isRightClick());
            case PERSONAL -> clickPersonal(player, holder, slot);
            case BUY_CONFIRM -> clickBuy(player, holder, slot);
            case MANAGE -> clickManage(player, holder, slot);
        }
    }

    private void clickBrowser(Player player, AuctionHolder holder, int slot, boolean rightClick) {
        BrowserState state = holder.browser;
        UUID id = holder.visibleIds.get(slot);
        if (id != null) {
            AuctionService.Listing listing = auctions.get(id);
            if (listing == null || listing.status() != AuctionService.Status.ACTIVE) {
                player.sendMessage(text("That listing is no longer available.", NamedTextColor.RED));
                openBrowser(player, state);
            } else if (listing.sellerId().equals(player.getUniqueId())) {
                openManage(player, listing, PersonalState.defaults());
            } else openBuyConfirmation(player, listing, state);
        } else if (slot == PREVIOUS) openBrowser(player, state.withPage(state.page() - 1));
        else if (slot == NEXT) openBrowser(player, state.withPage(state.page() + 1));
        else if (slot == SORT) openBrowser(player, state.withSort(state.sort().next()));
        else if (slot == FILTER) openBrowser(player, state.withCategory(state.category().next()));
        else if (slot == REFRESH) openBrowser(player, state);
        else if (slot == SEARCH) {
            if (rightClick) openBrowser(player, state.withSearch(""));
            else prompt(player, PromptType.SEARCH, state);
        } else if (slot == MY_LISTINGS) openPersonal(player, PersonalState.defaults());
        else if (slot == CREATE) prompt(player, PromptType.LIST_PRICE, state);
    }

    private void clickPersonal(Player player, AuctionHolder holder, int slot) {
        PersonalState state = holder.personal;
        UUID id = holder.visibleIds.get(slot);
        if (id != null) {
            AuctionService.Listing listing = auctions.get(id);
            if (listing != null) openManage(player, listing, state);
        } else if (slot == PREVIOUS) openPersonal(player, state.withPage(state.page() - 1));
        else if (slot == NEXT) openPersonal(player, state.withPage(state.page() + 1));
        else if (slot == 47) openPersonal(player, state.withStatus(state.status().next()));
        else if (slot == 48) openPersonal(player, state);
        else if (slot == 49) openBrowser(player, BrowserState.defaults());
        else if (slot == CREATE) prompt(player, PromptType.LIST_PRICE, BrowserState.defaults());
    }

    private void clickBuy(Player player, AuctionHolder holder, int slot) {
        if (slot == 45 || slot == 24) { openBrowser(player, holder.browser); return; }
        if (slot != 20) return;
        AuctionService.PurchaseResult result = auctions.purchase(holder.listingId, player.getUniqueId(), player.getName());
        switch (result.result()) {
            case SUCCESS -> {
                give(player, result.item());
                player.sendMessage(text("Auction purchase completed.", NamedTextColor.GREEN));
            }
            case INSUFFICIENT_FUNDS -> player.sendMessage(text("You do not have enough money.", NamedTextColor.RED));
            case OWN_LISTING -> player.sendMessage(text("You cannot purchase your own listing.", NamedTextColor.RED));
            case UNAVAILABLE -> player.sendMessage(text("That listing is no longer available.", NamedTextColor.RED));
        }
        openBrowser(player, holder.browser);
    }

    private void clickManage(Player player, AuctionHolder holder, int slot) {
        if (slot == 45) { openPersonal(player, holder.personal); return; }
        if (slot != 20) return;
        AuctionService.Listing listing = auctions.get(holder.listingId);
        if (listing == null || !listing.sellerId().equals(player.getUniqueId())) {
            openPersonal(player, holder.personal);
            return;
        }
        if (listing.status() == AuctionService.Status.ACTIVE) {
            ItemStack returned = auctions.cancel(listing.id(), player.getUniqueId());
            if (returned != null) {
                give(player, returned);
                player.sendMessage(text("Listing cancelled and item returned.", NamedTextColor.GREEN));
            }
        } else {
            AuctionService.CollectResult result = auctions.collect(listing.id(), player.getUniqueId());
            if (result.money() != null)
                player.sendMessage(text("Collected " + economy.format(result.money()) + " in auction proceeds.", NamedTextColor.GREEN));
            if (result.item() != null) {
                give(player, result.item());
                player.sendMessage(text("Your expired item was returned.", NamedTextColor.GREEN));
            }
        }
        openPersonal(player, holder.personal);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof AuctionHolder
                && event.getRawSlots().stream().anyMatch(slot -> slot < 54)) {
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
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            if (prompt.type() == PromptType.SEARCH) openBrowser(event.getPlayer(), prompt.state().withSearch(input).withPage(0));
            else {
                BigDecimal price = parsePrice(input);
                if (price == null) {
                    event.getPlayer().sendMessage(text("Invalid price. Listing was not created.", NamedTextColor.RED));
                    openBrowser(event.getPlayer(), prompt.state());
                } else createFromHand(event.getPlayer(), price);
            }
        });
    }

    private void prompt(Player player, PromptType type, BrowserState state) {
        if (type == PromptType.LIST_PRICE && !player.hasPermission("voideconomy.auction.sell")) {
            player.sendMessage(plugin.message("no-permission"));
            return;
        }
        prompts.put(player.getUniqueId(), new Prompt(type, state));
        player.closeInventory();
        player.sendMessage(text(type == PromptType.SEARCH
                ? "Type an item name to search for."
                : "Hold the item you want to sell and type its price.", NamedTextColor.YELLOW));
        player.sendMessage(text("Your next chat message will not be public.", NamedTextColor.GRAY));
    }

    private void createFromHand(Player player, BigDecimal price) {
        if (!player.hasPermission("voideconomy.auction.sell")) {
            player.sendMessage(plugin.message("no-permission"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(text("Hold the item you want to list in your main hand.", NamedTextColor.RED));
            openBrowser(player, BrowserState.defaults());
            return;
        }
        BigDecimal sellFloor=sell.minimumAuctionPrice(held);
        if(price.compareTo(sellFloor)<0){
            player.sendMessage(text("That stack is accepted by /sell and cannot be listed below its maximum server-market value of "
                    +economy.format(sellFloor)+".",NamedTextColor.RED));
            openBrowser(player,BrowserState.defaults());
            return;
        }
        ItemStack escrow = held.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        AuctionService.Listing listing = auctions.create(player.getUniqueId(), player.getName(), escrow, price);
        if (listing == null) {
            give(player, escrow);
            player.sendMessage(text("You have reached the maximum number of active listings.", NamedTextColor.RED));
        } else {
            BigDecimal fee=price.multiply(BigDecimal.valueOf(plugin.getConfig().getDouble("marketplace.transaction-fee-rate",.02))).setScale(2,java.math.RoundingMode.HALF_UP);
            player.sendMessage(text("Listed " + friendlyName(escrow) + " for " + economy.format(price)
                    + ". Fee on sale: " + economy.format(fee) + "; you receive: "
                    + economy.format(price.subtract(fee)) + ".", NamedTextColor.GREEN));
        }
        openPersonal(player, PersonalState.defaults());
    }

    private List<AuctionService.Listing> filteredActive(BrowserState state) {
        Comparator<AuctionService.Listing> comparator = switch (state.sort()) {
            case NEWEST -> Comparator.comparingLong(AuctionService.Listing::createdAt).reversed();
            case PRICE_LOW -> Comparator.comparing(AuctionService.Listing::price);
            case PRICE_HIGH -> Comparator.comparing(AuctionService.Listing::price).reversed();
        };
        String query = state.search().toLowerCase(Locale.ROOT);
        return auctions.activeListings().stream()
                .filter(listing -> state.category().matches(listing.item()))
                .filter(listing -> query.isBlank() || searchableName(listing.item()).contains(query)
                        || listing.sellerName().toLowerCase(Locale.ROOT).contains(query))
                .sorted(comparator)
                .toList();
    }

    private BigDecimal parsePrice(String value) {
        try {
            BigDecimal price = new BigDecimal(value);
            BigDecimal minimum = BigDecimal.valueOf(plugin.getConfig().getDouble("auction.minimum-price", 1));
            BigDecimal maximum = BigDecimal.valueOf(plugin.getConfig().getDouble("auction.maximum-price", 1_000_000_000));
            return price.signum() > 0 && price.scale() <= 2
                    && price.compareTo(minimum) >= 0 && price.compareTo(maximum) <= 0 ? price : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void fill(Inventory menu) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = 0; slot < menu.getSize(); slot++) menu.setItem(slot, filler);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name, color));
        if (lore.length > 0) meta.lore(java.util.Arrays.stream(lore).map(line -> text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private String searchableName(ItemStack item) {
        StringBuilder name = new StringBuilder(item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            name.append(' ').append(PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).toLowerCase(Locale.ROOT));
        return name.toString();
    }

    private String friendlyName(ItemStack item) {
        return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String remaining(long expiresAt) {
        Duration duration = Duration.between(Instant.now(), Instant.ofEpochMilli(expiresAt));
        if (duration.isNegative()) return "Expired";
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        return hours + "h " + minutes + "m";
    }

    private String statusLabel(AuctionService.Listing listing) {
        if (listing.valueCollected() && listing.status() == AuctionService.Status.SOLD) return "SOLD • COLLECTED";
        if (listing.valueCollected() && listing.status() == AuctionService.Status.EXPIRED) return "EXPIRED • RETURNED";
        return listing.status().name();
    }

    private NamedTextColor statusColor(AuctionService.Listing listing) {
        return switch (listing.status()) {
            case ACTIVE -> NamedTextColor.GREEN;
            case SOLD -> NamedTextColor.GOLD;
            case EXPIRED -> NamedTextColor.RED;
            case CANCELLED -> NamedTextColor.GRAY;
        };
    }

    private enum View { BROWSER, PERSONAL, BUY_CONFIRM, MANAGE }
    private enum PromptType { SEARCH, LIST_PRICE }
    private enum Sort {
        NEWEST("Newest"), PRICE_LOW("Price: Low to High"), PRICE_HIGH("Price: High to Low");
        private final String label;
        Sort(String label) { this.label = label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }
    private enum Category {
        ALL("All"), WEAPONS_ARMOR("Weapons & Armor"), TOOLS("Tools"),
        BLOCKS("Blocks"), CONSUMABLES("Food & Potions"), MISC("Miscellaneous");
        private final String label;
        Category(String label) { this.label = label; }
        Category next() { return values()[(ordinal() + 1) % values().length]; }
        boolean matches(ItemStack item) {
            if (this == ALL) return true;
            String name = item.getType().name();
            boolean weaponArmor = name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("BOW")
                    || name.equals("CROSSBOW") || name.equals("TRIDENT") || name.equals("SHIELD");
            boolean tools = name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                    || name.endsWith("_HOE") || name.equals("SHEARS") || name.equals("FISHING_ROD");
            boolean consumable = item.getType().isEdible() || name.contains("POTION") || name.equals("MILK_BUCKET");
            return switch (this) {
                case WEAPONS_ARMOR -> weaponArmor;
                case TOOLS -> tools && !weaponArmor;
                case BLOCKS -> item.getType().isBlock();
                case CONSUMABLES -> consumable;
                case MISC -> !weaponArmor && !tools && !item.getType().isBlock() && !consumable;
                default -> true;
            };
        }
    }
    private enum PersonalFilter {
        ACTIVE("Active"), SOLD("Sold"), EXPIRED("Expired"), ALL("All");
        private final String label;
        PersonalFilter(String label) { this.label = label; }
        PersonalFilter next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private record BrowserState(int page, Sort sort, Category category, String search) {
        static BrowserState defaults() { return new BrowserState(0, Sort.NEWEST, Category.ALL, ""); }
        BrowserState withPage(int value) { return new BrowserState(Math.max(0, value), sort, category, search); }
        BrowserState withSort(Sort value) { return new BrowserState(0, value, category, search); }
        BrowserState withCategory(Category value) { return new BrowserState(0, sort, value, search); }
        BrowserState withSearch(String value) { return new BrowserState(0, sort, category, value); }
    }
    private record PersonalState(int page, PersonalFilter status) {
        static PersonalState defaults() { return new PersonalState(0, PersonalFilter.ACTIVE); }
        PersonalState withPage(int value) { return new PersonalState(Math.max(0, value), status); }
        PersonalState withStatus(PersonalFilter value) { return new PersonalState(0, value); }
    }
    private record Prompt(PromptType type, BrowserState state) { }

    private static final class AuctionHolder implements InventoryHolder {
        private final View view;
        private final BrowserState browser;
        private final PersonalState personal;
        private Inventory inventory;
        private UUID listingId;
        private Map<Integer, UUID> visibleIds = Map.of();
        private AuctionHolder(View view, BrowserState browser, PersonalState personal) {
            this.view = view;
            this.browser = browser;
            this.personal = personal;
        }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}

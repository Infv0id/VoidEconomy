package com.voidplugin.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Set;

/**
 * Creates and validates physical currency notes. The persistent ID, not the
 * display name, determines whether an item is genuine.
 */
public final class BankNoteService {
    public static final Set<Integer> DENOMINATIONS = Set.of(1, 5, 10, 50, 100, 500, 1000);
    private static final int FORMAT_VERSION = 1;

    private final NamespacedKey itemIdKey;
    private final NamespacedKey valueKey;
    private final NamespacedKey versionKey;

    BankNoteService(VoidEconomyPlugin plugin) {
        itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        valueKey = new NamespacedKey(plugin, "bank_note_value");
        versionKey = new NamespacedKey(plugin, "bank_note_version");
    }

    public ItemStack createNote(int denomination, int amount) {
        if (!DENOMINATIONS.contains(denomination))
            throw new IllegalArgumentException("Unsupported bank-note denomination: " + denomination);
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Bank-note stack must be between 1 and 64");

        ItemStack note = new ItemStack(Material.MAP, amount);
        ItemMeta meta = note.getItemMeta();
        meta.displayName(text("$" + denomination + " Bank Note", NamedTextColor.GREEN));
        meta.lore(List.of(
                text("Official VoidEconomy currency", NamedTextColor.GRAY),
                text("Value per note: $" + denomination, NamedTextColor.GOLD),
                text("Right-click to deposit one note", NamedTextColor.YELLOW),
                text("Sneak-right-click to deposit the stack", NamedTextColor.AQUA),
                text("Custom ID: void_economy:bank_note_" + denomination, NamedTextColor.DARK_GRAY)
        ));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(itemIdKey, PersistentDataType.STRING, "bank_note_" + denomination);
        data.set(valueKey, PersistentDataType.INTEGER, denomination);
        data.set(versionKey, PersistentDataType.INTEGER, FORMAT_VERSION);
        note.setItemMeta(meta);
        return note;
    }

    public boolean isBankNote(ItemStack item) {
        return getDenomination(item) != null;
    }

    public Integer getDenomination(ItemStack item) {
        if (item == null || (item.getType() != Material.MAP && item.getType() != Material.FILLED_MAP
                && item.getType() != Material.PAPER)
                || !item.hasItemMeta()) return null;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        Integer value = data.get(valueKey, PersistentDataType.INTEGER);
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        String itemId = data.get(itemIdKey, PersistentDataType.STRING);
        if (value == null || version == null || version != FORMAT_VERSION || itemId == null) return null;
        return DENOMINATIONS.contains(value) && itemId.equals("bank_note_" + value) ? value : null;
    }

    public long stackValue(ItemStack item) {
        Integer denomination = getDenomination(item);
        return denomination == null ? 0L : (long) denomination * item.getAmount();
    }

    private Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
}

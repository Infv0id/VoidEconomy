package com.voidplugin.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;

final class BankNoteListener implements Listener {
    private final EconomyService economy;
    private final BankNoteService bankNotes;

    BankNoteListener(EconomyService economy, BankNoteService bankNotes) {
        this.economy = economy;
        this.bankNotes = bankNotes;
    }

    @EventHandler
    public void onUseBankNote(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        Integer denomination = bankNotes.getDenomination(held);
        if (denomination == null) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Player player = event.getPlayer();
        int noteCount = player.isSneaking() ? held.getAmount() : 1;
        BigDecimal total = BigDecimal.valueOf((long) denomination * noteCount);

        int remaining = held.getAmount() - noteCount;
        if (remaining <= 0) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else held.setAmount(remaining);

        economy.deposit(player.getUniqueId(), total);
        economy.record("BANK_NOTE_DEPOSIT", player.getUniqueId(), player.getName(),
                player.getUniqueId(), player.getName(), total);
        economy.save();

        player.sendActionBar(Component.text("Deposited " + economy.format(total)
                + " from " + noteCount + " bank note" + (noteCount == 1 ? "" : "s") + ".",
                NamedTextColor.GREEN));
    }
}

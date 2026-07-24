package com.voidplugin.economy;

import org.bukkit.ChatColor;

final class Text {
    private Text() {}
    @SuppressWarnings("deprecation")
    static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}

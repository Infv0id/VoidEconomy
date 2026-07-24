package com.voidplugin.economy;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class SellManager implements Listener, CommandExecutor, TabCompleter {
    private static final int[] SLOTS={10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private final VoidEconomyPlugin plugin;private final EconomyService economy;private final SellService sell;
    private final Map<UUID,Pending> prompts=new ConcurrentHashMap<>();
    SellManager(VoidEconomyPlugin plugin,EconomyService economy,SellService sell){
        this.plugin=plugin;this.economy=economy;this.sell=sell;
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(Player player:Bukkit.getOnlinePlayers()){
                if(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder holder&&holder.quote==null)
                    refreshTimers(holder);
            }
        },20L,20L);
    }
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage(plugin.message("players-only"));return true;}
        if(!p.hasPermission("voideconomy.sell")){p.sendMessage(plugin.message("no-permission"));return true;}
        if(!plugin.getConfig().getBoolean("sell.enabled",true)){p.sendMessage(text("The server sell market is disabled.",NamedTextColor.RED));return true;}
        if(args.length==0){open(p,0,Sort.HIGH);return true;}
        switch(args[0].toLowerCase()){
            case "hand"->{if(!p.hasPermission("voideconomy.sell.hand"))return deny(p);ItemStack hand=p.getInventory().getItemInMainHand();sellNow(p,hand.getType(),hand.getAmount());}
            case "all"->{if(!p.hasPermission("voideconomy.sell.all"))return deny(p);for(SellService.Market m:sell.markets()){int n=sell.countValid(p,m.material);if(n>0)sellNow(p,m.material,n);}}
            case "price"->{if(!p.hasPermission("voideconomy.sell.prices"))return deny(p);Material m=args.length>1?Material.matchMaterial(args[1]):p.getInventory().getItemInMainHand().getType();showPrice(p,m);}
            case "prices"->open(p,0,Sort.HIGH);
            default->open(p,0,Sort.HIGH);
        }return true;
    }
    private boolean deny(Player p){p.sendMessage(plugin.message("no-permission"));return true;}
    private void open(Player p,int requested,Sort sort){List<SellService.Market> list=new ArrayList<>(sell.markets());list.sort(sort.comparator);
        int pages=Math.max(1,(list.size()+SLOTS.length-1)/SLOTS.length),page=Math.max(0,Math.min(requested,pages-1));
        Holder h=new Holder(page,sort,null,null);Inventory inv=Bukkit.createInventory(h,54,Component.text("Floating Sell Market",NamedTextColor.DARK_GREEN));h.inventory=inv;fill(inv);
        for(int i=page*SLOTS.length;i<Math.min(list.size(),(page+1)*SLOTS.length);i++){SellService.Market m=list.get(i);int slot=SLOTS[i-page*SLOTS.length];
            inv.setItem(slot,marketItem(m));h.materials.put(slot,m.material);}
        inv.setItem(37,item(Material.SPECTRAL_ARROW,"Previous",NamedTextColor.YELLOW));inv.setItem(38,item(Material.CHEST,"Sell All",NamedTextColor.GREEN));
        inv.setItem(40,item(Material.HOPPER,"Sort: "+sort.label,NamedTextColor.GOLD));inv.setItem(42,item(Material.IRON_INGOT,"Sell Hand",NamedTextColor.AQUA));
        inv.setItem(43,item(Material.SPECTRAL_ARROW,"Next",NamedTextColor.YELLOW));inv.setItem(45,item(Material.ARROW,"Back to Hub",NamedTextColor.YELLOW));inv.setItem(53,item(Material.BARRIER,"Close",NamedTextColor.RED));p.openInventory(inv);}
    private void openConfirm(Player p,Material m,int amount,int page,Sort sort){SellService.Quote q=sell.quote(m,amount);if(q==null){p.sendMessage(text("That market is unavailable.",NamedTextColor.RED));return;}
        Holder h=new Holder(page,sort,m,q);Inventory inv=Bukkit.createInventory(h,54,Component.text("Confirm Server Sale",NamedTextColor.DARK_GREEN));h.inventory=inv;fill(inv);
        inv.setItem(20,item(Material.LIME_CONCRETE,"Confirm Sale",NamedTextColor.GREEN,"Items are removed only after final validation"));
        inv.setItem(22,item(m,"Sell "+amount+" "+pretty(m),NamedTextColor.GOLD,"Starting: "+economy.format(q.starting()),"Ending: "+economy.format(q.ending()),
                "Average: "+economy.format(q.average()),"Total: "+economy.format(q.total())));
        inv.setItem(24,item(Material.RED_CONCRETE,"Cancel",NamedTextColor.RED));inv.setItem(45,item(Material.ARROW,"Back",NamedTextColor.YELLOW));inv.setItem(53,item(Material.BARRIER,"Close",NamedTextColor.RED));p.openInventory(inv);}
    @EventHandler public void click(InventoryClickEvent e){if(!(e.getInventory().getHolder(false) instanceof Holder h)||!(e.getWhoClicked() instanceof Player p))return;e.setCancelled(true);
        int s=e.getRawSlot();if(s==53){p.closeInventory();return;}if(h.quote!=null){if(s==20){p.closeInventory();sellNow(p,h.material,h.quote.quantity());}else if(s==24||s==45)open(p,h.page,h.sort);return;}
        if(s==45){p.performCommand("menu");return;}if(s==37){open(p,h.page-1,h.sort);return;}if(s==43){open(p,h.page+1,h.sort);return;}
        if(s==40){open(p,0,h.sort.next());return;}if(s==38){p.performCommand("sell all");return;}if(s==42){p.performCommand("sell hand");return;}
        Material material=h.materials.get(s);if(material!=null){int count=sell.countValid(p,material);if(count<1){p.sendMessage(text("You have no plain accepted "+pretty(material)+".",NamedTextColor.RED));return;}
            prompts.put(p.getUniqueId(),new Pending(material,h.page,h.sort,count));p.closeInventory();p.sendMessage(text("Type the quantity to sell (1-"+count+"), all, or cancel.",NamedTextColor.YELLOW));}}
    @EventHandler public void drag(InventoryDragEvent e){if(e.getInventory().getHolder(false) instanceof Holder)e.setCancelled(true);}
    @EventHandler public void chat(AsyncChatEvent e){Pending pending=prompts.remove(e.getPlayer().getUniqueId());if(pending==null)return;e.setCancelled(true);
        String input=PlainTextComponentSerializer.plainText().serialize(e.message()).trim();Bukkit.getScheduler().runTask(plugin,()->{
            if(input.equalsIgnoreCase("cancel")){open(e.getPlayer(),pending.page,pending.sort);return;}
            try{int quantity=input.equalsIgnoreCase("all")?pending.maximum:Integer.parseInt(input);if(quantity<1||quantity>pending.maximum)throw new NumberFormatException();
                openConfirm(e.getPlayer(),pending.material,quantity,pending.page,pending.sort);}
            catch(NumberFormatException exception){prompts.put(e.getPlayer().getUniqueId(),pending);e.getPlayer().sendMessage(text("Enter 1-"+pending.maximum+", all, or cancel.",NamedTextColor.RED));}});}
    private void sellNow(Player p,Material m,int amount){SellService.SaleResult r=sell.sell(p,m,amount);if(!r.success()){p.sendMessage(text(r.message(),NamedTextColor.RED));return;}SellService.Quote q=r.quote();
        p.sendMessage(text("Sold: "+q.quantity()+" "+pretty(m)+" | Average: "+economy.format(q.average())+" | Received: "+economy.format(q.total())+" | New price: "+economy.format(q.ending()),NamedTextColor.GREEN));}
    private void showPrice(Player p,Material m){SellService.Market market=sell.market(m);p.sendMessage(text(market==null?"That item is not sellable.":pretty(m)+": "+economy.format(market.current)+" (base "+economy.format(market.base)+")",market==null?NamedTextColor.RED:NamedTextColor.GREEN));}
    private void refreshTimers(Holder holder){
        holder.materials.forEach((slot,material)->{
            SellService.Market market=sell.market(material);
            if(market!=null)holder.inventory.setItem(slot,marketItem(market));
        });
    }
    private ItemStack marketItem(SellService.Market market){
        double valuePercent=market.base.signum()==0?0:market.current.divide(market.base,4,java.math.RoundingMode.HALF_UP).doubleValue()*100;
        double progress=sell.recoveryProgress(market)*100;
        long remaining=sell.recoveryRemainingMillis(market);
        String time=sell.isFrozen(market)?"Paused":formatDuration(remaining);
        return item(market.material,pretty(market.material),market.current.signum()>0?NamedTextColor.GREEN:NamedTextColor.RED,
                market.current.signum()>0?"Current: "+economy.format(market.current):"No Current Demand",
                "Base: "+economy.format(market.base),"Minimum: "+economy.format(market.minimum),
                String.format(Locale.US,"Value: %.1f%% of base",valuePercent),
                "Full Recovery Time: "+formatDuration((long)(market.fullRecoveryHours*3600000d)),
                String.format(Locale.US,"Recovery Progress: %.1f%%",progress),
                "Time Until Base Price: "+time,
                "Recently sold: "+market.recentSold,"Click to choose a quantity");
    }
    private String formatDuration(long millis){
        if(millis<=0)return "Ready";
        long seconds=(millis+999)/1000,hours=seconds/3600,minutes=(seconds%3600)/60,remainder=seconds%60;
        return String.format(Locale.US,"%02d:%02d:%02d",hours,minutes,remainder);
    }
    private void fill(Inventory inv){ItemStack border=item(Material.BLACK_STAINED_GLASS_PANE," ",NamedTextColor.GRAY),inside=item(Material.GRAY_STAINED_GLASS_PANE," ",NamedTextColor.GRAY);for(int i=0;i<54;i++){int r=i/9,c=i%9;inv.setItem(i,r==0||r==5||c==0||c==8?border:inside);}}
    private ItemStack item(Material m,String name,NamedTextColor color,String...lore){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.displayName(text(name,color));meta.lore(Arrays.stream(lore).map(x->text(x,NamedTextColor.GRAY)).toList());i.setItemMeta(meta);return i;}
    private Component text(String s,NamedTextColor c){return Component.text(s,c).decoration(TextDecoration.ITALIC,false);}private static String pretty(Material m){String n=m.name().toLowerCase(Locale.ROOT).replace('_',' ');return Character.toUpperCase(n.charAt(0))+n.substring(1);}
    @Override public List<String> onTabComplete(@NotNull CommandSender s,@NotNull Command c,@NotNull String a,@NotNull String[] args){if(args.length==1)return List.of("hand","all","price","prices").stream().filter(x->x.startsWith(args[0].toLowerCase())).toList();if(args.length==2&&args[0].equalsIgnoreCase("price"))return sell.markets().stream().map(m->m.material.name().toLowerCase()).filter(x->x.startsWith(args[1].toLowerCase())).toList();return List.of();}
    enum Sort{HIGH("Highest Price",Comparator.<SellService.Market,java.math.BigDecimal>comparing(m->m.current).reversed()),LOW("Lowest Price",Comparator.comparing(m->m.current)),PERCENT_HIGH("Highest %",Comparator.<SellService.Market,Double>comparing(m->m.base.signum()==0?0:m.current.doubleValue()/m.base.doubleValue()).reversed()),PERCENT_LOW("Lowest %",Comparator.comparing(m->m.base.signum()==0?0:m.current.doubleValue()/m.base.doubleValue())),RECOVERY("Fastest Recovery",Comparator.comparingDouble(m->m.fullRecoveryHours)),MOST("Most Sold",Comparator.<SellService.Market>comparingLong(m->m.totalSold).reversed()),AZ("A-Z",Comparator.comparing(m->m.material.name())),ZA("Z-A",Comparator.<SellService.Market,String>comparing(m->m.material.name()).reversed());final String label;final Comparator<SellService.Market> comparator;Sort(String l,Comparator<SellService.Market> c){label=l;comparator=c;}Sort next(){return values()[(ordinal()+1)%values().length];}}
    static final class Holder implements InventoryHolder{final int page;final Sort sort;final Material material;final SellService.Quote quote;final Map<Integer,Material> materials=new HashMap<>();Inventory inventory;Holder(int p,Sort s,Material m,SellService.Quote q){page=p;sort=s;material=m;quote=q;}public Inventory getInventory(){return inventory;}}
    record Pending(Material material,int page,Sort sort,int maximum){}
}

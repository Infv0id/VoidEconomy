package com.voidplugin.economy;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SellService {
    private final VoidEconomyPlugin plugin;
    private final EconomyService economy;
    private final File file;
    private final Map<Material, Market> markets = new EnumMap<>(Material.class);
    private final List<Event> events = new ArrayList<>();
    private final List<Sale> sales = new ArrayList<>();
    private final List<Snapshot> snapshots = new ArrayList<>();
    private BigDecimal auctionFees = zero(), orderFees = zero(), adminDestroyed = zero();
    private boolean frozen;

    SellService(VoidEconomyPlugin plugin, EconomyService economy) {
        this.plugin=plugin; this.economy=economy; this.file=new File(plugin.getDataFolder(),"sell-market.yml");
    }

    synchronized void load() {
        markets.clear();
        migrateRecoveryConfiguration();
        ConfigurationSection configured=plugin.getConfig().getConfigurationSection("sell.materials");
        if(configured!=null) for(String key:configured.getKeys(false)) {
            Material material=Material.matchMaterial(key); ConfigurationSection c=configured.getConfigurationSection(key);
            if(material==null||c==null)continue;
            BigDecimal base=money(c.getString("base-price","0")), minimum=money(c.getString("minimum-price","0"));
            markets.put(material,new Market(material,c.getBoolean("enabled",true),base,minimum,
                    Math.max(1,c.getInt("reference-quantity",32)),clamp(c.getDouble("drop-rate",.05),0,.99),
                    Math.max(.01,c.getDouble("full-recovery-hours",8)),base,base,Instant.now().toEpochMilli(),
                    0,zero(),base,base,false,0));
        }
        if(!file.exists())return;
        YamlConfiguration data=YamlConfiguration.loadConfiguration(file);
        frozen=data.getBoolean("frozen");
        auctionFees=money(data.getString("fees.auction","0")); orderFees=money(data.getString("fees.orders","0"));
        adminDestroyed=money(data.getString("fees.admin","0"));
        ConfigurationSection saved=data.getConfigurationSection("markets");
        if(saved!=null) for(String key:saved.getKeys(false)) {
            Material material=Material.matchMaterial(key); Market m=markets.get(material); if(m==null)continue;
            String p="markets."+key+".";
            m.current=money(data.getString(p+"current",m.base.toPlainString()));
            m.base=money(data.getString(p+"base",m.base.toPlainString()));m.minimum=money(data.getString(p+"minimum",m.minimum.toPlainString()));
            m.reference=Math.max(1,data.getInt(p+"reference",m.reference));m.dropRate=clamp(data.getDouble(p+"drop-rate",m.dropRate),0,.99);
            if(data.contains(p+"full-recovery-hours"))m.fullRecoveryHours=Math.max(.01,data.getDouble(p+"full-recovery-hours"));
            else {
                double legacy=Math.max(.01,data.getDouble(p+"half-life",m.fullRecoveryHours));
                m.fullRecoveryHours=material==Material.DIAMOND&&Math.abs(legacy-12)<.000001?8:legacy;
            }
            m.enabled=data.getBoolean(p+"enabled",m.enabled);
            m.recoveryStartPrice=money(data.getString(p+"recovery-start-price",m.current.toPlainString()));
            m.recoveryStartTimestamp=data.getLong(p+"recovery-start-timestamp",
                    data.getLong(p+"last-update",m.recoveryStartTimestamp));
            m.totalSold=data.getLong(p+"total-sold");m.generated=money(data.getString(p+"generated","0"));
            m.lowest=money(data.getString(p+"lowest",m.current.toPlainString()));m.highest=money(data.getString(p+"highest",m.base.toPlainString()));
            m.frozen=data.getBoolean(p+"frozen");m.recentSold=data.getLong(p+"recent-sold");
        }
        ConfigurationSection history=data.getConfigurationSection("events");
        if(history!=null)for(String key:history.getKeys(false))try{String p="events."+key+".";
            events.add(new Event(data.getLong(p+"time"),data.getString(p+"kind","SELL"),Material.matchMaterial(data.getString(p+"material","")),
                    data.getLong(p+"quantity"),money(data.getString(p+"amount","0"))));}catch(RuntimeException ignored){}
        ConfigurationSection saleRows=data.getConfigurationSection("sales");
        if(saleRows!=null)for(String key:saleRows.getKeys(false))try{String p="sales."+key+".";
            sales.add(new Sale(data.getLong(p+"time"),UUID.fromString(data.getString(p+"player-id","")),data.getString(p+"player-name","Unknown"),
                    Material.valueOf(data.getString(p+"material","STONE")),data.getInt(p+"quantity"),money(data.getString(p+"starting","0")),
                    money(data.getString(p+"ending","0")),money(data.getString(p+"average","0")),money(data.getString(p+"total","0"))));}catch(RuntimeException ignored){}
        ConfigurationSection snapshotRows=data.getConfigurationSection("snapshots");
        if(snapshotRows!=null)for(String key:snapshotRows.getKeys(false))try{String p="snapshots."+key+".";
            snapshots.add(new Snapshot(data.getLong(p+"time"),money(data.getString(p+"supply","0"))));}catch(RuntimeException ignored){}
        migrateLegacyDefaultPrices();
        recoverAll();
    }

    private void migrateRecoveryConfiguration() {
        ConfigurationSection configured=plugin.getConfig().getConfigurationSection("sell.materials");
        if(configured==null)return;
        boolean changed=false;
        for(String key:configured.getKeys(false)){
            ConfigurationSection material=configured.getConfigurationSection(key);
            if(material==null||!material.contains("recovery-half-life-hours"))continue;
            double old=material.getDouble("recovery-half-life-hours",8);
            if(!material.contains("full-recovery-hours")) {
                material.set("full-recovery-hours",key.equalsIgnoreCase("DIAMOND")&&Math.abs(old-12)<.000001?8:old);
            }
            material.set("recovery-half-life-hours",null);
            changed=true;
        }
        if(changed){
            plugin.saveConfig();
            plugin.getLogger().info("Migrated sell recovery settings to full-recovery-hours.");
        }
    }

    private void migrateLegacyDefaultPrices() {
        Map<Material,BigDecimal[]> values=Map.ofEntries(
                Map.entry(Material.ECHO_SHARD,pair("3000","300","15")),
                Map.entry(Material.DIAMOND,pair("1500","150","7.50")),
                Map.entry(Material.ANCIENT_DEBRIS,pair("1800","180","9")),
                Map.entry(Material.NETHERITE_SCRAP,pair("2000","200","10")),
                Map.entry(Material.NETHERITE_INGOT,pair("8200","820","41")),
                Map.entry(Material.DRAGON_HEAD,pair("12000","1200","60")),
                Map.entry(Material.HEAVY_CORE,pair("8000","800","40")),
                Map.entry(Material.TRIAL_KEY,pair("300","30","1.50")),
                Map.entry(Material.OMINOUS_TRIAL_KEY,pair("750","75","3.75")),
                Map.entry(Material.COD,pair("8","0.80","0")),
                Map.entry(Material.SALMON,pair("12","1.20","0")),
                Map.entry(Material.PUFFERFISH,pair("20","2","0")),
                Map.entry(Material.TROPICAL_FISH,pair("25","2.50","0")));
        values.forEach((material,prices)->{
            Market m=markets.get(material);if(m==null||m.base.compareTo(prices[0])!=0)return;
            BigDecimal ratio=prices[1].divide(prices[0],12,RoundingMode.HALF_UP);
            m.current=money(m.current.multiply(ratio));m.lowest=money(m.lowest.multiply(ratio));
            m.recoveryStartPrice=money(m.recoveryStartPrice.multiply(ratio));
            m.highest=money(m.highest.multiply(ratio));m.base=prices[1];m.minimum=prices[2];
            m.current=m.current.max(m.minimum).min(m.base);
            m.recoveryStartPrice=m.recoveryStartPrice.max(m.minimum).min(m.base);
        });
    }
    private BigDecimal[] pair(String oldBase,String newBase,String newMinimum){
        return new BigDecimal[]{money(oldBase),money(newBase),money(newMinimum)};
    }

    synchronized void save() {
        recoverAll(); YamlConfiguration data=new YamlConfiguration();data.set("frozen",frozen);
        data.set("fees.auction",auctionFees.toPlainString());data.set("fees.orders",orderFees.toPlainString());data.set("fees.admin",adminDestroyed.toPlainString());
        for(Market m:markets.values()){String p="markets."+m.material.name()+".";data.set(p+"current",m.current.toPlainString());
            data.set(p+"base",m.base.toPlainString());data.set(p+"minimum",m.minimum.toPlainString());data.set(p+"reference",m.reference);
            data.set(p+"drop-rate",m.dropRate);data.set(p+"full-recovery-hours",m.fullRecoveryHours);data.set(p+"enabled",m.enabled);
            data.set(p+"recovery-start-price",m.recoveryStartPrice.toPlainString());
            data.set(p+"recovery-start-timestamp",m.recoveryStartTimestamp);
            data.set(p+"total-sold",m.totalSold);data.set(p+"generated",m.generated.toPlainString());
            data.set(p+"lowest",m.lowest.toPlainString());data.set(p+"highest",m.highest.toPlainString());data.set(p+"frozen",m.frozen);data.set(p+"recent-sold",m.recentSold);}
        int start=Math.max(0,events.size()-5000);for(int i=start;i<events.size();i++){Event e=events.get(i);String p="events."+(i-start)+".";
            data.set(p+"time",e.time);data.set(p+"kind",e.kind);data.set(p+"material",e.material==null?null:e.material.name());data.set(p+"quantity",e.quantity);data.set(p+"amount",e.amount.toPlainString());}
        int saleStart=Math.max(0,sales.size()-5000);for(int i=saleStart;i<sales.size();i++){Sale s=sales.get(i);String p="sales."+(i-saleStart)+".";
            data.set(p+"time",s.time);data.set(p+"player-id",s.playerId.toString());data.set(p+"player-name",s.playerName);data.set(p+"material",s.material.name());
            data.set(p+"quantity",s.quantity);data.set(p+"starting",s.starting.toPlainString());data.set(p+"ending",s.ending.toPlainString());
            data.set(p+"average",s.average.toPlainString());data.set(p+"total",s.total.toPlainString());}
        int snapStart=Math.max(0,snapshots.size()-2000);for(int i=snapStart;i<snapshots.size();i++){Snapshot s=snapshots.get(i);String p="snapshots."+(i-snapStart)+".";data.set(p+"time",s.time);data.set(p+"supply",s.supply.toPlainString());}
        try{File temp=new File(file.getParentFile(),file.getName()+".tmp");data.save(temp);java.nio.file.Files.move(temp.toPath(),file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(IOException exception){plugin.getLogger().severe("Could not save sell market: "+exception.getMessage());}
    }

    synchronized List<Market> markets(){recoverAll();return markets.values().stream().filter(m->m.enabled).sorted(Comparator.comparing(m->m.material.name())).toList();}
    synchronized List<Market> allMarkets(){recoverAll();return markets.values().stream().sorted(Comparator.comparing(m->m.material.name())).toList();}
    synchronized boolean isGloballyFrozen(){return frozen;}
    synchronized Market market(Material material){Market m=markets.get(material);if(m!=null)recover(m);return m;}
    synchronized BigDecimal minimumAuctionPrice(ItemStack item){
        if(item==null||!valid(item,item.getType()))return zero();
        Market market=markets.get(item.getType());
        return market==null||!market.enabled?zero():money(market.base.multiply(BigDecimal.valueOf(item.getAmount())));
    }
    synchronized Quote quote(Material material,int quantity){Market m=market(material);if(m==null||!m.enabled||quantity<1)return null;
        BigDecimal price=m.current,total=zero(),ratio=BigDecimal.valueOf(Math.pow(1-m.dropRate,1.0/m.reference));
        for(int i=0;i<quantity;i++){total=total.add(price);price=price.multiply(ratio).max(m.minimum);}
        total=money(total);return new Quote(material,quantity,m.current,money(price),quantity==0?zero():money(total.divide(BigDecimal.valueOf(quantity),8,RoundingMode.HALF_UP)),total);}

    synchronized SaleResult sell(Player player,Material material,int requested) {
        Market m=market(material);if(m==null||!m.enabled||frozen||m.frozen)return new SaleResult(false,"This market is unavailable.",null);
        int available=countValid(player,material),quantity=Math.min(requested,available);Quote q=quote(material,quantity);
        if(q==null||quantity<1)return new SaleResult(false,"You have no accepted items to sell.",null);
        if(q.total.signum()<=0)return new SaleResult(false,"No Current Demand. Your items were not removed.",q);
        if(!removeValid(player,material,quantity))return new SaleResult(false,"Your inventory changed; the sale was cancelled.",null);
        long now=Instant.now().toEpochMilli();
        m.current=q.ending;m.recoveryStartPrice=m.current;m.recoveryStartTimestamp=now;m.totalSold+=quantity;m.recentSold+=quantity;
        m.generated=m.generated.add(q.total);m.lowest=m.lowest.min(m.current);m.highest=m.highest.max(m.current);
        economy.deposit(player.getUniqueId(),q.total);economy.record("SELL_GENERATED",player.getUniqueId(),player.getName(),player.getUniqueId(),player.getName(),q.total);
        sales.add(new Sale(now,player.getUniqueId(),player.getName(),material,quantity,q.starting,q.ending,q.average,q.total));
        events.add(new Event(now,"SELL",material,quantity,q.total));economy.save();save();return new SaleResult(true,"",q);
    }

    int countValid(Player p,Material material){int count=0;for(ItemStack item:p.getInventory().getStorageContents())if(valid(item,material))count+=item.getAmount();return count;}
    boolean valid(ItemStack item,Material material){if(item==null||item.getType()!=material)return false;ItemMeta meta=item.getItemMeta(),plain=new ItemStack(material).getItemMeta();return meta.equals(plain);}
    private boolean removeValid(Player p,Material material,int amount){if(countValid(p,material)<amount)return false;ItemStack[] items=p.getInventory().getStorageContents();
        for(int i=0;i<items.length&&amount>0;i++){ItemStack item=items[i];if(!valid(item,material))continue;int take=Math.min(amount,item.getAmount());item.setAmount(item.getAmount()-take);if(item.getAmount()==0)items[i]=null;amount-=take;}p.getInventory().setStorageContents(items);return amount==0;}
    synchronized void recordFee(String kind,BigDecimal amount){amount=money(amount);if(kind.equals("AUCTION"))auctionFees=auctionFees.add(amount);else if(kind.equals("ORDER"))orderFees=orderFees.add(amount);else adminDestroyed=adminDestroyed.add(amount);events.add(new Event(System.currentTimeMillis(),"FEE_"+kind,null,0,amount));save();}
    synchronized void recordAdminChange(BigDecimal amount,boolean generated){amount=money(amount);if(!generated)adminDestroyed=adminDestroyed.add(amount);events.add(new Event(System.currentTimeMillis(),generated?"ADMIN_GENERATED":"FEE_ADMIN",null,0,amount));save();}
    BigDecimal fee(BigDecimal gross){return money(gross.multiply(BigDecimal.valueOf(plugin.getConfig().getDouble("marketplace.transaction-fee-rate",.02))));}
    synchronized Stats stats(long since){BigDecimal generated=zero(),destroyed=zero();for(Event e:events)if(e.time>=since){if(e.kind.equals("SELL")||e.kind.equals("ADMIN_GENERATED"))generated=generated.add(e.amount);if(e.kind.startsWith("FEE_"))destroyed=destroyed.add(e.amount);}return new Stats(money(generated),money(destroyed),money(generated.subtract(destroyed)),auctionFees,orderFees);}
    synchronized void reset(Material material){Market m=markets.get(material);if(m!=null){restartRecovery(m,m.base);save();}}
    synchronized void setFrozen(Material material,boolean value){
        Market m=markets.get(material);if(m==null||m.frozen==value)return;
        if(value){recover(m);restartRecovery(m,m.current);m.frozen=true;}
        else{m.frozen=false;restartRecovery(m,m.current);}
        save();
    }
    synchronized void setFrozen(boolean value){
        if(frozen==value)return;
        if(value){
            recoverAll();
            for(Market m:markets.values())restartRecovery(m,m.current);
            frozen=true;
        }else{
            frozen=false;
            for(Market m:markets.values())restartRecovery(m,m.current);
        }
        save();
    }
    synchronized void recordSnapshot(BigDecimal supply){long now=System.currentTimeMillis();if(snapshots.isEmpty()||now-snapshots.getLast().time>=3600000L)snapshots.add(new Snapshot(now,money(supply)));}
    synchronized BigDecimal snapshotBefore(long time){return snapshots.stream().filter(s->s.time<=time).min(Comparator.comparingLong(s->Math.abs(time-s.time))).map(Snapshot::supply).orElse(zero());}
    synchronized boolean configure(Material material,String action,String value){Market m=markets.get(material);if(m==null)return false;try{switch(action){
        case "enable"->m.enabled=true;case "disable"->m.enabled=false;case "price"->m.current=money(value).max(m.minimum);
        case "baseprice"->m.base=money(value);case "minimum"->m.minimum=money(value);case "droprate"->m.dropRate=clamp(Double.parseDouble(value)/100d,0,.99);
        case "reference"->m.reference=Math.max(1,Integer.parseInt(value));case "recovery"->m.fullRecoveryHours=Math.max(.01,Double.parseDouble(value));
        case "reset"->m.current=m.base;case "freeze"->{recover(m);m.frozen=true;}case "unfreeze"->m.frozen=false;default->{return false;}}
        m.current=m.current.min(m.base).max(m.minimum);restartRecovery(m,m.current);
        long now=System.currentTimeMillis();events.add(new Event(now,"ADMIN_SELL_"+action,material,0,zero()));save();return true;
    }catch(RuntimeException e){return false;}}
    synchronized boolean add(Material material){if(material==null||!material.isItem()||material.isAir())return false;
        markets.computeIfAbsent(material,m->new Market(m,true,BigDecimal.ONE.setScale(2),zero(),32,.05,8,
                BigDecimal.ONE.setScale(2),BigDecimal.ONE.setScale(2),System.currentTimeMillis(),0,zero(),
                BigDecimal.ONE.setScale(2),BigDecimal.ONE.setScale(2),false,0));save();return true;}
    private void recoverAll(){markets.values().forEach(this::recover);}
    private void recover(Market m){
        if(m.frozen||frozen)return;
        long now=System.currentTimeMillis();
        double elapsedHours=Math.max(0,(now-m.recoveryStartTimestamp)/3600000d);
        double progress=Math.min(1,elapsedHours/m.fullRecoveryHours);
        BigDecimal distance=m.base.subtract(m.recoveryStartPrice);
        m.current=money(m.recoveryStartPrice.add(distance.multiply(BigDecimal.valueOf(progress))))
                .min(m.base).max(m.minimum);
        if(elapsedHours>=1)m.recentSold=0;
    }
    private void restartRecovery(Market m,BigDecimal price){
        m.current=money(price).min(m.base).max(m.minimum);
        m.recoveryStartPrice=m.current;
        m.recoveryStartTimestamp=System.currentTimeMillis();
    }
    synchronized double recoveryProgress(Market m){
        recover(m);
        if(m.current.compareTo(m.base)>=0)return 1;
        if(m.frozen||frozen)return 0;
        return Math.min(1,Math.max(0,(System.currentTimeMillis()-m.recoveryStartTimestamp)
                /(m.fullRecoveryHours*3600000d)));
    }
    synchronized long recoveryRemainingMillis(Market m){
        recover(m);
        if(m.current.compareTo(m.base)>=0)return 0;
        if(m.frozen||frozen)return -1;
        long duration=(long)(m.fullRecoveryHours*3600000d);
        return Math.max(0,duration-(System.currentTimeMillis()-m.recoveryStartTimestamp));
    }
    synchronized boolean isFrozen(Market m){return frozen||m.frozen;}
    private BigDecimal money(String value){try{return money(new BigDecimal(value));}catch(Exception e){return zero();}}
    private BigDecimal money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP);}private static BigDecimal zero(){return new BigDecimal("0.00");}
    private static double clamp(double n,double min,double max){return Math.max(min,Math.min(max,n));}

    static final class Market {final Material material;boolean enabled;BigDecimal base,minimum;int reference;double dropRate,fullRecoveryHours;BigDecimal current,recoveryStartPrice;long recoveryStartTimestamp,totalSold;BigDecimal generated,lowest,highest;boolean frozen;long recentSold;
        Market(Material a,boolean b,BigDecimal c,BigDecimal d,int e,double f,double g,BigDecimal h,BigDecimal start,long timestamp,long sold,BigDecimal generated,BigDecimal lowest,BigDecimal highest,boolean frozen,long recent){
            material=a;enabled=b;base=c;minimum=d;reference=e;dropRate=f;fullRecoveryHours=g;current=h;recoveryStartPrice=start;
            recoveryStartTimestamp=timestamp;totalSold=sold;this.generated=generated;this.lowest=lowest;this.highest=highest;this.frozen=frozen;recentSold=recent;}}
    record Quote(Material material,int quantity,BigDecimal starting,BigDecimal ending,BigDecimal average,BigDecimal total){}
    record SaleResult(boolean success,String message,Quote quote){}
    record Event(long time,String kind,Material material,long quantity,BigDecimal amount){}
    record Sale(long time,UUID playerId,String playerName,Material material,int quantity,BigDecimal starting,BigDecimal ending,BigDecimal average,BigDecimal total){}
    record Snapshot(long time,BigDecimal supply){}
    record Stats(BigDecimal generated,BigDecimal destroyed,BigDecimal net,BigDecimal auctionFees,BigDecimal orderFees){}
}

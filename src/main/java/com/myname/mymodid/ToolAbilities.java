package com.example.toolabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import com.hbm.handler.ability.AvailableAbilities;
import com.hbm.handler.ability.IBaseAbility;
import com.hbm.handler.ability.IToolAreaAbility;
import com.hbm.handler.ability.IToolHarvestAbility;
import com.hbm.handler.ability.IWeaponAbility;
import com.hbm.handler.ability.ToolPreset;
import com.hbm.items.tool.ItemToolAbility;
import com.hbm.items.tool.ItemToolAbility.EnumToolType;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.BlockEvent;

/**
 * Gives any tool the real NTM tool/weapon abilities, chosen in config/toolabilities.cfg.
 *
 * This does NOT reimplement anything: it builds a hidden (never registered) ItemToolAbility per configured tool
 * and runs NTM's own onBlockStartBreak flow with it, and calls NTM's own IWeaponAbility.onHit on melee hits.
 * Ability code, ToolConfig toggles and the preset restrictions (e.g. explosion disables harvest abilities)
 * are all NTM's.
 *
 * Config line:  modid:itemname=ability[:tier],ability[:tier],...
 * Tier is 1-based (1 = first level in NTM's table for that ability).
 */
@Mod(modid = "toolabilities", name = "Tool Abilities", version = "2.0", dependencies = "required-after:hbm", acceptableRemoteVersions = "*")
public class ToolAbilities {

    private static final Map<String, IToolAreaAbility> AREA = new LinkedHashMap<String, IToolAreaAbility>();
    private static final Map<String, IToolHarvestAbility> HARVEST = new LinkedHashMap<String, IToolHarvestAbility>();
    private static final Map<String, IWeaponAbility> WEAPON = new LinkedHashMap<String, IWeaponAbility>();

    static {
        AREA.put("vein", IToolAreaAbility.RECURSION);
        AREA.put("recursion", IToolAreaAbility.RECURSION);
        AREA.put("hammer", IToolAreaAbility.HAMMER);
        AREA.put("hammer_flat", IToolAreaAbility.HAMMER_FLAT);
        AREA.put("explosion", IToolAreaAbility.EXPLOSION);

        HARVEST.put("silk", IToolHarvestAbility.SILK);
        HARVEST.put("luck", IToolHarvestAbility.LUCK);
        HARVEST.put("smelter", IToolHarvestAbility.SMELTER);
        HARVEST.put("shredder", IToolHarvestAbility.SHREDDER);
        HARVEST.put("centrifuge", IToolHarvestAbility.CENTRIFUGE);
        HARVEST.put("crystallizer", IToolHarvestAbility.CRYSTALLIZER);
        HARVEST.put("mercury", IToolHarvestAbility.MERCURY);

        WEAPON.put("radiation", IWeaponAbility.RADIATION);
        WEAPON.put("vampire", IWeaponAbility.VAMPIRE);
        WEAPON.put("stun", IWeaponAbility.STUN);
        WEAPON.put("phosphorus", IWeaponAbility.PHOSPHORUS);
        WEAPON.put("fire", IWeaponAbility.FIRE);
        WEAPON.put("chainsaw", IWeaponAbility.CHAINSAW);
        WEAPON.put("beheader", IWeaponAbility.BEHEADER);
        WEAPON.put("bobble", IWeaponAbility.BOBBLE);
        WEAPON.put("cleave", IWeaponAbility.CLEAVE);
    }

    /** A never-registered NTM tool whose active preset is fixed by the config. Everything else is inherited from NTM. */
    private static final class Proxy extends ItemToolAbility {
        private final ToolPreset preset;

        Proxy(ToolPreset preset) {
            super(0F, 0D, Item.ToolMaterial.IRON, EnumToolType.PICKAXE);
            this.preset = preset;
        }

        @Override
        public ItemToolAbility.Configuration getConfiguration(ItemStack stack) {
            List<ToolPreset> list = new ArrayList<ToolPreset>(1);
            list.add(preset);
            return new ItemToolAbility.Configuration(list, 0);
        }

        // The real held item decides what it is effective on (NTM's own tools do the same with their tool type).
        @Override
        public float getDigSpeed(ItemStack stack, Block block, int meta) {
            if (stack == null) return 1.0F;
            return stack.getItem().getDigSpeed(stack, block, meta);
        }
    }

    private static final class Entry {
        Proxy proxy; // null when the tool only has weapon abilities
        final Map<IWeaponAbility, Integer> weapons = new LinkedHashMap<IWeaponAbility, Integer>();
    }

    private static final class Pending {
        final EntityPlayer player;
        final Entity victim;
        final Item tool;
        final Entry entry;

        Pending(EntityPlayer player, Entity victim, Item tool, Entry entry) {
            this.player = player;
            this.victim = victim;
            this.tool = tool;
            this.entry = entry;
        }
    }

    private Logger log;
    private String[] lines = new String[0];
    private final Map<Item, Entry> tools = new LinkedHashMap<Item, Entry>();
    private final List<Pending> pending = new ArrayList<Pending>();
    private boolean busy = false; // NTM's flow fires BreakEvents itself; don't re-enter
    private boolean processingHits = false; // cleave etc. deal damage; don't treat that as a new hit

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        log = e.getModLog();
        Configuration cfg = new Configuration(e.getSuggestedConfigurationFile());
        cfg.load();
        lines = cfg.getStringList(
            "tools",
            "general",
            new String[0],
            "One entry per tool:  modid:itemname=ability[:tier],ability[:tier],...\n"
                + "Tier is 1-based: tier 1 = first value in the table below. Omitted tier = 1.\n"
                + "Example:  minecraft:iron_pickaxe=hammer:2,smelter,fire:2\n"
                + "\n"
                + "AREA abilities (max one per tool):\n"
                + "  vein (recursion)  radius: 3,4,5,6,7,9,10\n"
                + "  hammer            range:  1,2,3,4   (cube)\n"
                + "  hammer_flat       range:  1,2,3,4   (plane you are facing)\n"
                + "  explosion         power:  2.5,5,10,15   (disables the harvest ability, as in NTM)\n"
                + "HARVEST abilities (max one per tool):\n"
                + "  silk, smelter, shredder, centrifuge, crystallizer, mercury   (no tiers)\n"
                + "  luck              fortune: 1,2,3,4,5,9\n"
                + "WEAPON abilities (any number, run on melee hits):\n"
                + "  radiation 15,50,500 | vampire 2,3,5,10,50 | stun 2,3,5,10,15 (seconds)\n"
                + "  phosphorus 60,90 (ticks/20) | fire 5,10 (seconds) | chainsaw 1:15,1:10\n"
                + "  cleave 2,3,4,5,6,8,10 (radius) | beheader | bobble\n"
                + "An area and a harvest ability can be combined (e.g. hammer:2,smelter).\n"
                + "NTM's own config (ToolConfig) can still disable individual abilities globally.");
        if (cfg.hasChanged()) cfg.save();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    // All items are registered by now.
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) {
                log.warn("Skipping '{}': expected modid:itemname=ability,...", line);
                continue;
            }
            String id = line.substring(0, eq).trim();
            String modid = "minecraft";
            String name = id;
            int colon = id.indexOf(':');
            if (colon >= 0) {
                modid = id.substring(0, colon);
                name = id.substring(colon + 1);
            }
            Item item = GameRegistry.findItem(modid, name);
            if (item == null) {
                log.warn("Skipping '{}': item not found", id);
                continue;
            }

            IToolAreaAbility area = IToolAreaAbility.NONE;
            int areaLevel = 0;
            IToolHarvestAbility harvest = IToolHarvestAbility.NONE;
            int harvestLevel = 0;
            Entry entry = new Entry();

            for (String token : line.substring(eq + 1).split(",")) {
                token = token.trim().toLowerCase();
                if (token.isEmpty()) continue;
                String key = token;
                int tier = 1;
                int c = token.indexOf(':');
                if (c >= 0) {
                    key = token.substring(0, c).trim();
                    try {
                        tier = Integer.parseInt(token.substring(c + 1).trim());
                    } catch (NumberFormatException ex) {
                        log.warn("{}: bad tier in '{}'", id, token);
                        continue;
                    }
                }

                if (AREA.containsKey(key)) {
                    if (area != IToolAreaAbility.NONE) {
                        log.warn("{}: only one area ability allowed, ignoring '{}'", id, token);
                        continue;
                    }
                    area = AREA.get(key);
                    areaLevel = toLevel(area, tier);
                } else if (HARVEST.containsKey(key)) {
                    if (harvest != IToolHarvestAbility.NONE) {
                        log.warn("{}: only one harvest ability allowed, ignoring '{}'", id, token);
                        continue;
                    }
                    harvest = HARVEST.get(key);
                    harvestLevel = toLevel(harvest, tier);
                } else if (WEAPON.containsKey(key)) {
                    IWeaponAbility w = WEAPON.get(key);
                    if (w.isAllowed()) entry.weapons.put(w, toLevel(w, tier));
                } else {
                    log.warn("{}: unknown ability '{}'", id, key);
                }
            }

            // Same restriction pass NTM applies to every tool preset (honors ToolConfig toggles and allowsHarvest).
            AvailableAbilities available = new AvailableAbilities().addToolAbilities();
            if (area != IToolAreaAbility.NONE) available.addAbility(area, areaLevel);
            if (harvest != IToolHarvestAbility.NONE) available.addAbility(harvest, harvestLevel);

            ToolPreset preset = new ToolPreset(area, areaLevel, harvest, harvestLevel);
            preset.restrictTo(available);

            if (preset.areaAbility != area || preset.harvestAbility != harvest) {
                log.info("{}: NTM restricted the requested preset (disabled in ToolConfig, or area ability blocks harvest abilities)", id);
            }

            if (!preset.isNone()) entry.proxy = new Proxy(preset);
            if (entry.proxy == null && entry.weapons.isEmpty()) continue;

            tools.put(item, entry);
            log.info("Configured {} (area={}, harvest={}, weapon abilities={})", id, preset.areaAbility.getName(), preset.harvestAbility.getName(), entry.weapons.size());
        }
    }

    private static int toLevel(IBaseAbility ability, int tier) {
        return Math.max(0, Math.min(ability.levels() - 1, tier - 1));
    }

    private Entry entryFor(ItemStack stack) {
        return stack == null ? null : tools.get(stack.getItem());
    }

    // ---------------------------------------------------------------- tool abilities

    // Forge fires BreakEvent before Item.onBlockStartBreak. For NTM's own tools, onBlockStartBreak runs the ability
    // flow and returns true (vanilla break skipped). We run that exact method on the hidden NTM tool and cancel the
    // vanilla break in the same case.
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onBreak(BlockEvent.BreakEvent e) {
        if (busy || e.world.isRemote) return;
        if (!(e.getPlayer() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) e.getPlayer();
        ItemStack held = player.getHeldItem();
        Entry entry = entryFor(held);
        if (entry == null || entry.proxy == null) return;

        busy = true;
        try {
            if (entry.proxy.onBlockStartBreak(held, e.x, e.y, e.z, player)) {
                e.setCanceled(true);
            }
        } finally {
            busy = false;
        }
    }

    // ---------------------------------------------------------------- weapon abilities

    // NTM runs weapon abilities from Item.hitEntity, which vanilla calls right after a melee hit lands.
    // We record landed melee hits here and run NTM's onHit at the end of the same server tick.
    @SubscribeEvent
    public void onHurt(LivingHurtEvent e) {
        if (processingHits || e.entityLiving.worldObj.isRemote) return;
        DamageSource src = e.source;
        if (src == null || !(src.getEntity() instanceof EntityPlayer)) return;
        if (src.getSourceOfDamage() != src.getEntity() || !"player".equals(src.damageType)) return;

        EntityPlayer player = (EntityPlayer) src.getEntity();
        ItemStack held = player.getHeldItem();
        Entry entry = entryFor(held);
        if (entry == null || entry.weapons.isEmpty()) return;

        pending.add(new Pending(player, e.entityLiving, held.getItem(), entry));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || pending.isEmpty()) return;
        List<Pending> batch = new ArrayList<Pending>(pending);
        pending.clear();

        processingHits = true;
        try {
            for (Pending p : batch) {
                for (Map.Entry<IWeaponAbility, Integer> w : p.entry.weapons.entrySet()) {
                    w.getKey().onHit(w.getValue(), p.player.worldObj, p.player, p.victim, p.tool);
                }
            }
        } finally {
            processingHits = false;
        }
    }
}

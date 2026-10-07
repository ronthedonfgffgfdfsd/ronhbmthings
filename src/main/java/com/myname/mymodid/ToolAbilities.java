package com.example.toolabilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Logger;

import com.hbm.extprop.HbmPlayerProps;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.handler.ability.IBaseAbility;
import com.hbm.handler.ability.IToolAreaAbility;
import com.hbm.handler.ability.IToolHarvestAbility;
import com.hbm.handler.ability.IWeaponAbility;
import com.hbm.items.tool.ItemToolAbility;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.world.BlockEvent;

/**
 * Gives any tool the abilities of NTM's own ItemToolAbility, using NTM's own ability classes.
 *
 * For every configured tool this mod creates a hidden (never registered) ItemToolAbility "proxy" and
 * runs the held foreign item through that proxy's code: onBlockStartBreak, handleKeybind (preset cycling),
 * and the weapon abilities' onHit. Ability behaviour, levels, messages and sounds are therefore NTM's.
 *
 * Config line format (one array entry per tool):
 *     modid:itemname=ability:level,ability:level
 * "level" is NTM's own 0-based level index (the number NTM passes to addAbility).
 */
@Mod(modid = "toolabilities", name = "Tool Abilities", version = "2.0", acceptableRemoteVersions = "*", dependencies = "required-after:hbm")
public class ToolAbilities {

    /** Name -> NTM ability object. Names are the lowercase constant names from NTM's source. */
    private static final Map<String, IBaseAbility> ABILITIES = new HashMap<String, IBaseAbility>();

    static {
        // area (IToolAreaAbility)
        ABILITIES.put("recursion", IToolAreaAbility.RECURSION);
        ABILITIES.put("hammer", IToolAreaAbility.HAMMER);
        ABILITIES.put("hammer_flat", IToolAreaAbility.HAMMER_FLAT);
        ABILITIES.put("explosion", IToolAreaAbility.EXPLOSION);
        // harvest (IToolHarvestAbility)
        ABILITIES.put("silk", IToolHarvestAbility.SILK);
        ABILITIES.put("luck", IToolHarvestAbility.LUCK);
        ABILITIES.put("smelter", IToolHarvestAbility.SMELTER);
        ABILITIES.put("shredder", IToolHarvestAbility.SHREDDER);
        ABILITIES.put("centrifuge", IToolHarvestAbility.CENTRIFUGE);
        ABILITIES.put("crystallizer", IToolHarvestAbility.CRYSTALLIZER);
        ABILITIES.put("mercury", IToolHarvestAbility.MERCURY);
        // weapon (IWeaponAbility)
        ABILITIES.put("radiation", IWeaponAbility.RADIATION);
        ABILITIES.put("vampire", IWeaponAbility.VAMPIRE);
        ABILITIES.put("stun", IWeaponAbility.STUN);
        ABILITIES.put("phosphorus", IWeaponAbility.PHOSPHORUS);
        ABILITIES.put("fire", IWeaponAbility.FIRE);
        ABILITIES.put("chainsaw", IWeaponAbility.CHAINSAW);
        ABILITIES.put("beheader", IWeaponAbility.BEHEADER);
        ABILITIES.put("bobble", IWeaponAbility.BOBBLE);
    }

    /** Hidden NTM tool used to run NTM's code for a foreign item. Never registered with the game. */
    public static class ProxyTool extends ItemToolAbility {
        private final Item real;

        ProxyTool(Item real) {
            // damage/movement/material/type are irrelevant: nothing from the proxy ever reaches the player,
            // and digging suitability is delegated to the real item below.
            super(0F, 0D, ToolMaterial.IRON, EnumToolType.MINER);
            this.real = real;
        }

        // Same rule as ItemToolAbility.canHarvestBlock, but "can this tool dig it" asks the real item.
        @Override
        public boolean canHarvestBlock(Block block, ItemStack stack) {
            if (getConfiguration(stack).getActivePreset().harvestAbility == IToolHarvestAbility.SILK) return true;
            return real.getDigSpeed(stack, block, 0) > 1;
        }

        @Override
        public float getDigSpeed(ItemStack stack, Block block, int meta) {
            return real.getDigSpeed(stack, block, meta);
        }

        public Map<IWeaponAbility, Integer> weaponAbilities() {
            return availableAbilities.getWeaponAbilities();
        }
    }

    private static final class Hit {
        final ProxyTool tool;
        final EntityLivingBase victim;

        Hit(ProxyTool tool, EntityLivingBase victim) {
            this.tool = tool;
            this.victim = victim;
        }
    }

    private static final Map<Item, ProxyTool> TOOLS = new HashMap<Item, ProxyTool>();

    private Logger log;
    private String[] entries = new String[0];

    private final Set<UUID> cycleDown = new HashSet<UUID>();
    private final Map<UUID, List<Hit>> pendingHits = new HashMap<UUID, List<Hit>>();
    private boolean nested = false; // NTM's breakExtraBlock posts BreakEvents itself; let those through

    // ------------------------------------------------------------------ setup

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        log = e.getModLog();
        Configuration cfg = new Configuration(e.getSuggestedConfigurationFile());
        cfg.load();
        entries = cfg.getStringList(
            "tools",
            "general",
            new String[0],
            "One entry per tool:  modid:itemname=ability:level,ability:level\n"
                + "level is NTM's own 0-based index (omit for 0).\n"
                + "Area:    recursion (vein radius 3,4,5,6,7,9,10), hammer (range 1-4), hammer_flat (range 1-4),\n"
                + "         explosion (strength 2.5, 5, 10, 15)\n"
                + "Harvest: silk, luck (power 1,2,3,4,5,9), smelter, shredder, centrifuge, crystallizer, mercury\n"
                + "Weapon:  radiation (15,50,500), vampire (2,3,5,10,50), stun (2,3,5,10,15 s), phosphorus (60,90),\n"
                + "         fire (5,10 s), chainsaw (1:15, 1:10), beheader, bobble\n"
                + "Example: minecraft:iron_pickaxe=hammer:1,recursion:2,silk,smelter,fire:1\n"
                + "In game: right-click cycles presets, sneak + right-click resets (same as NTM tools).");
        if (cfg.hasChanged()) cfg.save();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        if (FMLCommonHandler.instance().getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new ClientHooks());
        }
    }

    // All items are registered by now.
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        for (String raw : entries) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) {
                log.warn("Skipping '{}': expected modid:itemname=ability:level,...", line);
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
            if (item instanceof ItemToolAbility) {
                log.warn("Skipping '{}': already an NTM ability tool", id);
                continue;
            }

            ProxyTool proxy = TOOLS.get(item);
            if (proxy == null) proxy = new ProxyTool(item);

            int added = 0;
            for (String part : line.substring(eq + 1).split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String abilityName = part;
                int level = 0;
                int c = part.indexOf(':');
                if (c >= 0) {
                    abilityName = part.substring(0, c).trim();
                    try {
                        level = Integer.parseInt(part.substring(c + 1).trim());
                    } catch (NumberFormatException ex) {
                        log.warn("Bad level in '{}' for {}", part, id);
                        continue;
                    }
                }
                IBaseAbility ability = ABILITIES.get(abilityName.toLowerCase());
                if (ability == null) {
                    log.warn("Unknown ability '{}' for {}", abilityName, id);
                    continue;
                }
                proxy.addAbility(ability, level); // NTM clamps bad levels and honours its own ToolConfig toggles
                added++;
            }

            if (added > 0) {
                TOOLS.put(item, proxy);
                log.info("{}: {} abilities configured", id, added);
            }
        }
    }

    private static ProxyTool toolFor(ItemStack stack) {
        return stack == null ? null : TOOLS.get(stack.getItem());
    }

    // ------------------------------------------------------------------ block breaking

    /**
     * Vanilla order is: BreakEvent, then item.onBlockStartBreak. Foreign items have no such override, so we run
     * NTM's onBlockStartBreak here (LOWEST priority, so protection mods that cancel the event have already
     * spoken). If NTM handled the break, the event is cancelled and Forge re-syncs the block to the client.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent e) {
        if (nested || e.world.isRemote) return;
        if (!(e.getPlayer() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) e.getPlayer();
        ItemStack held = player.getHeldItem();
        ProxyTool tool = toolFor(held);
        if (tool == null) return;

        nested = true;
        try {
            if (tool.onBlockStartBreak(held, e.x, e.y, e.z, player)) {
                e.setCanceled(true);
            }
        } finally {
            nested = false;
        }
    }

    // ------------------------------------------------------------------ weapon abilities

    /**
     * NTM runs weapon abilities from Item.hitEntity, which vanilla calls only when damage was dealt.
     * LivingHurtEvent is the closest hook; the abilities then run at the end of the player's tick so that the
     * victim's health already reflects the hit (chainsaw, beheader and bobble check for death).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onHurt(LivingHurtEvent e) {
        if (e.entityLiving.worldObj.isRemote) return;
        if (!"player".equals(e.source.getDamageType())) return;
        if (!(e.source.getEntity() instanceof EntityPlayer)) return;
        EntityPlayer p = (EntityPlayer) e.source.getEntity();
        ProxyTool tool = toolFor(p.getHeldItem());
        if (tool == null || tool.weaponAbilities().isEmpty()) return;

        List<Hit> list = pendingHits.get(p.getUniqueID());
        if (list == null) {
            list = new ArrayList<Hit>();
            pendingHits.put(p.getUniqueID(), list);
        }
        list.add(new Hit(tool, e.entityLiving));
    }

    // ------------------------------------------------------------------ tick: preset cycling + pending hits

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !e.side.isServer()) return;
        EntityPlayer p = e.player;
        UUID id = p.getUniqueID();

        // Preset cycling. NTM only forwards keybinds to items implementing IKeybindReceiver, but it does record
        // the key state per player, so we watch for the press and call NTM's own handler.
        boolean down = HbmPlayerProps.getData(p).getKeyPressed(EnumKeybind.ABILITY_CYCLE);
        boolean wasDown = cycleDown.contains(id);
        if (down) cycleDown.add(id);
        else cycleDown.remove(id);

        if (down && !wasDown) {
            ItemStack held = p.getHeldItem();
            ProxyTool tool = toolFor(held);
            if (tool != null && tool.canHandleKeybind(p, held, EnumKeybind.ABILITY_CYCLE)) {
                tool.handleKeybind(p, held, EnumKeybind.ABILITY_CYCLE, true);
            }
        }

        List<Hit> hits = pendingHits.remove(id);
        if (hits != null) {
            for (Hit hit : hits) {
                for (Map.Entry<IWeaponAbility, Integer> en : hit.tool.weaponAbilities().entrySet()) {
                    en.getKey().onHit(en.getValue(), p.worldObj, p, hit.victim, hit.tool);
                }
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.player.getUniqueID();
        cycleDown.remove(id);
        pendingHits.remove(id);
    }

    // ------------------------------------------------------------------ client: NTM's tooltip

    public static class ClientHooks {
        @SubscribeEvent
        public void onTooltip(ItemTooltipEvent e) {
            ProxyTool tool = toolFor(e.itemStack);
            if (tool != null) tool.addInformation(e.itemStack, e.entityPlayer, e.toolTip, e.showAdvancedItemTooltips);
        }
    }
}

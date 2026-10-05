package com.hdworldmarkers;

import java.util.*;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.Text;

/**
 * Warns in the chat box when other plugins that are on draw what HD World Markers draws too, so it is drawn twice,
 * naming them; the Double drawing warning option turns this off. Other plugins' settings are only read.
 */
@Singleton
final class OverlapWarnings
{
    static final String TILE_INDICATORS = "net.runelite.client.plugins.tileindicators.TileIndicatorsPlugin";
    static final String BETTER_NPC_HIGHLIGHT = "com.betternpchighlight.BetterNpcHighlightPlugin";

    /** A plugin that can draw the same: its class, its name, and whether it does now. */
    private static final class Overlap
    {
        final String type, name;
        final BooleanSupplier now;

        Overlap(String type, String name, BooleanSupplier now) { this.type = type; this.name = name; this.now = now; }
    }

    private final PluginManager plugins;
    private final ConfigManager configs;
    private final HdWorldMarkersConfig config;
    private final ChatMessageManager chat;
    private final List<Overlap> overlaps;
    /** Plugins named this session; one turned off is named again when it is turned back on. */
    private final Set<String> warned = new HashSet<>();

    @Inject
    OverlapWarnings(PluginManager plugins, ConfigManager configs, HdWorldMarkersConfig config, ChatMessageManager chat,
        TilePackSource tilePacks, AgilitySource agility)
    {
        this.plugins = plugins; this.configs = configs; this.config = config; this.chat = chat;
        BooleanSupplier always = () -> true;
        BooleanSupplier tileIndicators = () -> config.highlightDestinationTile() || config.highlightHoveredTile() || config.highlightCurrentTile();
        overlaps = Arrays.asList(
            new Overlap("net.runelite.client.plugins.groundmarkers.GroundMarkerPlugin", "Ground Markers", always),
            new Overlap("net.runelite.client.plugins.objectindicators.ObjectIndicatorsPlugin", "Object Markers", always),
            new Overlap("net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin", "NPC Indicators", always),
            new Overlap(TILE_INDICATORS, "Tile Indicators", tileIndicators),
            new Overlap("com.cornertileindicators.CornerTileIndicatorsPlugin", "Corner Tile Indicators", tileIndicators),
            new Overlap("io.leikvolle.tileindicators.ImprovedTileIndicatorsPlugin", "Improved Tile Indicators", tileIndicators),
            new Overlap("com.pathmarker.PathMarkerPlugin", "Path Marker",
                () -> config.activePathDisplaySetting() != HdWorldMarkersConfig.PathDisplaySetting.NEVER
                    || config.hoverPathDisplaySetting() != HdWorldMarkersConfig.PathDisplaySetting.NEVER),
            new Overlap(BETTER_NPC_HIGHLIGHT, "Better NPC Highlight", this::sameNpcs),
            new Overlap("net.runelite.client.plugins.agility.AgilityPlugin", "Agility", () -> agility.any()
                && (config.agilityObstacles() && on("agility", "showClickboxes") || config.agilityShortcuts() && on("agility", "highlightShortcuts")
                    || config.agilityMarks() && on("agility", "highlightMarks") || config.agilityPortals() && on("agility", "highlightPortals")
                    || config.agilityTraps() && on("agility", "trapOverlay") || config.agilityStick() && on("agility", "highlightStick")
                    || config.agilitySepulchreObstacles() && on("agility", "highlightSepulchreObstacles")
                    || config.agilitySepulchreSkilling() && on("agility", "highlightSepulchreSkilling"))),
            new Overlap("com.tilepacks.TilePacksPlugin", "Tile Packs", () -> config.tilePacks() && tilePacks.any()),
            // The Slayer plugin's own highlight of your task, off unless chosen there.
            new Overlap("net.runelite.client.plugins.slayer.SlayerPlugin", "Slayer", () -> config.npcSlayerTask()
                && (chosen("slayer", "highlightHull") || chosen("slayer", "highlightTile") || chosen("slayer", "highlightOutline"))));
    }

    /** A boolean option of another plugin, on unless saved as off. */
    private boolean on(String group, String key) { return !"false".equals(configs.getConfiguration(group, key)); }

    /** A boolean option of another plugin, off unless saved as on. */
    private boolean chosen(String group, String key) { return "true".equals(configs.getConfiguration(group, key)); }

    /** Whether Better NPC Highlight highlights a name tagged here too (its lists: names, an escaped comma, ":preset"), or your Slayer task as here. */
    private boolean sameNpcs()
    {
        // Both highlighting your Slayer task.
        if (config.npcSlayerTask() && chosen("BetterNpcHighlight", "slayerHighlight")) { return true; }
        Set<String> ours = new HashSet<>();
        for (HdWorldMarkersConfig.NpcStyle style : HdWorldMarkersConfig.NpcStyle.values())
        {
            for (String name : MarkerSources.names(configs, style)) { ours.add(Text.standardize(name)); }
        }
        if (ours.isEmpty()) { return false; }
        for (String style : new String[]{"tile", "trueTile", "swTile", "swTrueTile", "hull", "area", "outline", "clickbox"})
        {
            String list = configs.getConfiguration("BetterNpcHighlight", style + "Names");
            for (String entry : list == null ? new String[0] : list.split("(?<!\\\\),"))
            {
                if (ours.contains(entry.replace("\\,", ",").trim().toLowerCase().split(":")[0])) { return true; }
            }
        }
        return false;
    }

    /** Warns about the plugins that draw the same now, when one of them was not warned about yet. */
    void check()
    {
        if (!config.warnDoubleDrawing()) { return; }
        // Every plugin that draws the same now, named whenever one of them was not named yet: a message naming only the
        // new one read as if the others no longer counted.
        List<String> names = new ArrayList<>();
        boolean any = false;
        for (Overlap o : overlaps)
        {
            if (!enabled(o.type)) { warned.remove(o.type); continue; }
            if (!o.now.getAsBoolean()) { continue; }
            names.add(o.name);
            any |= warned.add(o.type);
        }
        if (!any) { return; }
        boolean one = names.size() == 1;
        Chat.send(chat, Chat.list(names) + (one ? " draws" : " draw") + " the same marks, so they show twice. Turn " + (one ? "it" : "them")
            + " off, or this message with the option Double drawing warning.", true);
    }

    private boolean enabled(String type) { Plugin p = plugin(plugins, type); return p != null && plugins.isPluginEnabled(p); }

    /** The plugin of this class, or null when it is not installed. */
    static Plugin plugin(PluginManager plugins, String type)
    {
        for (Plugin p : plugins.getPlugins()) { if (p.getClass().getName().equals(type)) { return p; } }
        return null;
    }
}

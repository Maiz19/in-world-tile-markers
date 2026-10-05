/*
 * Copyright (c) 2018, TheLonelyDev <https://github.com/TheLonelyDev>
 * Copyright (c) 2018, James Swindle <wilingua@gmail.com>
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
 * Copyright (c) 2018, 2021, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * The menu options and what they save follow RuneLite's Ground Markers (GroundMarkerPlugin.onMenuEntryAdded, markTile,
 * labelTile, colorTile, and GroundMarkerSharingManager's import, export and clear), Object Markers
 * (ObjectIndicatorsPlugin.onMenuEntryAdded, markObject, findTileObject, its color and style menus) and NPC Indicators
 * (NpcIndicatorsPlugin.onMenuEntryAdded, tag, its color and style menus) of
 * https://github.com/runelite/runelite, tag runelite-parent-1.13.1, BSD 2-Clause License; see META-INF/LICENSE-runelite
 * and THIRD_PARTY_NOTICES.md.
 * Changes for HD World Markers: saved in its own settings, NPC colors and styles per name instead of per NPC id,
 * marks without a color of their own follow the color options, and the marks and options those plugins saved can be
 * copied (their settings are only read).
 */
package com.hdworldmarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.hdworldmarkers.HdWorldMarkersConfig.NpcStyle;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.menus.WidgetMenuOption;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

/**
 * Marking with Shift + right-click: tiles (Mark, Unmark, Label), objects (Mark object, Unmark object) and NPCs by name
 * (Tag-All, Un-tag-All), saved in HD World Markers' own settings. Tiles can be imported from and exported to
 * the clipboard in Ground Markers' format. The marks of Ground Markers, Object Markers and NPC Indicators are copied
 * when HD World Markers first starts in a profile, and again with Sync.
 */
@Singleton
final class Marking
{
    private static final String TILE = "Tile";
    private static final WidgetMenuOption EXPORT = new WidgetMenuOption("Export", "HD World Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    private static final WidgetMenuOption IMPORT = new WidgetMenuOption("Import", "HD World Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    private static final WidgetMenuOption SYNC = new WidgetMenuOption("Sync", "HD World Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    private static final WidgetMenuOption CLEAR = new WidgetMenuOption("Clear", "HD World Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    /** The colors offered in the color menus after those in use, as RuneLite's marking plugins. */
    private static final Color[] DEFAULT_COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA};
    /** Set in a profile once the other plugins' marks and options were taken over there. */
    static final String COPIED = "tookOverMarkingPlugins3";
    /** The settings groups of Ground Markers, Object Markers and NPC Indicators. */
    private static final String GROUND_MARKERS = "groundMarker", OBJECT_MARKERS = "objectindicators", NPC_INDICATORS = "npcindicators",
        TILE_INDICATORS = "tileindicators", AGILITY = "agility", BETTER_NPC_HIGHLIGHT = "BetterNpcHighlight";

    private final Client client;
    private final ClientThread clientThread;
    private final ConfigManager configs;
    private final HdWorldMarkersConfig config;
    private final MarkerSources sources;
    private final ObjectMarkerSource objects;
    private final MenuManager menus;
    private final ChatboxPanelManager chatbox;
    private final ChatMessageManager chat;
    private final Gson gson;
    private final ColorPickerManager colorPickers;
    private final PluginManager plugins;

    @Inject
    Marking(Client client, ClientThread clientThread, ConfigManager configs, HdWorldMarkersConfig config, MarkerSources sources,
        ObjectMarkerSource objects, MenuManager menus, ChatboxPanelManager chatbox, ChatMessageManager chat, Gson gson,
        ColorPickerManager colorPickers, PluginManager plugins)
    {
        this.client = client; this.clientThread = clientThread; this.configs = configs; this.config = config; this.sources = sources;
        this.objects = objects; this.menus = menus; this.chatbox = chatbox; this.chat = chat; this.gson = gson;
        this.colorPickers = colorPickers; this.plugins = plugins;
    }

    void startUp()
    {
        importExportOptions();
        clientThread.invokeLater(this::copyOnce);
    }

    void shutDown()
    {
        removeOptions();
        pendingNotice = null;
        profileSwitched = false;
    }

    private void removeOptions()
    {
        for (WidgetMenuOption option : new WidgetMenuOption[]{EXPORT, IMPORT, SYNC, CLEAR}) { menus.removeManagedCustomMenu(option); }
    }

    private void importExportOptions()
    {
        removeOptions();
        if (!config.showImportExport()) { return; }
        menus.addManagedCustomMenu(EXPORT, e -> exportTiles());
        menus.addManagedCustomMenu(IMPORT, e -> promptImport());
        menus.addManagedCustomMenu(SYNC, e -> sync());
        menus.addManagedCustomMenu(CLEAR, e -> promptClear());
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (HdWorldMarkersConfig.GROUP.equals(e.getGroup()) && "showImportExport".equals(e.getKey())) { importExportOptions(); }
    }

    /**
     * A profile switch: its settings are moved and the other plugins' marks taken over at the next game tick or login.
     * RuneLite handles the switch after this and stops the plugin in a profile where it is off or not installed, which
     * then got them all the same.
     */
    private volatile boolean profileSwitched;

    @Subscribe
    public void onProfileChanged(ProfileChanged e)
    {
        importExportOptions();
        profileSwitched = true;
    }

    @Subscribe
    public void onGameTick(GameTick e) { if (profileSwitched) { afterProfileSwitch(); } }

    private void afterProfileSwitch()
    {
        profileSwitched = false;
        copyOnce();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        // NPC names come from the game's cache, loaded by the login screen.
        if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.LOGGED_IN)
        {
            if (profileSwitched) { afterProfileSwitch(); } else { copyOnce(); }
        }
        if (e.getGameState() == GameState.LOGGED_IN && pendingNotice != null) { message(pendingNotice); pendingNotice = null; }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (!client.isKeyPressed(KeyCode.KC_SHIFT)) { return; }
        MenuEntry entry = event.getMenuEntry();
        MenuAction action = entry.getType();
        if ((action == MenuAction.WALK || action == MenuAction.SET_HEADING) && config.markTiles()) { tileOptions(entry); }
        else if (action == MenuAction.EXAMINE_OBJECT && config.markObjects()) { objectOptions(event); }
        else if (action == MenuAction.EXAMINE_NPC && config.tagNpcs()) { npcOptions(event); }
    }

    // Tiles

    private void tileOptions(MenuEntry entry)
    {
        WorldView wv = client.getWorldView(entry.getWorldViewId());
        Tile tile = wv == null ? null : wv.getSelectedSceneTile();
        if (tile == null) { return; }
        WorldPoint point = WorldPoint.fromLocalInstance(client, tile.getLocalLocation());
        MarkerSources.TilePoint marked = tile(point);
        client.getMenu().createMenuEntry(-1).setOption(marked != null ? "Unmark" : "Mark").setTarget(TILE)
            .setType(MenuAction.RUNELITE).onClick(e -> markTile(point));
        if (marked != null)
        {
            client.getMenu().createMenuEntry(-2).setOption("Label").setTarget(TILE)
                .setType(MenuAction.RUNELITE).onClick(e -> labelTile(marked));
            tileColorMenu(marked);
        }
    }

    /** Ground Markers' Color menu: Reset all (the region's tiles), Pick, and the colors in use. */
    private void tileColorMenu(MarkerSources.TilePoint marked)
    {
        Menu colors = client.getMenu().createMenuEntry(-3).setOption("Color").setTarget(TILE).setType(MenuAction.RUNELITE).createSubMenu();
        List<MarkerSources.TilePoint> region = sources.tiles(marked.regionId);
        if (region.size() > 1)
        {
            colors.createMenuEntry(-1).setOption("Reset all").setType(MenuAction.RUNELITE).onClick(e -> chatbox
                .openTextMenuInput("Are you sure you want to reset the color of " + region.size() + " tiles?")
                .option("Yes", () -> clientThread.invokeLater(() -> {
                    List<MarkerSources.TilePoint> reset = new ArrayList<>();
                    for (MarkerSources.TilePoint p : sources.tiles(marked.regionId))
                    {
                        reset.add(new MarkerSources.TilePoint(p.regionId, p.regionX, p.regionY, p.z, null, p.label));
                    }
                    saveTiles(marked.regionId, reset);
                }))
                .option("No", () -> { })
                .build());
        }
        if (marked.color != null)
        {
            colors.createMenuEntry(-1).setOption("Reset").setType(MenuAction.RUNELITE).onClick(e -> colorTile(marked, null));
        }
        colors.createMenuEntry(-1).setOption("Pick").setType(MenuAction.RUNELITE)
            .onClick(e -> pick(marked.color != null ? marked.color : config.tileColor(), "Tile marker color", c -> colorTile(marked, c)));
        List<Color> used = new ArrayList<>();
        WorldView wv = client.getTopLevelWorldView();
        for (int r : wv == null || wv.getMapRegions() == null ? new int[0] : wv.getMapRegions())
        {
            for (MarkerSources.TilePoint p : sources.tiles(r))
            {
                if (!Objects.equals(p.color, marked.color)) { offer(used, p.color); }
            }
        }
        for (Color c : used)
        {
            colors.createMenuEntry(-1).setOption(ColorUtil.prependColorTag("Color", c)).setType(MenuAction.RUNELITE).onClick(e -> colorTile(marked, c));
        }
    }

    private void colorTile(MarkerSources.TilePoint marked, Color color) { replaceTile(marked, color, marked.label); }

    /** The marked tile saved again with this color and label. */
    private void replaceTile(MarkerSources.TilePoint marked, Color color, String label)
    {
        List<MarkerSources.TilePoint> points = new ArrayList<>(sources.tiles(marked.regionId));
        points.removeIf(marked::same);
        points.add(new MarkerSources.TilePoint(marked.regionId, marked.regionX, marked.regionY, marked.z, color, label));
        saveTiles(marked.regionId, points);
    }

    /** RuneLite's color picker; the chosen color is applied on the client thread. */
    private void pick(Color current, String title, Consumer<Color> apply)
    {
        SwingUtilities.invokeLater(() -> {
            RuneliteColorPicker picker = colorPickers.create(client, current, title, false);
            picker.setOnClose(c -> clientThread.invokeLater(() -> apply.accept(c)));
            picker.setVisible(true);
        });
    }

    /** The colors in use (up to five), then defaults (at this alpha divisor) up to five, for the color menus. */
    private static List<Color> withDefaults(List<Color> used, int alphaDivisor)
    {
        List<Color> colors = new ArrayList<>(used);
        for (Color d : DEFAULT_COLORS) { offer(colors, ColorUtil.colorWithAlpha(d, d.getAlpha() / alphaDivisor)); }
        return colors;
    }

    /** Adds a color to those a color menu offers: each once, at most five, as RuneLite's marking plugins. */
    static void offer(List<Color> colors, Color color)
    {
        if (color != null && colors.size() < 5 && !colors.contains(color)) { colors.add(color); }
    }

    /** The marked tile at this point, or null. */
    private MarkerSources.TilePoint tile(WorldPoint point)
    {
        for (MarkerSources.TilePoint p : sources.tiles(point.getRegionID()))
        {
            if (p.regionX == point.getRegionX() && p.regionY == point.getRegionY() && p.z == point.getPlane()) { return p; }
        }
        return null;
    }

    private void markTile(WorldPoint point)
    {
        int region = point.getRegionID();
        // Without a color of its own: it follows the Tile color option.
        MarkerSources.TilePoint mark = new MarkerSources.TilePoint(region, point.getRegionX(), point.getRegionY(), point.getPlane(), null, null);
        List<MarkerSources.TilePoint> points = new ArrayList<>(sources.tiles(region));
        if (!points.removeIf(mark::same)) { points.add(mark); }
        saveTiles(region, points);
    }

    private void labelTile(MarkerSources.TilePoint marked)
    {
        chatbox.openTextInput("Tile label")
            .value(Strings.nullToEmpty(marked.label))
            .onDone((String input) -> clientThread.invokeLater(() -> replaceTile(marked, marked.color, Strings.emptyToNull(input))))
            .build();
    }

    private void saveTiles(int region, List<MarkerSources.TilePoint> points) { save(MarkerSources.TILES + region, points, points.isEmpty()); }

    /** Saves a value as JSON in HD World Markers' settings, or takes the setting away when there is nothing to keep. */
    private void save(String key, Object value, boolean empty)
    {
        if (empty) { configs.unsetConfiguration(HdWorldMarkersConfig.GROUP, key); }
        else { configs.setConfiguration(HdWorldMarkersConfig.GROUP, key, gson.toJson(value)); }
    }

    /** The marked tiles of the loaded area to the clipboard, as Ground Markers' Export. */
    private void exportTiles()
    {
        WorldView wv = client.getTopLevelWorldView();
        int[] regions = wv == null ? null : wv.getMapRegions();
        if (regions == null) { return; }
        List<MarkerSources.TilePoint> points = new ArrayList<>();
        for (int region : regions) { points.addAll(sources.tiles(region)); }
        if (points.isEmpty())
        {
            message("You have no marked tiles to export.");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(gson.toJson(points)), null);
        message(points.size() + " marked tiles were copied to your clipboard.");
    }

    /** Ground Markers' Clear: the marked tiles of the loaded area, after a confirmation. */
    private void promptClear()
    {
        WorldView wv = client.getTopLevelWorldView();
        int[] regions = wv == null ? null : wv.getMapRegions();
        if (regions == null) { return; }
        int n = 0;
        for (int region : regions) { n += sources.tiles(region).size(); }
        if (n == 0)
        {
            message("You have no marked tiles to clear.");
            return;
        }
        int cleared = n;
        chatbox.openTextMenuInput("Are you sure you want to clear the<br>" + n + " marked tiles of this area?")
            .option("Yes", () -> clientThread.invokeLater(() -> {
                for (int region : regions) { saveTiles(region, Collections.emptyList()); }
                message(cleared + (cleared == 1 ? " marked tile was cleared." : " marked tiles were cleared."));
            }))
            .option("No", () -> { })
            .build();
    }

    /** Tiles from the clipboard (Ground Markers' Export, or this one's), after a confirmation. */
    private void promptImport()
    {
        String text;
        try
        {
            text = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).toString();
        }
        catch (Exception ex)
        {
            message("Unable to read the clipboard.");
            return;
        }
        List<MarkerSources.TilePoint> imported = MarkerSources.parse(gson, text);
        if (imported.isEmpty())
        {
            message("You do not have any ground markers copied in your clipboard.");
            return;
        }
        chatbox.openTextMenuInput("Are you sure you want to import " + imported.size() + " tile markers?")
            .option("Yes", () -> clientThread.invokeLater(() -> importTiles(imported)))
            .option("No", () -> { })
            .build();
    }

    /** As Ground Markers' import: added to the tiles marked in each region, tiles marked already kept as they are. */
    void importTiles(List<MarkerSources.TilePoint> imported)
    {
        Map<Integer, List<MarkerSources.TilePoint>> regions = new HashMap<>();
        for (MarkerSources.TilePoint p : imported) { regions.computeIfAbsent(p.regionId, r -> new ArrayList<>()).add(p); }
        int added = addTiles(regions);
        message(added + " tile markers were imported from the clipboard.");
    }

    /** Adds tiles to those marked in their region; tiles marked already stay as they are. Returns how many were new. */
    private int addTiles(Map<Integer, List<MarkerSources.TilePoint>> regions)
    {
        int added = 0;
        for (Map.Entry<Integer, List<MarkerSources.TilePoint>> region : regions.entrySet())
        {
            List<MarkerSources.TilePoint> merged = new ArrayList<>(sources.tiles(region.getKey()));
            int before = merged.size();
            for (MarkerSources.TilePoint p : region.getValue())
            {
                if (merged.stream().noneMatch(p::same)) { merged.add(p); }
            }
            if (merged.size() > before) { saveTiles(region.getKey(), merged); added += merged.size() - before; }
        }
        return added;
    }

    // Copying other plugins' marks

    /** The plugins whose marks and options a first start takes over: settings group, name. */
    private static final String[][] PLUGINS = {
        {GROUND_MARKERS, "Ground Markers"}, {OBJECT_MARKERS, "Object Markers"}, {NPC_INDICATORS, "NPC Indicators"},
        {BETTER_NPC_HIGHLIGHT, "Better NPC Highlight"}, {TILE_INDICATORS, "Tile Indicators"}, {AGILITY, "Agility"},
    };

    /** Saved forms of the defaults below: booleans, colors (ARGB) and widths as RuneLite saves them. */
    private static final String YES = "true", NO = "false", TWO = "2.0", YELLOW = "-256", CYAN = "-16711681", CYAN_FILL = "335609855",
        BLACK_FILL = "838860800", RED = "-65536";

    /**
     * Their option, ours (null: the same name) and their default, per plugin settings group. An option differs from its
     * default when the player changed it there; only those are taken over. NPC Indicators' one color and fill are those of
     * its tile styles, and of the hull and outline when it had them on (the fifth: its option for the style); otherwise
     * those keep ours, as does the clickbox, which it does not have (""). Its styles become the Tag styles (copySettings).
     * With Better NPC Highlight installed, the NPC options are its colors per style instead, and NPC Indicators' are left.
     */
    private static final String[][] SETTINGS = {
        {GROUND_MARKERS, "markerColor", "tileColor", YELLOW}, {GROUND_MARKERS, "fillOpacity", "tileFillOpacity", "50"},
        {GROUND_MARKERS, "borderWidth", "tileBorderWidth", TWO},
        {OBJECT_MARKERS, "markerColor", "objectColor", YELLOW}, {OBJECT_MARKERS, "fillColor", "objectFillColor", null},
        {OBJECT_MARKERS, "highlightHull", "objectHull", YES}, {OBJECT_MARKERS, "highlightOutline", "objectOutline", NO},
        {OBJECT_MARKERS, "highlightClickbox", "objectClickbox", NO}, {OBJECT_MARKERS, "highlightTile", "objectTile", NO},
        {OBJECT_MARKERS, "borderWidth", "objectBorderWidth", TWO},
        {NPC_INDICATORS, "npcColor", "npcHullColor", CYAN, "highlightHull"}, {NPC_INDICATORS, "npcColor", "npcTileColor", CYAN},
        {NPC_INDICATORS, "npcColor", "npcTrueTileColor", CYAN}, {NPC_INDICATORS, "npcColor", "npcSouthWestTileColor", CYAN},
        {NPC_INDICATORS, "npcColor", "npcSouthWestTrueTileColor", CYAN}, {NPC_INDICATORS, "npcColor", "npcOutlineColor", CYAN, "highlightOutline"},
        {NPC_INDICATORS, "npcColor", "npcClickboxColor", CYAN, ""},
        {NPC_INDICATORS, "fillColor", "npcHullFill", CYAN_FILL, "highlightHull"}, {NPC_INDICATORS, "fillColor", "npcTileFill", CYAN_FILL},
        {NPC_INDICATORS, "fillColor", "npcTrueTileFill", CYAN_FILL}, {NPC_INDICATORS, "fillColor", "npcSouthWestTileFill", CYAN_FILL},
        {NPC_INDICATORS, "fillColor", "npcSouthWestTrueTileFill", CYAN_FILL}, {NPC_INDICATORS, "fillColor", "npcClickboxFill", CYAN_FILL, ""},
        {NPC_INDICATORS, "borderWidth", "npcBorderWidth", TWO}, {NPC_INDICATORS, "ignoreDeadNpcs", "npcIgnoreDead", YES},
        {NPC_INDICATORS, "ignorePets", "npcIgnorePets", YES},
        {BETTER_NPC_HIGHLIGHT, "hullColor", "npcHullColor", CYAN}, {BETTER_NPC_HIGHLIGHT, "hullFillColor", "npcHullFill", CYAN_FILL},
        {BETTER_NPC_HIGHLIGHT, "tileColor", "npcTileColor", CYAN}, {BETTER_NPC_HIGHLIGHT, "tileFillColor", "npcTileFill", CYAN_FILL},
        {BETTER_NPC_HIGHLIGHT, "trueTileColor", "npcTrueTileColor", CYAN}, {BETTER_NPC_HIGHLIGHT, "trueTileFillColor", "npcTrueTileFill", CYAN_FILL},
        {BETTER_NPC_HIGHLIGHT, "swTileColor", "npcSouthWestTileColor", CYAN}, {BETTER_NPC_HIGHLIGHT, "swTileFillColor", "npcSouthWestTileFill", CYAN_FILL},
        {BETTER_NPC_HIGHLIGHT, "swTrueTileColor", "npcSouthWestTrueTileColor", CYAN},
        {BETTER_NPC_HIGHLIGHT, "swTrueTileFillColor", "npcSouthWestTrueTileFill", CYAN_FILL},
        {BETTER_NPC_HIGHLIGHT, "outlineColor", "npcOutlineColor", CYAN}, {BETTER_NPC_HIGHLIGHT, "clickboxColor", "npcClickboxColor", CYAN},
        {BETTER_NPC_HIGHLIGHT, "clickboxFillColor", "npcClickboxFill", CYAN_FILL},
        {TILE_INDICATORS, "highlightDestinationTile", null, YES}, {TILE_INDICATORS, "highlightDestinationColor", null, "-8355712"},
        {TILE_INDICATORS, "destinationTileFillColor", null, BLACK_FILL}, {TILE_INDICATORS, "destinationTileBorderWidth", null, TWO},
        {TILE_INDICATORS, "highlightHoveredTile", null, NO}, {TILE_INDICATORS, "highlightHoveredColor", null, "0"},
        {TILE_INDICATORS, "hoveredTileFillColor", null, BLACK_FILL}, {TILE_INDICATORS, "hoveredTileBorderWidth", null, TWO},
        {TILE_INDICATORS, "highlightCurrentTile", null, NO}, {TILE_INDICATORS, "highlightCurrentColor", null, CYAN},
        {TILE_INDICATORS, "currentTileFillColor", null, BLACK_FILL}, {TILE_INDICATORS, "currentTileBorderWidth", null, TWO},
        {AGILITY, "showClickboxes", "agilityObstacles", YES}, {AGILITY, "overlayColor", "agilityColor", "-16711936"},
        {AGILITY, "highlightShortcuts", "agilityShortcuts", YES}, {AGILITY, "highlightMarks", "agilityMarks", YES},
        {AGILITY, "markHighlight", "agilityMarkColor", RED}, {AGILITY, "highlightPortals", "agilityPortals", YES},
        {AGILITY, "portalsHighlight", "agilityPortalColor", "-65281"}, {AGILITY, "trapOverlay", "agilityTraps", YES},
        {AGILITY, "trapHighlight", "agilityTrapColor", RED}, {AGILITY, "highlightStick", "agilityStick", YES},
        {AGILITY, "stickHighlightColor", "agilityStickColor", RED},
        {AGILITY, "highlightSepulchreObstacles", "agilitySepulchreObstacles", YES},
        {AGILITY, "highlightSepulchreSkilling", "agilitySepulchreSkilling", YES},
    };

    /**
     * The first start in a profile: the marks of Ground Markers, Object Markers and NPC Indicators (Better NPC Highlight
     * when it is installed) are copied over, and the options changed in those plugins, in Agility and in Tile Indicators
     * while it is on.
     */
    private void copyOnce()
    {
        // NPC names come from the game's cache, which the client has loaded by the login screen.
        GameState state = client.getGameState();
        if (state == null || state.getState() < GameState.LOGIN_SCREEN.getState()
            || configs.getConfiguration(HdWorldMarkersConfig.GROUP, COPIED) != null) { return; }
        configs.setConfiguration(HdWorldMarkersConfig.GROUP, COPIED, "true");
        boolean better = betterNpcHighlight();
        Set<String> from = copySettings(better);
        int tiles = copyGroundMarkers(), objectCount = copyObjectMarkers();
        int names = better ? copyBetterNpcHighlight() : copyNpcNames(), colors = better ? 0 : copyNpcColors();
        if (tiles > 0) { from.add(GROUND_MARKERS); }
        if (objectCount > 0) { from.add(OBJECT_MARKERS); }
        if (names + colors > 0) { from.add(better ? BETTER_NPC_HIGHLIGHT : NPC_INDICATORS); }
        if (from.isEmpty()) { return; }
        // Which of them still draw the same is OverlapWarnings' to say.
        List<String> plugins = new ArrayList<>();
        for (String[] plugin : PLUGINS) { if (from.contains(plugin[0])) { plugins.add(plugin[1]); } }
        String marks = describe(tiles, objectCount, names);
        notice("HD World Markers took over your marks and settings from " + Chat.list(plugins) + (marks == null ? "" : " (" + marks + ")") + "."
            + " Sync on the world map orb copies what you mark there later.");
    }

    /**
     * The options the player changed in RuneLite's marking plugins (and Tile Indicators' while it is on) become ours;
     * returns their settings groups. RuneLite saves every option's default, so a changed one is one that differs from it.
     * The NPC options are Better NPC Highlight's when it is installed, else NPC Indicators'.
     */
    private Set<String> copySettings(boolean better)
    {
        Set<String> from = new HashSet<>();
        Plugin tilePlugin = OverlapWarnings.plugin(plugins, OverlapWarnings.TILE_INDICATORS);
        boolean tileIndicators = tilePlugin != null && plugins.isPluginEnabled(tilePlugin);
        for (String[] setting : SETTINGS)
        {
            if (setting[0].equals(better ? NPC_INDICATORS : BETTER_NPC_HIGHLIGHT)) { continue; }
            String ours = setting[2] != null ? setting[2] : setting[1];
            // A style NPC Indicators did not have on: ours, as its default.
            if (setting.length > 4 && !styleOn(setting[4])) { configs.unsetConfiguration(HdWorldMarkersConfig.GROUP, ours); continue; }
            String value = setting[0].equals(TILE_INDICATORS) && !tileIndicators ? null : configs.getConfiguration(setting[0], setting[1]);
            if (value == null || value.equals(setting[3])) { continue; }
            configs.setConfiguration(HdWorldMarkersConfig.GROUP, ours, value);
            from.add(setting[0]);
        }
        // The styles NPC Indicators draws, when changed there from its hull: what Tag-All gives here too.
        Set<NpcStyle> theirs = better ? EnumSet.noneOf(NpcStyle.class) : npcIndicatorsStyles();
        if (!theirs.isEmpty() && !theirs.equals(EnumSet.of(NpcStyle.HULL)))
        {
            configs.setConfiguration(HdWorldMarkersConfig.GROUP, TAG_STYLES, gson.toJson(theirs));
            from.add(NPC_INDICATORS);
        }
        return from;
    }

    /** NPC Indicators' option per style, in NpcStyle order; "" for the clickbox, which it does not have. */
    private static final String[] NPC_INDICATORS_STYLES = {"highlightHull", "highlightTile", "highlightTrueTile", "highlightSouthWestTile",
        "highlightSouthWestTrueTile", "highlightOutline", ""};
    private static final String TAG_STYLES = "npcTagStyles";

    /** The styles NPC Indicators has on. */
    private Set<NpcStyle> npcIndicatorsStyles()
    {
        Set<NpcStyle> on = EnumSet.noneOf(NpcStyle.class);
        for (NpcStyle style : NpcStyle.values()) { if (styleOn(NPC_INDICATORS_STYLES[style.ordinal()])) { on.add(style); } }
        return on;
    }

    /** Whether NPC Indicators has this style on (its hull is, unless changed); "" for a style it does not have. */
    private boolean styleOn(String option)
    {
        if (option.isEmpty()) { return false; }
        String value = configs.getConfiguration(NPC_INDICATORS, option);
        return "true".equals(value != null ? value : String.valueOf(option.equals("highlightHull")));
    }

    /** Sync: what the other plugins marked since is copied. */
    private void sync()
    {
        String copied = copyOtherMarks();
        message(copied == null ? "No new marks in Ground Markers, Object Markers or " + (betterNpcHighlight() ? "Better NPC Highlight" : "NPC Indicators") + "."
            : "Copied " + copied + ".");
    }

    /**
     * Copies what Ground Markers, Object Markers and NPC Indicators (Better NPC Highlight when it is installed) saved and is
     * not marked here yet; their settings are only read. Returns what was copied ("12 tiles and 2 NPC names"), or null
     * when nothing was new.
     */
    String copyOtherMarks()
    {
        boolean better = betterNpcHighlight();
        int tiles = copyGroundMarkers(), objectCount = copyObjectMarkers();
        int names = better ? copyBetterNpcHighlight() : copyNpcNames(), colors = better ? 0 : copyNpcColors();
        String marks = describe(tiles, objectCount, names);
        return marks == null && colors > 0 ? colors + " NPC color" + (colors == 1 ? "" : "s") : marks;
    }

    /** "12 tiles, 3 objects and 2 NPC names", or null when all are 0. */
    private static String describe(int tiles, int objectCount, int names)
    {
        List<String> parts = new ArrayList<>();
        count(parts, tiles, "tile");
        count(parts, objectCount, "object");
        count(parts, names, "NPC name");
        return parts.isEmpty() ? null : Chat.list(parts);
    }

    private static void count(List<String> parts, int n, String what) { if (n > 0) { parts.add(n + " " + what + (n == 1 ? "" : "s")); } }

    private int copyGroundMarkers()
    {
        Map<Integer, List<MarkerSources.TilePoint>> regions = new HashMap<>();
        for (int region : regions(GROUND_MARKERS))
        {
            List<MarkerSources.TilePoint> points = MarkerSources.parse(gson, configs.getConfiguration(GROUND_MARKERS, "region_" + region));
            points.removeIf(p -> p.regionId != region);
            regions.put(region, points);
        }
        return addTiles(regions);
    }

    private int copyObjectMarkers()
    {
        int added = 0;
        for (int region : regions(OBJECT_MARKERS))
        {
            List<ObjectMarkerSource.ObjectPoint> points = objects.saved(region);
            int changed = 0;
            for (ObjectMarkerSource.ObjectPoint p : ObjectMarkerSource.parse(gson, configs.getConfiguration(OBJECT_MARKERS, "region_" + region)))
            {
                ObjectMarkerSource.ObjectPoint own = points.stream().filter(p::same).findFirst().orElse(null);
                if (own == null) { points.add(p); changed++; }
                // Marked here too, without a color or style of its own: Object Markers' are taken.
                else if (!own.hasLook() && p.hasLook()) { own.borderColor = p.borderColor; own.fillColor = p.fillColor;
                    own.hull = p.hull; own.outline = p.outline; own.clickbox = p.clickbox; own.tile = p.tile; changed++; }
            }
            if (changed > 0) { saveObjects(region, points); added += changed; }
        }
        return added;
    }

    /**
     * NPC Indicators' names not tagged here yet, in the lists of the styles it draws them with: its Tag style of an NPC
     * with that name (saved per NPC id), else the styles it has on. Returns how many were new.
     */
    private int copyNpcNames()
    {
        Map<String, Set<NpcStyle>> idStyles = new HashMap<>();
        for (int id : ids(NPC_INDICATORS, "tagstyle_"))
        {
            String name = npcName(id);
            NpcStyle style = name == null ? null : style(configs.getConfiguration(NPC_INDICATORS, "tagstyle_" + id));
            if (style != null) { idStyles.computeIfAbsent(name, k -> EnumSet.noneOf(NpcStyle.class)).add(style); }
        }
        Set<NpcStyle> on = npcIndicatorsStyles();
        NameLists lists = new NameLists();
        int added = 0;
        for (String typed : Text.fromCSV(Strings.nullToEmpty(configs.getConfiguration(NPC_INDICATORS, "npcToHighlight"))))
        {
            // A name saved with colour tags (<col=...>) matches no NPC as it is.
            String name = Text.removeTags(typed).trim();
            if (name.isEmpty()) { continue; }
            Set<NpcStyle> styles = idStyles.getOrDefault(Text.standardize(name), on);
            if (lists.tagged(name) || styles.isEmpty()) { continue; }
            for (NpcStyle style : styles) { lists.set(style, name, true); }
            added++;
        }
        lists.save();
        return added;
    }

    /** The regions a plugin saved marks for: its settings region_<id>. */
    private List<Integer> regions(String group) { return ids(group, "region_"); }

    /** The numbers of a plugin's settings named prefix<number>. */
    private List<Integer> ids(String group, String prefix)
    {
        String whole = ConfigManager.getWholeKey(group, null, prefix);
        List<Integer> ids = new ArrayList<>();
        for (String key : configs.getConfigurationKeys(whole))
        {
            try { ids.add(Integer.parseInt(key.substring(whole.length()))); }
            catch (NumberFormatException ignored) { /* Not one of them. */ }
        }
        return ids;
    }

    /**
     * NPC Indicators' Tag color, saved per NPC id, as the color of the NPC's name (an NPC's id is never kept); a name's
     * own is kept. Returns how many were new.
     */
    private int copyNpcColors()
    {
        Map<String, MarkerSources.NpcTag> tags = MarkerSources.npcTags(configs, gson);
        int added = 0;
        for (int id : ids(NPC_INDICATORS, "highlightcolor_"))
        {
            String name = npcName(id);
            Color color = name == null ? null : configs.getConfiguration(NPC_INDICATORS, "highlightcolor_" + id, Color.class);
            MarkerSources.NpcTag tag = color == null ? null : tags.computeIfAbsent(name, k -> new MarkerSources.NpcTag());
            if (tag != null && tag.color == null) { tag.color = color; added++; }
        }
        if (added > 0) { saveNpcTags(tags); }
        return added;
    }

    /**
     * Whether Better NPC Highlight is installed, on or not (turned off for drawing the same as here): its NPC lists and
     * options are then the ones the player keeps, and NPC Indicators' are left alone.
     */
    private boolean betterNpcHighlight() { return OverlapWarnings.plugin(plugins, OverlapWarnings.BETTER_NPC_HIGHLIGHT) != null; }

    /** Better NPC Highlight's option prefix per style, in NpcStyle order: its tileNames, trueTileNames, ... and tileHighlight, .... */
    private static final String[] BETTER_NPC_HIGHLIGHT_STYLES = {"hull", "tile", "trueTile", "swTile", "swTrueTile", "outline", "clickbox"};

    /**
     * Better NPC Highlight's names per style, of the styles it has on, into the same style's list here. Only its name
     * lists: its ID lists are left alone (no IDs here), as is a name's preset (":2"). Returns how many names were new.
     */
    private int copyBetterNpcHighlight()
    {
        NameLists lists = new NameLists();
        Set<String> added = new HashSet<>();
        for (NpcStyle style : NpcStyle.values())
        {
            String option = BETTER_NPC_HIGHLIGHT_STYLES[style.ordinal()];
            if ("false".equals(configs.getConfiguration(BETTER_NPC_HIGHLIGHT, option + "Highlight"))) { continue; }
            String list = configs.getConfiguration(BETTER_NPC_HIGHLIGHT, option + "Names");
            for (String entry : list == null ? new String[0] : list.split("(?<!\\\\),"))
            {
                String name = Text.removeTags(entry.split(":")[0]).trim();
                // A name with an escaped comma cannot go into a list here.
                if (name.isEmpty() || name.contains("\\") || lists.has(style, name)) { continue; }
                lists.set(style, name, true);
                added.add(name.toLowerCase());
            }
        }
        lists.save();
        return added.size();
    }

    /** An NPC's name (standardized) from the game's cache, or null. */
    private String npcName(int id)
    {
        NPCComposition composition = client.getNpcDefinition(id);
        String name = composition == null ? null : composition.getName();
        return name == null || name.equals("null") ? null : Text.standardize(name);
    }

    // Objects

    private void objectOptions(MenuEntryAdded event)
    {
        MenuEntry entry = event.getMenuEntry();
        WorldView wv = client.getWorldView(entry.getWorldViewId());
        TileObject object = wv == null ? null : findTileObject(wv, event.getActionParam0(), event.getActionParam1(), event.getIdentifier());
        if (object == null) { return; }
        ObjectMarkerSource.Marked marked = objects.find(object);
        client.getMenu().createMenuEntry(-1)
            .setOption(marked != null ? "Unmark object" : "Mark object")
            .setTarget(event.getTarget())
            .setWorldViewId(entry.getWorldViewId())
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setIdentifier(event.getIdentifier())
            .setType(MenuAction.RUNELITE)
            .onClick(this::markObject);
        if (marked != null) { objectMenus(event.getTarget(), object, marked, wv.getMapRegions()); }
    }

    /** Object Markers' Mark border color, Mark fill color and Mark style menus. */
    private void objectMenus(String target, TileObject object, ObjectMarkerSource.Marked marked, int[] regions)
    {
        Menu border = client.getMenu().createMenuEntry(-2).setOption("Mark border color").setTarget(target)
            .setType(MenuAction.RUNELITE).createSubMenu();
        for (Color c : withDefaults(objects.usedColors(regions, false), 1))
        {
            border.createMenuEntry(0).setOption(ColorUtil.prependColorTag("Set color", c)).setType(MenuAction.RUNELITE)
                .onClick(e -> updateObject(object, p -> p.borderColor = c));
        }
        border.createMenuEntry(0).setOption("Pick color").setType(MenuAction.RUNELITE).onClick(e -> pick(
            marked.borderColor != null ? marked.borderColor : config.objectColor(), "Mark Border Color", c -> updateObject(object, p -> p.borderColor = c)));
        if (marked.borderColor != null)
        {
            border.createMenuEntry(0).setOption("Reset").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> p.borderColor = null));
        }

        Menu fill = client.getMenu().createMenuEntry(-3).setOption("Mark fill color").setTarget(target)
            .setType(MenuAction.RUNELITE).createSubMenu();
        for (Color c : withDefaults(objects.usedColors(regions, true), 12))
        {
            fill.createMenuEntry(0).setOption(ColorUtil.prependColorTag("Set color", c)).setType(MenuAction.RUNELITE)
                .onClick(e -> updateObject(object, p -> p.fillColor = c));
        }
        // The fill differs per style; the hull's default (a=50) to start from.
        fill.createMenuEntry(0).setOption("Pick color").setType(MenuAction.RUNELITE).onClick(e -> pick(
            marked.fillColor != null ? marked.fillColor : Marker.BLACK_FILL, "Mark Fill Color", c -> updateObject(object, p -> p.fillColor = c)));
        fill.createMenuEntry(0).setOption("Reset").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> p.fillColor = null));

        Menu style = client.getMenu().createMenuEntry(-4).setOption("Mark style").setTarget(target)
            .setType(MenuAction.RUNELITE).createSubMenu();
        style.createMenuEntry(0).setOption("Hull").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> p.hull = p.hull != Boolean.TRUE));
        style.createMenuEntry(0).setOption("Outline").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> p.outline = p.outline != Boolean.TRUE));
        style.createMenuEntry(0).setOption("Clickbox").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> p.clickbox = p.clickbox != Boolean.TRUE));
        style.createMenuEntry(0).setOption("Tile").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> p.tile = p.tile != Boolean.TRUE));
        style.createMenuEntry(0).setOption("Reset").setType(MenuAction.RUNELITE).onClick(e -> updateObject(object, p -> {
            p.hull = null; p.outline = null; p.clickbox = null; p.tile = null;
        }));
    }

    /** Changes the saved mark of this object, as Object Markers' updateObjectConfig. */
    private void updateObject(TileObject object, Consumer<ObjectMarkerSource.ObjectPoint> change)
    {
        WorldPoint point = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
        int region = point.getRegionID();
        ObjectComposition composition = composition(object);
        String name = composition == null ? null : composition.getName();
        List<ObjectMarkerSource.ObjectPoint> points = objects.saved(region);
        for (ObjectMarkerSource.ObjectPoint p : points)
        {
            if (matches(p, object, name, point))
            {
                change.accept(p);
                saveObjects(region, points);
                return;
            }
        }
    }

    /** The same object, or a multiloc marked under another name, or another id spawned with the same name. */
    private static boolean matches(ObjectMarkerSource.ObjectPoint p, TileObject object, String name, WorldPoint point)
    {
        return (p.id == object.getId() || p.name.equals(name))
            && p.regionX == point.getRegionX() && p.regionY == point.getRegionY() && p.z == point.getPlane();
    }

    /** The object as it is seen now: a multiloc's current form. */
    private ObjectComposition composition(TileObject object)
    {
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        return composition != null && composition.getImpostorIds() != null ? composition.getImpostor() : composition;
    }

    private void markObject(MenuEntry entry)
    {
        WorldView wv = client.getWorldView(entry.getWorldViewId());
        TileObject object = wv == null ? null : findTileObject(wv, entry.getParam0(), entry.getParam1(), entry.getIdentifier());
        if (object == null) { return; }
        // The object's id is its base id; the composition is the object as it is seen now.
        ObjectComposition composition = composition(object);
        String name = composition == null ? null : composition.getName();
        // Objects without a name are ambiguous: not marked, as in Object Markers.
        if (Strings.isNullOrEmpty(name) || name.equals("null")) { return; }
        // On the object's own floor, as the marks are matched (ObjectMarkerSource.check).
        WorldPoint point = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
        int region = point.getRegionID();
        List<ObjectMarkerSource.ObjectPoint> points = objects.saved(region);
        boolean removed = points.removeIf(p -> matches(p, object, name, point));
        if (!removed) { points.add(new ObjectMarkerSource.ObjectPoint(object.getId(), name, point.getRegionX(), point.getRegionY(), point.getPlane())); }
        saveObjects(region, points);
    }

    private void saveObjects(int region, List<ObjectMarkerSource.ObjectPoint> points) { save(ObjectMarkerSource.KEY + region, points, points.isEmpty()); }

    /** The object with this id on a scene tile, as Object Markers' findTileObject. */
    private TileObject findTileObject(WorldView wv, int x, int y, int id)
    {
        Scene scene = wv.getScene();
        Tile[][][] tiles = scene == null ? null : scene.getTiles();
        int plane = wv.getPlane();
        if (tiles == null || x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length) { return null; }
        Tile tile = tiles[plane][x][y];
        if (tile == null) { return null; }
        if (idEquals(tile.getWallObject(), id)) { return tile.getWallObject(); }
        if (idEquals(tile.getDecorativeObject(), id)) { return tile.getDecorativeObject(); }
        if (idEquals(tile.getGroundObject(), id)) { return tile.getGroundObject(); }
        for (GameObject object : tile.getGameObjects()) { if (idEquals(object, id)) { return object; } }
        return null;
    }

    /** Examine sends the id of the object as it is seen (a multiloc's current form), not its base id. */
    private boolean idEquals(TileObject object, int id)
    {
        if (object == null) { return false; }
        if (object.getId() == id) { return true; }
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        int[] impostors = composition == null ? null : composition.getImpostorIds();
        if (impostors != null) { for (int impostor : impostors) { if (impostor == id) { return true; } } }
        return false;
    }

    // NPCs

    private void npcOptions(MenuEntryAdded event)
    {
        NPC npc = event.getMenuEntry().getNpc();
        String name = npc == null ? null : npc.getName();
        if (name == null) { return; }
        NameLists lists = new NameLists();
        boolean tagged = lists.tagged(name), pattern = lists.pattern(name);
        // A name matched by a pattern (with *) has no Tag-All: Un-tag-All cannot remove the pattern. No Tag styles: no Tag-All.
        if (!pattern && (tagged || !tagStyles().isEmpty()))
        {
            client.getMenu().createMenuEntry(-1)
                .setOption(tagged ? "Un-tag-All" : "Tag-All")
                .setTarget(event.getTarget())
                .setType(MenuAction.RUNELITE)
                .onClick(e -> tagAll(name));
        }
        if (tagged || pattern) { npcMenus(event.getTarget(), name); }
    }

    /** The Tag styles option, without what an unknown saved style reads as; none for an unreadable save. */
    private Set<NpcStyle> tagStyles()
    {
        Set<NpcStyle> styles = EnumSet.noneOf(NpcStyle.class), saved = config.npcTagStyles();
        for (NpcStyle style : saved == null ? styles : saved) { if (style != null) { styles.add(style); } }
        return styles;
    }

    /** NPC Indicators' Tag color and Tag style menus, per name. */
    private void npcMenus(String target, String name)
    {
        Map<String, MarkerSources.NpcTag> tags = MarkerSources.npcTags(configs, gson);
        MarkerSources.NpcTag own = tags.get(Text.standardize(name));
        List<Color> used = new ArrayList<>();
        for (MarkerSources.NpcTag t : tags.values()) { offer(used, t.color); }
        Menu colors = client.getMenu().createMenuEntry(-2).setOption("Tag color").setTarget(target).setType(MenuAction.RUNELITE).createSubMenu();
        for (Color c : withDefaults(used, 1))
        {
            colors.createMenuEntry(0).setOption(ColorUtil.prependColorTag("Set color", c)).setType(MenuAction.RUNELITE)
                .onClick(e -> updateNpc(name, t -> t.color = c));
        }
        colors.createMenuEntry(0).setOption("Pick color").setType(MenuAction.RUNELITE)
            .onClick(e -> pick(own != null && own.color != null ? own.color : Color.WHITE, "Tag Color", c -> updateNpc(name, t -> t.color = c)));
        if (own != null && own.color != null)
        {
            colors.createMenuEntry(0).setOption("Reset").setType(MenuAction.RUNELITE).onClick(e -> updateNpc(name, t -> t.color = null));
        }
        Menu styles = client.getMenu().createMenuEntry(-3).setOption("Tag style").setTarget(target).setType(MenuAction.RUNELITE).createSubMenu();
        // Each puts the name in its style's list or takes it out.
        for (NpcStyle style : NpcStyle.values())
        {
            styles.createMenuEntry(0).setOption(style.label).setType(MenuAction.RUNELITE).onClick(e -> {
                NameLists lists = new NameLists();
                lists.set(style, name, !lists.has(style, name));
                lists.save();
            });
        }
    }

    /** Changes a name's own color; a name without one is removed. */
    private void updateNpc(String name, Consumer<MarkerSources.NpcTag> change)
    {
        Map<String, MarkerSources.NpcTag> tags = MarkerSources.npcTags(configs, gson);
        String key = Text.standardize(name);
        MarkerSources.NpcTag tag = tags.computeIfAbsent(key, k -> new MarkerSources.NpcTag());
        change.accept(tag);
        if (tag.color == null) { tags.remove(key); }
        saveNpcTags(tags);
    }

    private void saveNpcTags(Map<String, MarkerSources.NpcTag> tags) { save(MarkerSources.NPC_TAGS, tags, tags.isEmpty()); }

    /** Tag-All: the name joins the lists of the Tag styles. Un-tag-All: it leaves every list. */
    private void tagAll(String name)
    {
        NameLists lists = new NameLists();
        Set<NpcStyle> styles = lists.tagged(name) ? EnumSet.noneOf(NpcStyle.class) : tagStyles();
        for (NpcStyle style : NpcStyle.values()) { lists.set(style, name, styles.contains(style)); }
        lists.save();
    }

    /** The NPC styles' lists of names as saved, changed here and saved together; only the lists that changed are written. */
    private final class NameLists
    {
        private final Map<NpcStyle, List<String>> names = new EnumMap<>(NpcStyle.class);
        private final Set<NpcStyle> changed = EnumSet.noneOf(NpcStyle.class);

        NameLists() { for (NpcStyle style : NpcStyle.values()) { names.put(style, new ArrayList<>(MarkerSources.names(configs, style))); } }

        /** Whether the name itself (not a pattern) is in this style's list. */
        boolean has(NpcStyle style, String name) { return names.get(style).stream().anyMatch(name::equalsIgnoreCase); }

        /** Whether the name itself is in any list. */
        boolean tagged(String name) { return names.keySet().stream().anyMatch(s -> has(s, name)); }

        /** Whether a pattern in any list (with *, not the name itself) matches the name. */
        boolean pattern(String name)
        {
            return names.values().stream().flatMap(List::stream)
                .anyMatch(n -> n.contains("*") && !n.equalsIgnoreCase(name) && WildcardMatcher.matches(n, name));
        }

        /** Puts the name in a style's list, or takes it out. */
        void set(NpcStyle style, String name, boolean on)
        {
            if (has(style, name) == on) { return; }
            if (on) { names.get(style).add(name); } else { names.get(style).removeIf(name::equalsIgnoreCase); }
            changed.add(style);
        }

        void save()
        {
            for (NpcStyle style : changed) { configs.setConfiguration(HdWorldMarkersConfig.GROUP, style.key, Text.toCSV(names.get(style))); }
            changed.clear();
        }
    }

    /** A style by the name NPC Indicators saves it under (tagstyle_), or null. */
    private static NpcStyle style(String saved)
    {
        for (NpcStyle style : NpcStyle.values()) { if (style.saved.equals(saved)) { return style; } }
        return null;
    }

    private void message(String text) { Chat.send(chat, text, false); }

    /** A notice shown when logged in: now, or at the next login. */
    private String pendingNotice;

    private void notice(String text)
    {
        if (client.getGameState() == GameState.LOGGED_IN) { message(text); } else { pendingNotice = text; }
    }

}

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
 * labelTile, and GroundMarkerSharingManager's import and export), Object Markers (ObjectIndicatorsPlugin.onMenuEntryAdded,
 * markObject, findTileObject) and NPC Indicators (NpcIndicatorsPlugin.onMenuEntryAdded, tag) of
 * https://github.com/runelite/runelite, tag runelite-parent-1.13.1, BSD 2-Clause License; see META-INF/LICENSE-runelite
 * and THIRD_PARTY_NOTICES.md.
 * Changes for In-World Tile Markers: saved in its own settings, marks without a color of their own (all but imported
 * tiles) follow the color options, and the marks those plugins saved can be copied (their settings are only read).
 */
package com.inworldtilemarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.menus.WidgetMenuOption;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

/**
 * Marking with Shift + right-click: tiles (Mark, Unmark, Label), objects (Mark object, Unmark object) and NPCs by name
 * (Tag-All, Un-tag-All), saved in In-World Tile Markers' own settings. Tiles can be imported from and exported to
 * the clipboard in Ground Markers' format. The marks of Ground Markers, Object Markers and NPC Indicators are copied
 * when In-World Tile Markers first starts in a profile, and again with Sync.
 */
@Singleton
final class Marking
{
    private static final String TILE = "Tile", NPC_NAMES = "npcNames";
    private static final WidgetMenuOption EXPORT = new WidgetMenuOption("Export", "In-World Tile Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    private static final WidgetMenuOption IMPORT = new WidgetMenuOption("Import", "In-World Tile Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    private static final WidgetMenuOption SYNC = new WidgetMenuOption("Sync", "In-World Tile Markers",
        InterfaceID.Orbs.WORLDMAP, InterfaceID.OrbsNomap.WORLDMAP);
    /** Set in a profile once the other plugins' marks were copied there. */
    static final String COPIED = "copiedOtherMarks";
    /** The settings groups of Ground Markers, Object Markers and NPC Indicators. */
    private static final String GROUND_MARKERS = "groundMarker", OBJECT_MARKERS = "objectindicators", NPC_INDICATORS = "npcindicators";

    private final Client client;
    private final ClientThread clientThread;
    private final ConfigManager configs;
    private final InWorldTileMarkersConfig config;
    private final MarkerSources sources;
    private final ObjectMarkerSource objects;
    private final MenuManager menus;
    private final ChatboxPanelManager chatbox;
    private final ChatMessageManager chat;
    private final Gson gson;

    @Inject
    Marking(Client client, ClientThread clientThread, ConfigManager configs, InWorldTileMarkersConfig config, MarkerSources sources,
        ObjectMarkerSource objects, MenuManager menus, ChatboxPanelManager chatbox, ChatMessageManager chat, Gson gson)
    {
        this.client = client; this.clientThread = clientThread; this.configs = configs; this.config = config; this.sources = sources;
        this.objects = objects; this.menus = menus; this.chatbox = chatbox; this.chat = chat; this.gson = gson;
    }

    void startUp()
    {
        importExportOptions();
        clientThread.invokeLater(this::copyOnce);
    }

    void shutDown() { menus.removeManagedCustomMenu(EXPORT); menus.removeManagedCustomMenu(IMPORT); menus.removeManagedCustomMenu(SYNC); }

    private void importExportOptions()
    {
        shutDown();
        if (!config.showImportExport()) { return; }
        menus.addManagedCustomMenu(EXPORT, e -> exportTiles());
        menus.addManagedCustomMenu(IMPORT, e -> promptImport());
        menus.addManagedCustomMenu(SYNC, e -> sync());
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (InWorldTileMarkersConfig.GROUP.equals(e.getGroup()) && "showImportExport".equals(e.getKey())) { importExportOptions(); }
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged e)
    {
        importExportOptions();
        clientThread.invokeLater(this::copyOnce);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
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
        }
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
            .onDone((String input) -> clientThread.invokeLater(() -> {
                List<MarkerSources.TilePoint> points = new ArrayList<>(sources.tiles(marked.regionId));
                points.removeIf(marked::same);
                points.add(new MarkerSources.TilePoint(marked.regionId, marked.regionX, marked.regionY, marked.z, marked.color,
                    Strings.emptyToNull(input)));
                saveTiles(marked.regionId, points);
            }))
            .build();
    }

    private void saveTiles(int region, List<MarkerSources.TilePoint> points)
    {
        if (points.isEmpty()) { configs.unsetConfiguration(InWorldTileMarkersConfig.GROUP, MarkerSources.TILES + region); }
        else { configs.setConfiguration(InWorldTileMarkersConfig.GROUP, MarkerSources.TILES + region, gson.toJson(points)); }
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

    /** The first start in a profile: the marks of Ground Markers, Object Markers and NPC Indicators are copied over. */
    private void copyOnce()
    {
        if (configs.getConfiguration(InWorldTileMarkersConfig.GROUP, COPIED) != null) { return; }
        configs.setConfiguration(InWorldTileMarkersConfig.GROUP, COPIED, "true");
        String copied = copyOtherMarks();
        if (copied != null)
        {
            notice("In-World Tile Markers copied your " + copied + ". Turn off the plugins they came from, or they are drawn twice."
                + " Sync on the world map orb copies new ones.");
        }
    }

    /** Sync: what the other plugins marked since is copied. */
    private void sync()
    {
        String copied = copyOtherMarks();
        message(copied == null ? "No new marks in Ground Markers, Object Markers or NPC Indicators." : "Copied " + copied + ".");
    }

    /**
     * Copies what Ground Markers, Object Markers and NPC Indicators saved and is not marked here yet; their settings are
     * only read. Returns what was copied ("12 tiles and 2 NPC names"), or null when nothing was new.
     */
    String copyOtherMarks()
    {
        List<String> parts = new ArrayList<>();
        count(parts, copyGroundMarkers(), "tile");
        count(parts, copyObjectMarkers(), "object");
        count(parts, copyNpcNames(), "NPC name");
        if (parts.isEmpty()) { return null; }
        String last = parts.remove(parts.size() - 1);
        return parts.isEmpty() ? last : String.join(", ", parts) + " and " + last;
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
            int before = points.size();
            for (ObjectMarkerSource.ObjectPoint p : ObjectMarkerSource.parse(gson, configs.getConfiguration(OBJECT_MARKERS, "region_" + region)))
            {
                if (points.stream().noneMatch(o -> o.id == p.id && o.regionX == p.regionX && o.regionY == p.regionY && o.z == p.z)) { points.add(p); }
            }
            if (points.size() > before) { saveObjects(region, points); added += points.size() - before; }
        }
        return added;
    }

    private int copyNpcNames()
    {
        List<String> names = new ArrayList<>(Text.fromCSV(config.npcNames()));
        int before = names.size();
        for (String name : Text.fromCSV(Strings.nullToEmpty(configs.getConfiguration(NPC_INDICATORS, "npcToHighlight"))))
        {
            if (names.stream().noneMatch(name::equalsIgnoreCase)) { names.add(name); }
        }
        if (names.size() > before) { configs.setConfiguration(InWorldTileMarkersConfig.GROUP, NPC_NAMES, Text.toCSV(names)); }
        return names.size() - before;
    }

    /** The regions a plugin saved marks for: its settings region_<id>. */
    private List<Integer> regions(String group)
    {
        String prefix = ConfigManager.getWholeKey(group, null, "region_");
        List<Integer> regions = new ArrayList<>();
        for (String key : configs.getConfigurationKeys(prefix))
        {
            try { regions.add(Integer.parseInt(key.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { /* Not a region. */ }
        }
        return regions;
    }

    // Objects

    private void objectOptions(MenuEntryAdded event)
    {
        MenuEntry entry = event.getMenuEntry();
        WorldView wv = client.getWorldView(entry.getWorldViewId());
        TileObject object = wv == null ? null : findTileObject(wv, event.getActionParam0(), event.getActionParam1(), event.getIdentifier());
        if (object == null) { return; }
        client.getMenu().createMenuEntry(-1)
            .setOption(objects.marked(object) ? "Unmark object" : "Mark object")
            .setTarget(event.getTarget())
            .setWorldViewId(entry.getWorldViewId())
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setIdentifier(event.getIdentifier())
            .setType(MenuAction.RUNELITE)
            .onClick(this::markObject);
    }

    private void markObject(MenuEntry entry)
    {
        WorldView wv = client.getWorldView(entry.getWorldViewId());
        TileObject object = wv == null ? null : findTileObject(wv, entry.getParam0(), entry.getParam1(), entry.getIdentifier());
        if (object == null) { return; }
        // The object's id is its base id; the composition is the object as it is seen now.
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        if (composition != null && composition.getImpostorIds() != null) { composition = composition.getImpostor(); }
        String name = composition == null ? null : composition.getName();
        // Objects without a name are ambiguous: not marked, as in Object Markers.
        if (Strings.isNullOrEmpty(name) || name.equals("null")) { return; }
        // On the object's own floor, as the marks are matched (ObjectMarkerSource.check).
        WorldPoint point = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
        int region = point.getRegionID();
        List<ObjectMarkerSource.ObjectPoint> points = objects.saved(region);
        // The same object, or a multiloc marked under another name, or another id spawned with the same name.
        boolean removed = points.removeIf(p -> (p.id == object.getId() || p.name.equals(name))
            && p.regionX == point.getRegionX() && p.regionY == point.getRegionY() && p.z == point.getPlane());
        if (!removed) { points.add(new ObjectMarkerSource.ObjectPoint(object.getId(), name, point.getRegionX(), point.getRegionY(), point.getPlane())); }
        saveObjects(region, points);
    }

    private void saveObjects(int region, List<ObjectMarkerSource.ObjectPoint> points)
    {
        if (points.isEmpty()) { configs.unsetConfiguration(InWorldTileMarkersConfig.GROUP, ObjectMarkerSource.KEY + region); }
        else { configs.setConfiguration(InWorldTileMarkersConfig.GROUP, ObjectMarkerSource.KEY + region, gson.toJson(points)); }
    }

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
        List<String> names = Text.fromCSV(config.npcNames());
        // A name matched by a pattern (with *) has no option: Un-tag-All cannot remove the pattern.
        if (names.stream().anyMatch(n -> !n.equalsIgnoreCase(name) && WildcardMatcher.matches(n, name))) { return; }
        boolean tagged = names.stream().anyMatch(name::equalsIgnoreCase);
        client.getMenu().createMenuEntry(-1)
            .setOption(tagged ? "Un-tag-All" : "Tag-All")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE)
            .onClick(e -> tagAll(name));
    }

    private void tagAll(String name)
    {
        List<String> names = new ArrayList<>(Text.fromCSV(config.npcNames()));
        if (!names.removeIf(name::equalsIgnoreCase)) { names.add(name); }
        configs.setConfiguration(InWorldTileMarkersConfig.GROUP, NPC_NAMES, Text.toCSV(names));
    }

    private void message(String text)
    {
        chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(text).build());
    }

    /** A notice shown when logged in: now, or at the next login. */
    private String pendingNotice;

    private void notice(String text)
    {
        if (client.getGameState() == GameState.LOGGED_IN) { message(text); } else { pendingNotice = text; }
    }

    /** A notice shown once per profile. */
    void noticeOnce(String key, String text)
    {
        if (configs.getConfiguration(InWorldTileMarkersConfig.GROUP, key) != null) { return; }
        configs.setConfiguration(InWorldTileMarkersConfig.GROUP, key, "true");
        notice(text);
    }
}

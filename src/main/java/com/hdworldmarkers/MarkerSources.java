/*
 * Copyright (c) 2018, TheLonelyDev <https://github.com/TheLonelyDev>
 * Copyright (c) 2018, James Swindle <wilingua@gmail.com>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
 * Tile mark loading follows RuneLite's Ground Markers (GroundMarkerPlugin.loadPoints) and NPC selection
 * follows NPC Indicators (highlightMatchesNPCName) of https://github.com/runelite/runelite,
 * BSD 2-Clause License, copyright (c) 2018 TheLonelyDev, James Swindle and Adam; see
 * META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD World Markers: its own saved
 * marks and settings, and marks returned to HD World Markers' renderer instead of drawn.
 */
package com.hdworldmarkers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.hdworldmarkers.HdWorldMarkersConfig.NpcStyle;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

/** Collects what to draw: the tiles, objects and NPCs you mark, and the tile indicators. */
@Singleton
final class MarkerSources
{
    /** Upper bound of the draw distance option; the loaded area limits it further in practice. */
    static final int MAX_DISTANCE = 200;

    /** The draw distance in local units. */
    static int drawDistance(HdWorldMarkersConfig config) { return Math.max(8, Math.min(MAX_DISTANCE, config.distance())) * 128; }

    private final Client client;
    private final ConfigManager configs;
    private final Gson gson;
    private final HdWorldMarkersConfig config;
    private final ObjectMarkerSource objectMarkers;
    private final TilePackSource tilePacks;
    private final AgilitySource agility;
    private final Set<NPC> npcs = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The marked tiles in the scene; drawn in the tile options of the moment (collect). */
    private final List<Ground> ground = new ArrayList<>();
    /**
     * The marked tiles as markers, kept while the tiles and their options stay the same (Tile Packs alone can have
     * thousands, each made anew every tick); null when they are to be made again.
     */
    private List<Marker> groundMarkers;
    private List<Object> groundOptions;
    /** Outlines of tagged NPCs and marked objects in other world views (a boat): only RuneLite's 2D outline draws them. */
    private List<ModelTarget> outlinesElsewhere = Collections.emptyList();
    /** Per style the names and patterns (with *) of its list under NPC styles, as typed. */
    private final Map<NpcStyle, List<String>> styleNames = new EnumMap<>(NpcStyle.class);
    /** The styles (bits) per NPC name, worked out from the lists once per name rather than per frame. */
    private final Map<String, Integer> nameStyles = new HashMap<>();
    /** Tag color per NPC name (standardized), read with the names. */
    private Map<String, NpcTag> npcTags = Collections.emptyMap();
    // Fadeout and predicted destination state.
    private WorldPoint lastPlayerTile, lastDestination, predicted;
    private long stillSince, arrivedAt, predictedAt;
    /** Last hitsplat on you or your target; combat lasts COMBAT_MS after it. */
    private long lastHit;
    static final long COMBAT_MS = 6000;
    private int predictedTick;
    private boolean predictionConfirmed;
    private WorldView predictedWorld;

    @Inject
    MarkerSources(Client client, ConfigManager configs, Gson gson, HdWorldMarkersConfig config, ObjectMarkerSource objectMarkers,
        TilePackSource tilePacks, AgilitySource agility)
    {
        this.client = client; this.configs = configs; this.gson = gson; this.config = config; this.objectMarkers = objectMarkers;
        this.tilePacks = tilePacks; this.agility = agility;
    }

    void rebuild()
    {
        clear();
        readNpcHighlights();
        visit(client.getTopLevelWorldView());
    }

    /** The tagged names, in each style's list, and their own colors. */
    private void readNpcHighlights()
    {
        for (NpcStyle style : NpcStyle.values()) { styleNames.put(style, names(configs, style)); }
        nameStyles.clear();
        npcTags = npcTags(configs, gson);
    }

    /** A style's list of names and patterns (with *), as typed. */
    static List<String> names(ConfigManager configs, NpcStyle style)
    {
        String list = configs.getConfiguration(HdWorldMarkersConfig.GROUP, style.key);
        return list == null || list.isEmpty() ? Collections.emptyList() : Text.fromCSV(list);
    }

    /** Config key of the colors per NPC name. */
    static final String NPC_TAGS = "npcTags";

    /** Whether a setting holds tagged names or their colors. */
    static boolean npcKey(String key)
    {
        if (key.equals(NPC_TAGS)) { return true; }
        for (NpcStyle style : NpcStyle.values()) { if (style.key.equals(key)) { return true; } }
        return false;
    }

    /** The colors per NPC name as saved; an unreadable save counts as none. */
    static Map<String, NpcTag> npcTags(ConfigManager configs, Gson gson)
    {
        try
        {
            Map<String, NpcTag> tags = gson.fromJson(configs.getConfiguration(HdWorldMarkersConfig.GROUP, NPC_TAGS),
                new com.google.gson.reflect.TypeToken<Map<String, NpcTag>>() { }.getType());
            if (tags != null) { tags.values().removeIf(Objects::isNull); return tags; }
        }
        catch (RuntimeException ignored)
        {
            // None.
        }
        return new HashMap<>();
    }

    /** Your Slayer task's NPCs, from the Slayer plugin. */
    @Inject SlayerPluginService slayer;

    /** The NPCs of your Slayer task as the Slayer plugin finds them, with the Slayer task option on; else none. */
    private Set<NPC> taskTargets()
    {
        List<NPC> targets = config.npcSlayerTask() ? slayer.getTargets() : null;
        if (targets == null || targets.isEmpty()) { return Collections.emptySet(); }
        Set<NPC> task = Collections.newSetFromMap(new IdentityHashMap<>());
        task.addAll(targets);
        return task;
    }

    /** The tagged NPCs, and those of your Slayer task. */
    private Collection<NPC> withTask(Set<NPC> task)
    {
        if (task.isEmpty()) { return npcs; }
        Set<NPC> all = Collections.newSetFromMap(new IdentityHashMap<>());
        all.addAll(npcs);
        all.addAll(task);
        return all;
    }

    /** A set of styles as bits; unknown saved styles (null) left out. */
    private static int bits(Set<NpcStyle> styles)
    {
        int bits = 0;
        for (NpcStyle s : styles == null ? Collections.<NpcStyle>emptySet() : styles) { if (s != null) { bits |= s.bit; } }
        return bits;
    }

    private static final int HULL = NpcStyle.HULL.bit, TILE = NpcStyle.TILE.bit, TRUE_TILE = NpcStyle.TRUE_TILE.bit,
        SW_TILE = NpcStyle.SW_TILE.bit, SW_TRUE_TILE = NpcStyle.SW_TRUE_TILE.bit, OUTLINE = NpcStyle.OUTLINE.bit, CLICKBOX = NpcStyle.CLICKBOX.bit;

    /** A tagged NPC name's own color (Tag color); null for the styles' colors. */
    static final class NpcTag
    {
        Color color;
    }

    /** An NPC name's styles (bits): those whose list has the name, or a pattern that matches it. */
    int styles(String name)
    {
        if (name == null) { return 0; }
        Integer styles = nameStyles.get(name);
        if (styles == null)
        {
            int bits = 0;
            for (Map.Entry<NpcStyle, List<String>> e : styleNames.entrySet())
            {
                for (String entry : e.getValue()) { if (WildcardMatcher.matches(entry, name)) { bits |= e.getKey().bit; break; } }
            }
            nameStyles.put(name, styles = bits);
        }
        return styles;
    }

    /**
     * The tagged names changed: only the NPCs are matched again. A full rebuild scanned the whole scene and dropped every
     * shape the renderer kept, for each NPC tagged.
     */
    void refreshNpcs()
    {
        readNpcHighlights();
        npcs.clear();
        refreshNpcs(client.getTopLevelWorldView());
    }

    /**
     * Marked tiles or objects changed: they are read again, without starting the rest over (the renderer keeps what it
     * drew, the tile indicators their fades).
     */
    void reloadMarks()
    {
        ground.clear();
        groundMarkers = null;
        objectMarkers.clear();
        agility.clear();
        visible = null;
        visit(client.getTopLevelWorldView());
    }

    private void refreshNpcs(WorldView wv)
    {
        if (wv == null) { return; }
        for (NPC npc : wv.npcs()) { add(npc); }
        for (WorldView child : wv.worldViews()) { refreshNpcs(child); }
    }

    private void visit(WorldView wv)
    {
        if (wv == null) { return; }
        // Load saved points before matching the scene's objects against them.
        objectMarkers.load(wv);
        for (NPC npc : wv.npcs()) { add(npc); }
        // A world view still loading (a boat's) has no tiles yet.
        Scene scene = wv.getScene();
        Tile[][][] tiles = scene == null ? null : scene.getTiles();
        for (Tile[][] plane : tiles == null ? new Tile[0][][] : tiles)
        {
            for (Tile[] row : plane == null ? new Tile[0][] : plane)
            {
                for (Tile tile : row == null ? new Tile[0] : row)
                {
                    if (tile == null) { continue; }
                    add(tile.getWallObject()); add(tile.getDecorativeObject()); add(tile.getGroundObject());
                    for (GameObject object : tile.getGameObjects()) { add(object); }
                    List<TileItem> items = tile.getGroundItems();
                    for (TileItem item : items == null ? Collections.<TileItem>emptyList() : items) { agility.item(tile, item, true); }
                }
            }
        }
        if (wv.getMapRegions() != null)
        {
            for (int region : wv.getMapRegions()) { loadGround(wv, region); }
        }
        for (WorldView child : wv.worldViews()) { visit(child); }
    }

    /** Config key prefix of a region's marked tiles. */
    static final String TILES = "tiles_";

    /** Saved tiles per region, parsed once per saved text: every scene load read them again. */
    private final Map<Integer, String> tileJson = new HashMap<>();
    private final Map<Integer, List<TilePoint>> tilePoints = new HashMap<>();

    /**
     * A region's marked tiles as saved (Ground Markers' format, so its export can be imported); invalid ones are
     * left out. The list is shared: changes go through a copy.
     */
    List<TilePoint> tiles(int region)
    {
        String json = configs.getConfiguration(HdWorldMarkersConfig.GROUP, TILES + region);
        if (json == null || json.isEmpty()) { return Collections.emptyList(); }
        if (json.equals(tileJson.get(region))) { return tilePoints.get(region); }
        List<TilePoint> points = parse(gson, json);
        points.removeIf(p -> p.regionId != region);
        tileJson.put(region, json);
        tilePoints.put(region, Collections.unmodifiableList(points));
        return tilePoints.get(region);
    }

    /** Tiles in Ground Markers' format (its export, or what is saved here); invalid ones are left out, all when it is no list. */
    static List<TilePoint> parse(Gson gson, String json)
    {
        List<TilePoint> points = new ArrayList<>();
        if (json == null || json.isEmpty()) { return points; }
        try
        {
            // Not JsonParser: its parse is deprecated in later Gson, and RuneLite's (2.8.5) has no parseString.
            for (JsonElement element : gson.fromJson(json, JsonArray.class))
            {
                TilePoint p = gson.fromJson(element, TilePoint.class);
                if (p != null && p.valid()) { points.add(p); }
            }
        }
        catch (RuntimeException ex) { points.clear(); }
        return points;
    }

    private void loadGround(WorldView wv, int region)
    {
        loadGround(wv, region, "ground:", tiles(region));
        if (config.tilePacks()) { loadGround(wv, region, "pack:", tilePacks.tiles(region)); }
    }

    private void loadGround(WorldView wv, int region, String prefix, List<TilePoint> points)
    {
        for (TilePoint p : points)
        {
            WorldPoint world = WorldPoint.fromRegion(region, p.regionX, p.regionY, p.z);
            for (WorldPoint instance : WorldPoint.toLocalInstance(wv, world))
            {
                // Cache every floor: the WorldPoint overload rejects floors other than the current one,
                // and changing floors within a scene does not necessarily trigger a rebuild.
                LocalPoint local = LocalPoint.fromWorld(wv, instance.getX(), instance.getY());
                if (local == null) { continue; }
                ground.add(new Ground(prefix + wv.getId() + ":" + instance, local, instance.getPlane(), p, prefix.equals("pack:")));
            }
        }
    }

    List<Marker> collect(List<Marker> pathTiles)
    {
        visible = null;
        List<Marker> result = new ArrayList<>(groundMarkers());
        Player player = client.getLocalPlayer();
        if (player == null) { return new ArrayList<>(); }
        WorldView playerWorld = player.getWorldView();
        int plane = playerWorld.getPlane();
        // Path tiles first so tile indicators on the same tile are drawn on top.
        result.addAll(pathTiles);
        // Tile Indicators.
        long now = System.currentTimeMillis();
        if (config.highlightDestinationTile())
        {
            Marker destination = destination(playerWorld, player, plane, now);
            if (destination != null) { result.add(destination); }
        }
        if (config.highlightCurrentTile())
        {
            WorldPoint tile = player.getWorldLocation();
            if (!tile.equals(lastPlayerTile)) { lastPlayerTile = tile; stillSince = now; }
            // Out-of-combat only: the fade (and its delay) restarts after combat.
            if (config.currentTileFadeout() && config.currentTileFadeoutOutOfCombat() && inCombat(player, now)) { stillSince = now; }
            LocalPoint point = LocalPoint.fromWorld(playerWorld, tile);
            // As Corner Tile Indicators: fade out once the player stops moving.
            float alpha = config.currentTileFadeout() ? fade(now - stillSince - config.currentTileFadeoutDelay(), config.currentTileFadeoutTime()) : 1;
            if (point != null && alpha > 0)
            {
                result.add(corners(config.currentTileCornersOnly(), config.currentTileCornerSize(),
                    layer(Marker.CURRENT, new Marker("current", point, plane, 1, 1, scale(config.highlightCurrentColor(), alpha),
                    scale(config.currentTileFillColor(), alpha), config.currentTileBorderWidth(), null))));
            }
        }
        // Tagged NPCs' tile styles, as NPC Indicators draws them, and your Slayer task's.
        Set<NPC> task = taskTargets();
        NpcLook look = npcs.isEmpty() && task.isEmpty() ? null : new NpcLook();
        for (NPC npc : withTask(task))
        {
            if (!look.drawn(npc)) { continue; }
            NPCComposition composition = npc.getTransformedComposition();
            if (composition == null || composition.getSize() < 1 || composition.getSize() > 64) { continue; }
            NpcTag tag = tag(npc);
            int styles = look.styles(npc, task);
            Color own = look.own(tag, npc, task);
            int size = composition.getSize(), npcPlane = npc.getWorldView().getPlane(), offset = (size - 1) * 64;
            String key = "npc:" + npc.getWorldView().getId() + ":" + npc.getIndex();
            LocalPoint local = npc.getLocalLocation();
            // The true tile only when a style needs it: where the server has the NPC (its south-west tile).
            WorldPoint server = (styles & (TRUE_TILE | SW_TRUE_TILE)) != 0 ? npc.getWorldLocation() : null;
            LocalPoint trueSw = server == null ? null : LocalPoint.fromWorld(npc.getWorldView(), server);
            if ((styles & TILE) != 0 && local != null)
            { result.add(npcMarker(Marker.NPC_TILE, key + ":tile", local, npcPlane, size, or(own, look.tile), look.tileFill, look.width)); }
            if ((styles & TRUE_TILE) != 0 && trueSw != null)
            {
                result.add(npcMarker(Marker.NPC_TILE + 1, key + ":true", trueSw.plus(offset, offset), npcPlane, size, or(own, look.trueTile),
                    look.trueTileFill, look.width));
            }
            if ((styles & SW_TILE) != 0 && local != null)
            {
                result.add(npcMarker(Marker.NPC_TILE + 2, key + ":sw", local.plus(-offset, -offset), npcPlane, 1, or(own, look.swTile),
                    look.swTileFill, look.width));
            }
            if ((styles & SW_TRUE_TILE) != 0 && trueSw != null)
            {
                result.add(npcMarker(Marker.NPC_TILE + 3, key + ":swtrue", trueSw, npcPlane, 1, or(own, look.swTrueTile), look.swTrueTileFill,
                    look.width));
            }
        }
        // Marked objects' tile style: the stroke is capped at 2, as in Object Markers.
        for (ObjectMarkerSource.Resolved o : visibleObjects())
        {
            if ((o.flags & ObjectMarkerSource.HF_TILE) == 0) { continue; }
            Marker m = footprint("object:" + o.object.getWorldView().getId() + ":" + o.object.getHash() + ":tile", o.object, o.border, o.otherFill, Math.min(o.borderWidth, 2));
            if (m != null) { result.add(m); }
        }
        agility.tiles(result);
        int distance = drawDistance(config);
        LocalPoint origin = player.getLocalLocation();
        result.removeIf(m -> {
            WorldView wv = client.getWorldView(m.point.getWorldView());
            // Draw distance limits the many saved markers, not your own tile, destination and path,
            // which can be far away when the renderer draws further than it.
            boolean own = m.key.equals("destination") || m.key.equals("current") || m.key.startsWith("path:");
            return wv == null || m.plane != wv.getPlane() || m.color.getAlpha() == 0 && m.fill.getAlpha() == 0
                || (!own && m.point.getWorldView() == origin.getWorldView()
                    && m.point.distanceTo(origin) > distance);
        });
        return result;
    }

    /** The marked tiles as markers in the tile options of the moment, made again only when those or the tiles changed. */
    private List<Marker> groundMarkers()
    {
        Color tileColor = config.tileColor();
        int opacity = Math.max(0, Math.min(255, config.tileFillOpacity()));
        double width = config.tileBorderWidth();
        boolean remember = config.rememberTileColors();
        List<Object> options = Arrays.asList(tileColor, opacity, width, remember);
        if (groundMarkers != null && options.equals(groundOptions)) { return groundMarkers; }
        Color fill = new Color(0, 0, 0, opacity);
        List<Marker> markers = new ArrayList<>(ground.size());
        for (Ground g : ground)
        {
            // Tile Packs' tiles always keep their pack's colors; marked tiles theirs while Remember tile colors is on.
            Color color = g.tile.color == null || !remember && !g.pack ? tileColor : g.tile.color;
            markers.add(layer(Marker.GROUND, new Marker(g.key, g.point, g.plane, 1, 1, color, fill, width, g.tile.label)));
        }
        groundOptions = options;
        return groundMarkers = markers;
    }

    /**
     * The destination tile: the predicted walk target while it is active and the
     * client confirms a route beyond the loaded area, else the
     * client's destination, else the last destination fading out after arrival.
     */
    private Marker destination(WorldView wv, Player player, int plane, long now)
    {
        LocalPoint local = client.getLocalDestinationLocation();
        WorldPoint target = null;
        LocalPoint point = null;
        float alpha = 1;
        WorldPoint prediction = predictedTarget(player, now);
        if (prediction != null && local != null && outside(wv, prediction))
        {
            target = prediction;
            point = local(wv, prediction);
        }
        else if (local != null)
        {
            target = WorldPoint.fromLocal(wv, local.getX(), local.getY(), plane);
            // Clicking the tile you stand on is not shown. A walk that just arrived there keeps its
            // destination (already remembered), so it does not blink before the fadeout.
            if (target.equals(player.getWorldLocation()) && !target.equals(lastDestination)) { return null; }
            lastDestination = target;
            arrivedAt = 0;
        }
        else if (lastDestination != null && config.destinationTileFadeout())
        {
            if (arrivedAt == 0 || config.destinationTileFadeoutOutOfCombat() && inCombat(player, now)) { arrivedAt = now; }
            alpha = fade(now - arrivedAt - config.destinationTileFadeoutDelay(), config.destinationTileFadeoutTime());
            target = lastDestination;
            if (alpha <= 0) { lastDestination = null; }
        }
        if (point == null && target != null) { point = LocalPoint.fromWorld(wv, target); }
        if (point == null || alpha <= 0) { return null; }
        return corners(config.destinationTileCornersOnly(), config.destinationTileCornerSize(),
            layer(Marker.DESTINATION, new Marker("destination", point, plane, 1, 1, scale(config.highlightDestinationColor(), alpha),
            scale(config.destinationTileFillColor(), alpha), config.destinationTileBorderWidth(), null)));
    }

    /** Records a hitsplat; hits on you or on what you are interacting with count as combat. */
    void hitsplat(Actor target, long now)
    {
        Player player = client.getLocalPlayer();
        if (player != null && target != null && (target == player || target == player.getInteracting())) { lastHit = now; }
    }

    /**
     * In combat: a recent hitsplat on you or your target, or you and an attackable NPC
     * interacting with each other. Current state only, nothing is predicted.
     */
    boolean inCombat(Player player, long now)
    {
        if (lastHit != 0 && now - lastHit < COMBAT_MS) { return true; }
        if (attackable(player.getInteracting())) { return true; }
        WorldView wv = player.getWorldView();
        if (wv == null) { return false; }
        for (NPC npc : wv.npcs())
        {
            if (npc != null && npc.getInteracting() == player && attackable(npc)) { return true; }
        }
        return false;
    }

    private static boolean attackable(Actor actor)
    {
        if (actor instanceof Player) { return true; }
        if (!(actor instanceof NPC) || ((NPC) actor).isDead()) { return false; }
        NPCComposition c = ((NPC) actor).getTransformedComposition();
        if (c == null || c.getActions() == null) { return false; }
        for (String action : c.getActions()) { if ("Attack".equals(action)) { return true; } }
        return false;
    }

    /** Discard unaccepted clicks after two game ticks, and confirmed routes as soon as they end. */
    WorldPoint predictedTarget(Player player, long now)
    {
        if (predicted == null || !config.predictWalk()) { return null; }
        WorldPoint current = player.getWorldLocation();
        boolean hasDestination = client.getLocalDestinationLocation() != null;
        if (player.getWorldView() != predictedWorld || now - predictedAt >= 30000
            || predicted.equals(current) || (current != null && predicted.getPlane() != current.getPlane())
            || (!hasDestination && (predictionConfirmed || client.getTickCount() - predictedTick >= 2)))
        {
            predicted = null;
            return null;
        }
        if (hasDestination) { predictionConfirmed = true; }
        return predicted;
    }

    /** Whether a world point lies beyond the loaded area. */
    static boolean outside(WorldView wv, WorldPoint p)
    {
        int x = p.getX() - wv.getBaseX(), y = p.getY() - wv.getBaseY();
        return x < 0 || y < 0 || x >= wv.getSizeX() || y >= wv.getSizeY();
    }

    /** A local point for any world point, also beyond the loaded area. */
    static LocalPoint local(WorldView wv, WorldPoint p)
    {
        LocalPoint inside = outside(wv, p) ? null : LocalPoint.fromWorld(wv, p);
        if (inside != null) { return inside; }
        return new LocalPoint((p.getX() - wv.getBaseX()) * 128 + 64, (p.getY() - wv.getBaseY()) * 128 + 64, wv.getId());
    }

    /** Remembers the tile clicked with Walk here, for the predicted destination and path. */
    void walkedTo(WorldPoint tile)
    {
        Player player = client.getLocalPlayer();
        // Clicking the tile you already stand on goes nowhere: no prediction, no destination.
        predicted = player != null && tile != null && tile.equals(player.getWorldLocation()) ? null : tile;
        predictedAt = System.currentTimeMillis();
        predictedTick = client.getTickCount();
        predictionConfirmed = false;
        predictedWorld = player == null ? null : player.getWorldView();
        lastDestination = null;
    }

    /** 1 until elapsed reaches 0 (the delay has been subtracted), then down to 0 over time. */
    private static float fade(long elapsed, int time) { return elapsed <= 0 ? 1 : Math.max(0, 1 - elapsed / (float) Math.max(1, time)); }

    private static Color scale(Color c, float alpha)
    { return alpha >= 1 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * alpha)); }

    private static Marker layer(int layer, Marker m) { m.layer = layer; return m; }

    /** An object's footprint (getCanvasTilePoly), on the object layer; null when larger than 64 tiles. */
    static Marker footprint(String key, TileObject object, Color color, Color fill, double width)
    {
        int w = 1, h = 1;
        LocalPoint point = object.getLocalLocation();
        if (object instanceof GameObject)
        {
            GameObject go = (GameObject) object;
            w = go.getSceneMaxLocation().getX() - go.getSceneMinLocation().getX() + 1;
            h = go.getSceneMaxLocation().getY() - go.getSceneMinLocation().getY() + 1;
            point = LocalPoint.fromScene(go.getSceneMinLocation().getX(), go.getSceneMinLocation().getY(), object.getWorldView()).plus((w - 1) * 64, (h - 1) * 64);
        }
        return w <= 0 || h <= 0 || w > 64 || h > 64 ? null : layer(Marker.OBJECT, new Marker(key, point, object.getPlane(), w, h, color, fill, width, null));
    }

    private static Marker corners(boolean only, int divisor, Marker m) { m.cornerDivisor = only ? Math.max(2, divisor) : 0; return m; }

    /**
     * The hovered tile, read every frame rather than every client tick: the
     * client picks it while drawing, so it changes faster than markers.
     */
    Marker hover()
    {
        Player player = client.getLocalPlayer();
        if (!config.highlightHoveredTile() || player == null) { return null; }
        Tile hovered = player.getWorldView().getSelectedSceneTile();
        if (hovered == null || hovered.getLocalLocation() == null) { return null; }
        Marker m = new Marker("hover", hovered.getLocalLocation(), hovered.getPlane(), 1, 1, config.highlightHoveredColor(),
            config.hoveredTileFillColor(), config.hoveredTileBorderWidth(), null);
        return m.color.getAlpha() == 0 && m.fill.getAlpha() == 0 ? null
            : corners(config.hoveredTileCornersOnly(), config.hoveredTileCornerSize(), layer(Marker.HOVER, m));
    }

    private static Marker npcMarker(int layer, String key, LocalPoint point, int plane, int size, Color color, Color fill, double width)
    { return layer(layer, new Marker(key, point, plane, size, size, color, fill, width, null)); }

    /** The tag of this NPC's name, or null. */
    private NpcTag tag(NPC npc) { return npcTags.isEmpty() || npc.getName() == null ? null : npcTags.get(Text.standardize(npc.getName())); }

    /** A style's color, or the name's own (Tag color) when it has one. */
    private static Color or(Color own, Color style) { return own != null ? own : style; }

    /** The NPC options of the moment, read once per collect or modelTargets rather than per NPC and style. */
    private final class NpcLook
    {
        final double width = config.npcBorderWidth();
        final int taskStyles = bits(config.npcSlayerTaskStyles());
        final Color task = config.npcSlayerTaskColor();
        final boolean ignoreDead = config.npcIgnoreDead(), ignorePets = config.npcIgnorePets();
        final Color tile = config.npcTileColor(), tileFill = config.npcTileFill(), trueTile = config.npcTrueTileColor(),
            trueTileFill = config.npcTrueTileFill(), swTile = config.npcSouthWestTileColor(), swTileFill = config.npcSouthWestTileFill(),
            swTrueTile = config.npcSouthWestTrueTileColor(), swTrueTileFill = config.npcSouthWestTrueTileFill(), outline = config.npcOutlineColor(),
            hull = config.npcHullColor(), hullFill = config.npcHullFill(), clickbox = config.npcClickboxColor(), clickboxFill = config.npcClickboxFill();

        /** An NPC's styles: those of its name's lists, and the Slayer task styles for one of your task. */
        int styles(NPC npc, Set<NPC> targets) { return MarkerSources.this.styles(npc.getName()) | (targets.contains(npc) ? taskStyles : 0); }

        /** The color an NPC's styles take instead of their own: its name's Tag color, else the Slayer task's for one of your task. */
        Color own(NpcTag tag, NPC npc, Set<NPC> targets) { return tag != null && tag.color != null ? tag.color : targets.contains(npc) ? task : null; }

        /** Dead NPCs and pets as the options say (NPC Indicators' Ignore dead NPCs and Ignore pets). */
        boolean drawn(NPC npc)
        {
            if (npc.isDead() && ignoreDead) { return false; }
            NPCComposition composition = npc.getTransformedComposition();
            return composition == null || !(composition.isFollower() && ignorePets);
        }
    }

    /** Hulls and clickboxes, nearest first. */
    List<ModelTarget> modelTargets()
    {
        Player player = client.getLocalPlayer();
        WorldView top = client.getTopLevelWorldView();
        if (player == null || top == null) { return new ArrayList<>(); }
        List<ModelTarget> result = new ArrayList<>(), elsewhere = new ArrayList<>();
        Set<NPC> task = taskTargets();
        NpcLook look = npcs.isEmpty() && task.isEmpty() ? null : new NpcLook();
        for (NPC npc : withTask(task))
        {
            if (!look.drawn(npc)) { continue; }
            NpcTag tag = tag(npc);
            int styles = look.styles(npc, task);
            Color own = look.own(tag, npc, task);
            if (npc.getWorldView() != top)
            {
                // In another world view only the outline, which RuneLite's outline renderer draws in 2D.
                if ((styles & OUTLINE) != 0)
                {
                    elsewhere.add(ModelTarget.npcOutline("npc:" + npc.getWorldView().getId() + ":" + npc.getIndex() + ":outline", npc,
                        or(own, look.outline), look.width));
                }
                continue;
            }
            String key = "npc:" + npc.getIndex();
            if ((styles & OUTLINE) != 0) { result.add(ModelTarget.npcOutline(key + ":outline", npc, or(own, look.outline), look.width)); }
            if ((styles & HULL) != 0) { result.add(ModelTarget.npc(key + ":hull", npc, or(own, look.hull), look.hullFill, look.width)); }
            if ((styles & CLICKBOX) != 0)
            { result.add(ModelTarget.npcClickbox(key + ":clickbox", npc, or(own, look.clickbox), look.clickboxFill, look.width, client)); }
        }
        for (ObjectMarkerSource.Resolved o : visibleObjects())
        {
            TileObject object = o.object;
            if (object.getWorldView() != top)
            {
                if ((o.flags & ObjectMarkerSource.HF_OUTLINE) != 0)
                {
                    elsewhere.add(ModelTarget.objectOutline("object:" + object.getWorldView().getId() + ":" + object.getHash() + ":outline", object,
                        ModelTarget.renderable(object), 0, 0, o.border, o.borderWidth));
                }
                continue;
            }
            String key = "object:" + object.getHash();
            if ((o.flags & ObjectMarkerSource.HF_HULL) != 0) { addParts(result, key, object, o, false); }
            if ((o.flags & ObjectMarkerSource.HF_OUTLINE) != 0) { addParts(result, key, object, o, true); }
            if ((o.flags & ObjectMarkerSource.HF_CLICKBOX) != 0)
            {
                Renderable renderable = ModelTarget.renderable(object);
                if (renderable != null)
                {
                    result.add(ModelTarget.object(key + ":clickbox", object, renderable, ModelTarget.offsetX(object), ModelTarget.offsetY(object),
                        o.border, o.otherFill,
                        o.borderWidth, true, object::getClickbox));
                }
            }
        }
        agility.models(result);
        LocalPoint origin = player.getLocalLocation();
        int distance = drawDistance(config);
        // Each target's distance once: an object's location() is a new LocalPoint per call.
        Map<ModelTarget, Integer> distances = new IdentityHashMap<>();
        for (ModelTarget t : result)
        {
            LocalPoint location = t.location();
            if (location != null) { distances.put(t, location.distanceTo(origin)); }
        }
        result.removeIf(t -> { Integer d = distances.get(t); return d == null || d > distance; });
        result.sort(Comparator.comparingInt(distances::get));
        outlinesElsewhere = elsewhere;
        return result;
    }

    /** The outlines in other world views found by the last modelTargets(), for RuneLite's 2D outline renderer. */
    List<ModelTarget> outlinesElsewhere() { return outlinesElsewhere; }

    /** Hull or outline parts as in ObjectIndicatorsOverlay.renderConvexHull: walls and decorations have two. */
    private static void addParts(List<ModelTarget> out, String key, TileObject object, ObjectMarkerSource.Resolved o, boolean outline)
    {
        if (object instanceof GameObject)
        {
            GameObject go = (GameObject) object;
            part(out, key, "", object, go.getRenderable(), 0, 0, go::getConvexHull, o, outline);
        }
        else if (object instanceof WallObject)
        {
            WallObject wall = (WallObject) object;
            part(out, key, "", object, wall.getRenderable1(), 0, 0, wall::getConvexHull, o, outline);
            part(out, key, "2", object, wall.getRenderable2(), 0, 0, wall::getConvexHull2, o, outline);
        }
        else if (object instanceof DecorativeObject)
        {
            DecorativeObject decor = (DecorativeObject) object;
            part(out, key, "", object, decor.getRenderable(), decor.getXOffset(), decor.getYOffset(), decor::getConvexHull, o, outline);
            part(out, key, "2", object, decor.getRenderable2(), decor.getXOffset2(), decor.getYOffset2(), decor::getConvexHull2, o, outline);
        }
        else if (object instanceof GroundObject)
        {
            GroundObject g = (GroundObject) object;
            part(out, key, "", object, g.getRenderable(), 0, 0, g::getConvexHull, o, outline);
        }
    }

    private static void part(List<ModelTarget> out, String key, String n, TileObject object, Renderable r, int dx, int dy,
        java.util.function.Supplier<java.awt.Shape> hull, ObjectMarkerSource.Resolved o, boolean outline)
    {
        if (r == null) { return; }
        out.add(outline ? ModelTarget.objectOutline(key + ":outline" + n, object, r, dx, dy, o.border, o.borderWidth)
            : ModelTarget.object(key + ":hull" + n, object, r, dx, dy, o.border, o.hullFill, o.borderWidth, false, hull));
    }

    /**
     * The marked objects to draw, resolved at most once per tick (collect() starts a new one)
     * and shared by the lookups that follow; objects spawning or despawning resolve them again.
     */
    private List<ObjectMarkerSource.Resolved> visible;

    private List<ObjectMarkerSource.Resolved> visibleObjects()
    {
        if (visible == null) { visible = objectMarkers.visible(); }
        return visible;
    }

    /** NPC Indicators' name match: a name in any style's list, with wildcards. */
    boolean isHighlighted(NPC npc) { return styles(npc.getName()) != 0; }

    void add(TileObject object)
    {
        if (object == null) { return; }
        objectMarkers.check(object);
        agility.add(object);
        visible = null;
    }

    void remove(TileObject object)
    {
        if (object == null) { return; }
        objectMarkers.remove(object);
        agility.remove(object);
        visible = null;
    }

    /** A ground item appeared or went (marks of grace for the agility highlights). */
    void item(Tile tile, TileItem item, boolean spawned) { if (tile != null && item != null) { agility.item(tile, item, spawned); } }
    void add(NPC npc) { npcs.remove(npc); if (isHighlighted(npc)) { npcs.add(npc); } }
    void remove(NPC npc) { npcs.remove(npc); }
    /** A world view that loaded inside the scene (a boat): its markers, without rebuilding the rest. */
    void addWorldView(WorldView wv) { removeWorldView(wv); visit(wv); visible = null; groundMarkers = null; }

    void removeWorldView(WorldView wv)
    {
        if (wv == null) { return; }
        objectMarkers.removeWorldView(wv);
        agility.removeWorldView(wv);
        npcs.removeIf(n -> n.getWorldView() == wv);
        ground.removeIf(m -> m.point.getWorldView() == wv.getId());
        groundMarkers = null;
        visible = null;
    }
    void clear() { visible = null; predicted = null; predictedWorld = null; predictionConfirmed = false;
        lastPlayerTile = null; lastDestination = null; stillSince = 0; arrivedAt = 0; lastHit = 0;
        ground.clear(); groundMarkers = null; npcs.clear(); objectMarkers.clear(); agility.clear(); styleNames.clear();
        nameStyles.clear(); npcTags = Collections.emptyMap(); outlinesElsewhere = Collections.emptyList(); }

    /** A marked tile in the scene, or a Tile Packs pack's (pack). */
    private static final class Ground
    {
        final String key;
        final LocalPoint point;
        final int plane;
        final TilePoint tile;
        final boolean pack;

        Ground(String key, LocalPoint point, int plane, TilePoint tile, boolean pack)
        {
            this.key = key; this.point = point; this.plane = plane; this.tile = tile; this.pack = pack;
        }
    }

    /** A marked tile as saved: Ground Markers' point format. */
    static final class TilePoint
    {
        final int regionId, regionX, regionY, z;
        final Color color;
        final String label;

        TilePoint(int regionId, int regionX, int regionY, int z, Color color, String label)
        {
            this.regionId = regionId; this.regionX = regionX; this.regionY = regionY; this.z = z; this.color = color; this.label = label;
        }

        boolean valid()
        {
            return regionId >= 0 && regionId <= 0xFFFF && regionX >= 0 && regionX <= 63 && regionY >= 0 && regionY <= 63 && z >= 0 && z <= 3;
        }

        boolean same(TilePoint o) { return regionId == o.regionId && regionX == o.regionX && regionY == o.regionY && z == o.z; }
    }
}

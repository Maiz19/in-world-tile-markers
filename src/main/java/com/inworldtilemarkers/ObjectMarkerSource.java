/*
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
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
 * Matching and display rules adapted from RuneLite's Object Markers plugin
 * (ObjectIndicatorsPlugin.checkObjectPoints, loadPoints and ObjectIndicatorsOverlay.render),
 * https://github.com/runelite/runelite, tag runelite-parent-1.12.39.
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * BSD 2-Clause License; see THIRD_PARTY_NOTICES.md. Changes for In-World Tile Markers: its own saved marks and styles,
 * and marks returned to In-World Tile Markers' renderer instead of drawn.
 */
package com.inworldtilemarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/** The objects marked with Mark object, saved per region of the map. */
@Singleton
final class ObjectMarkerSource
{
    /** Config key prefix of a region's marked objects. */
    static final String KEY = "objects_";
    static final int HF_HULL = 0x1, HF_OUTLINE = 0x2, HF_CLICKBOX = 0x4, HF_TILE = 0x8;

    private final Client client;
    private final ConfigManager configs;
    private final Gson gson;
    private final InWorldTileMarkersConfig config;
    private final Map<Integer, List<ObjectPoint>> points = new HashMap<>();
    private final List<Marked> objects = new ArrayList<>();

    @Inject
    ObjectMarkerSource(Client client, ConfigManager configs, Gson gson, InWorldTileMarkersConfig config)
    {
        this.client = client; this.configs = configs; this.gson = gson; this.config = config;
    }

    /** A marked object in the scene, with its own colors and styles (flags; 0 for the options'). */
    static final class Marked
    {
        final TileObject object;
        final ObjectComposition composition;
        final String name;
        final Color borderColor, fillColor;
        final int flags;

        Marked(TileObject object, ObjectComposition composition, ObjectPoint p)
        {
            this.object = object; this.composition = composition; name = p.name; borderColor = p.borderColor; fillColor = p.fillColor;
            flags = (p.hull == Boolean.TRUE ? HF_HULL : 0) | (p.outline == Boolean.TRUE ? HF_OUTLINE : 0)
                | (p.clickbox == Boolean.TRUE ? HF_CLICKBOX : 0) | (p.tile == Boolean.TRUE ? HF_TILE : 0);
        }
    }

    /**
     * A marked object as saved, in Object Markers' format: the object on this tile, its name, and its own colors and
     * styles (null: the options').
     */
    static final class ObjectPoint
    {
        int id;
        String name;
        int regionX, regionY, z;
        @SerializedName("color")
        Color borderColor;
        Color fillColor;
        Boolean hull, outline, clickbox, tile;

        ObjectPoint(int id, String name, int regionX, int regionY, int z)
        {
            this.id = id; this.name = name; this.regionX = regionX; this.regionY = regionY; this.z = z;
        }

        boolean same(ObjectPoint o) { return id == o.id && regionX == o.regionX && regionY == o.regionY && z == o.z; }

        /** Whether it has a color or style of its own. */
        boolean hasLook() { return borderColor != null || fillColor != null || hull != null || outline != null || clickbox != null || tile != null; }
    }

    /** The marked objects; their saved points stay parsed (per region) until they change. */
    void clear() { objects.clear(); }

    /** The saved points changed: they are read again. */
    void clearPoints() { points.clear(); }

    /** A region's saved points, read once. */
    List<ObjectPoint> points(int region) { return points.computeIfAbsent(region, this::saved); }

    /** A region's saved points, read now: what a change starts from. */
    List<ObjectPoint> saved(int region) { return parse(gson, configs.getConfiguration(InWorldTileMarkersConfig.GROUP, KEY + region)); }

    /** Marked objects in Object Markers' format (or this plugin's, its id, name and place); those without a name are left out. */
    static List<ObjectPoint> parse(Gson gson, String json)
    {
        List<ObjectPoint> list = new ArrayList<>();
        if (Strings.isNullOrEmpty(json)) { return list; }
        try
        {
            for (ObjectPoint p : gson.fromJson(json, ObjectPoint[].class))
            {
                if (p != null && p.name != null && !p.name.equals("null")) { list.add(p); }
            }
        }
        catch (RuntimeException ex) { list.clear(); }
        return list;
    }

    /** Loads the points of every loaded region; objects are matched as the scene is visited. */
    void load(WorldView wv)
    {
        if (wv == null || wv.getMapRegions() == null) { return; }
        for (int region : wv.getMapRegions()) { points(region); }
    }

    /** As Object Markers' checkObjectPoints. */
    void check(TileObject object)
    {
        // No saved points loaded (as during a scene load, before the rebuild): nothing to match.
        if (object == null || object.getPlane() < 0 || points.isEmpty()) { return; }
        WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
        List<ObjectPoint> regionPoints = points.get(worldPoint.getRegionID());
        if (regionPoints == null || regionPoints.isEmpty()) { return; }
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        if (composition == null) { return; }
        if (composition.getImpostorIds() == null)
        {
            // Multiloc names are instead checked when drawing.
            String name = composition.getName();
            if (Strings.isNullOrEmpty(name) || name.equals("null")) { return; }
        }
        for (ObjectPoint p : regionPoints)
        {
            if (worldPoint.getRegionX() == p.regionX && worldPoint.getRegionY() == p.regionY
                && worldPoint.getPlane() == p.z && p.id == object.getId())
            {
                remove(object);
                objects.add(new Marked(object, composition, p));
                break;
            }
        }
    }

    void remove(TileObject object) { objects.removeIf(o -> o.object == object); }

    /** Whether this object is marked, as found in the scene. */
    boolean marked(TileObject object) { return find(object) != null; }

    /** The mark of this object as found in the scene, or null. */
    Marked find(TileObject object)
    {
        for (Marked m : objects) { if (m.object == object) { return m; } }
        return null;
    }

    /** The colors in use in these regions' marks (up to five), for the color menus. */
    List<Color> usedColors(int[] regions, boolean fill)
    {
        List<Color> colors = new ArrayList<>();
        for (int region : regions == null ? new int[0] : regions)
        {
            for (ObjectPoint p : points(region))
            {
                Color c = fill ? p.fillColor : p.borderColor;
                if (c != null && !colors.contains(c) && colors.size() < 5) { colors.add(c); }
            }
        }
        return colors;
    }

    void removeWorldView(WorldView wv) { objects.removeIf(o -> o.object.getWorldView() == wv); }

    /** Marks to draw now, in the styles chosen in the settings, as ObjectIndicatorsOverlay.render resolves them. */
    List<Resolved> visible()
    {
        if (objects.isEmpty()) { return Collections.emptyList(); }
        WorldView top = client.getTopLevelWorldView();
        int defaultFlags = (config.objectHull() ? HF_HULL : 0) | (config.objectOutline() ? HF_OUTLINE : 0)
            | (config.objectClickbox() ? HF_CLICKBOX : 0) | (config.objectTile() ? HF_TILE : 0);
        List<Resolved> result = new ArrayList<>();
        for (Marked m : objects)
        {
            WorldView wv = m.object.getWorldView();
            if (wv == null || m.object.getPlane() != wv.getPlane()) { continue; }
            WorldEntity entity = top == null ? null : top.worldEntities().byIndex(wv.getId());
            if (entity != null && entity.isHiddenForOverlap()) { continue; }
            ObjectComposition composition = m.composition;
            if (composition.getImpostorIds() != null)
            {
                // A multiloc: only mark it while the name still matches.
                composition = composition.getImpostor();
                if (composition == null || Strings.isNullOrEmpty(composition.getName())
                    || "null".equals(composition.getName()) || !composition.getName().equals(m.name)) { continue; }
            }
            Color border = m.borderColor != null ? m.borderColor : config.objectColor();
            Color fill = m.fillColor != null ? m.fillColor : config.objectFillColor();
            // Without a fill the hull's is Object Markers' default (a=50); clickbox and tile use the border color at a/12.
            Color hullFill = fill != null ? fill : new Color(0, 0, 0, 50);
            Color otherFill = fill != null ? fill : new Color(border.getRed(), border.getGreen(), border.getBlue(), border.getAlpha() / 12);
            result.add(new Resolved(m.object, m.flags != 0 ? m.flags : defaultFlags, border, hullFill, otherFill, config.objectBorderWidth()));
        }
        return result;
    }

    static final class Resolved
    {
        final TileObject object;
        final int flags;
        final Color border, hullFill, otherFill;
        final double borderWidth;

        Resolved(TileObject object, int flags, Color border, Color hullFill, Color otherFill, double borderWidth)
        {
            this.object = object; this.flags = flags; this.border = border; this.hullFill = hullFill;
            this.otherFill = otherFill; this.borderWidth = borderWidth;
        }
    }
}

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
 * BSD 2-Clause License; see THIRD_PARTY_NOTICES.md. Changes for HD World Markers: its own saved marks and styles,
 * and marks returned to HD World Markers' renderer instead of drawn.
 */
package com.hdworldmarkers;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;

/** The objects marked with Mark object, saved per region of the map. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
final class ObjectMarkerSource
{
    /** Config key prefix of a region's marked objects. */
    static final String KEY = "objects_";
    static final int HF_HULL = 0x1, HF_OUTLINE = 0x2, HF_CLICKBOX = 0x4, HF_TILE = 0x8;

    private final Client client;
    private final ConfigManager configs;
    private final Gson gson;
    private final HdWorldMarkersConfig config;
    private final Map<Integer, List<ObjectPoint>> points = new HashMap<>();
    private final List<Marked> objects = new ArrayList<>();

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
     * A marked object as saved: the object of this name on this tile, and its own colors and styles (null: the
     * options'). Object Markers' format without its object id, which is neither read nor saved.
     */
    static final class ObjectPoint
    {
        String name;
        int regionX, regionY, z;
        @SerializedName("color")
        Color borderColor;
        Color fillColor;
        Boolean hull, outline, clickbox, tile;

        ObjectPoint(String name, int regionX, int regionY, int z)
        {
            this.name = name; this.regionX = regionX; this.regionY = regionY; this.z = z;
        }

        boolean same(ObjectPoint o) { return name.equals(o.name) && regionX == o.regionX && regionY == o.regionY && z == o.z; }

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
    List<ObjectPoint> saved(int region) { return parse(gson, configs.getConfiguration(HdWorldMarkersConfig.GROUP, KEY + region)); }

    /** Marked objects in Object Markers' format (or this plugin's, its name and place); those without a name are left out. */
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

    /** As Object Markers' checkObjectPoints, but a mark is the object of its name on its tile, whatever its id. */
    void check(TileObject object)
    {
        // No saved points loaded (as during a scene load, before the rebuild): nothing to match.
        if (object == null || object.getPlane() < 0 || points.isEmpty()) { return; }
        WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
        List<ObjectPoint> regionPoints = points.get(worldPoint.getRegionID());
        if (regionPoints == null || regionPoints.isEmpty()) { return; }
        ObjectComposition composition = client.getObjectDefinition(object.getId());
        if (composition == null) { return; }
        // A multiloc's name is that of the form it shows, checked when drawing: it takes every mark on its tile.
        boolean multiloc = composition.getImpostorIds() != null, found = false;
        String name = composition.getName();
        if (!multiloc && (Strings.isNullOrEmpty(name) || name.equals("null"))) { return; }
        for (ObjectPoint p : regionPoints)
        {
            if (worldPoint.getRegionX() != p.regionX || worldPoint.getRegionY() != p.regionY || worldPoint.getPlane() != p.z
                || !multiloc && !p.name.equals(name)) { continue; }
            if (!found) { remove(object); found = true; }
            objects.add(new Marked(object, composition, p));
            if (!multiloc) { break; }
        }
    }

    void remove(TileObject object) { objects.removeIf(o -> o.object == object); }

    /** The mark of this object as it shows now, or null: a multiloc in another form than the marked one has none. */
    Marked find(TileObject object)
    {
        for (Marked m : objects) { if (m.object == object && shows(m)) { return m; } }
        return null;
    }

    /** Whether the object shows the form its mark names: a multiloc is only marked while the name still matches. */
    private static boolean shows(Marked m)
    {
        if (m.composition.getImpostorIds() == null) { return true; }
        ObjectComposition composition = m.composition.getImpostor();
        return composition != null && !Strings.isNullOrEmpty(composition.getName()) && composition.getName().equals(m.name);
    }

    /** The colors in use in these regions' marks (up to five), for the color menus. */
    List<Color> usedColors(int[] regions, boolean fill)
    {
        List<Color> colors = new ArrayList<>();
        for (int region : regions == null ? new int[0] : regions)
        {
            for (ObjectPoint p : points(region))
            {
                Marking.offer(colors, fill ? p.fillColor : p.borderColor);
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
        // The options once, not per object: each is a call through the config proxy.
        Color color = config.objectColor(), fillColor = config.objectFillColor();
        double width = config.objectBorderWidth();
        Color defaultOther = fillColor != null ? fillColor : twelfth(color);
        List<Resolved> result = new ArrayList<>();
        for (Marked m : objects)
        {
            WorldView wv = m.object.getWorldView();
            if (wv == null || m.object.getPlane() != wv.getPlane()) { continue; }
            WorldEntity entity = top == null ? null : top.worldEntities().byIndex(wv.getId());
            if (entity != null && entity.isHiddenForOverlap()) { continue; }
            if (!shows(m)) { continue; }
            Color border = m.borderColor != null ? m.borderColor : color;
            Color fill = m.fillColor != null ? m.fillColor : fillColor;
            // Without a fill the hull's is Object Markers' default (a=50); clickbox and tile use the border color at a/12.
            Color hullFill = fill != null ? fill : Marker.BLACK_FILL;
            Color otherFill = fill != null ? fill : border == color ? defaultOther : twelfth(border);
            result.add(new Resolved(m.object, m.flags != 0 ? m.flags : defaultFlags, border, hullFill, otherFill, width));
        }
        return result;
    }

    /** A color at a twelfth of its alpha: Object Markers' clickbox and tile fill without a fill color. */
    private static Color twelfth(Color c) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() / 12); }

    @RequiredArgsConstructor
    static final class Resolved
    {
        final TileObject object;
        final int flags;
        final Color border, hullFill, otherFill;
        final double borderWidth;
    }
}

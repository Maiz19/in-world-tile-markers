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
 * BSD 2-Clause License; see THIRD_PARTY_NOTICES.md. Changes for In-World Tile Markers:
 * read-only (never saves), no menus, and returns marks for In-World Tile Markers' renderer instead of drawing.
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
import net.runelite.client.plugins.objectindicators.ObjectIndicatorsConfig;

/**
 * The objects marked with Object Markers, read from its saved configuration.
 * Object Markers keeps its menus and remains the owner of the data.
 */
@Singleton
final class ObjectMarkerSource
{
    static final String GROUP = "objectindicators";
    static final int HF_HULL = 0x1, HF_OUTLINE = 0x2, HF_CLICKBOX = 0x4, HF_TILE = 0x8;

    private final Client client;
    private final ConfigManager configs;
    private final Gson gson;
    private final ObjectIndicatorsConfig config;
    private final Map<Integer, List<ObjectPoint>> points = new HashMap<>();
    private final List<Marked> objects = new ArrayList<>();
    private boolean valid = true;

    @Inject
    ObjectMarkerSource(Client client, ConfigManager configs, Gson gson)
    {
        this.client = client; this.configs = configs; this.gson = gson;
        config = configs.getConfig(ObjectIndicatorsConfig.class);
    }

    /** A marked object, with Object Markers' per-object settings. */
    static final class Marked
    {
        final TileObject object;
        final ObjectComposition composition;
        final String name;
        final Color borderColor, fillColor;
        final int flags;

        Marked(TileObject object, ObjectComposition composition, String name, Color borderColor, Color fillColor, int flags)
        {
            this.object = object; this.composition = composition; this.name = name;
            this.borderColor = borderColor; this.fillColor = fillColor; this.flags = flags;
        }
    }

    /** Object Markers' saved format. */
    static final class ObjectPoint
    {
        int id = -1;
        String name;
        int regionX, regionY, z;
        @SerializedName("color")
        Color borderColor;
        Color fillColor;
        Boolean hull, outline, clickbox, tile;
    }

    /** The marked objects; their saved points stay parsed (per region) until Object Markers' settings change. */
    void clear() { objects.clear(); }

    /** Object Markers' settings changed: its saved points are read again. */
    void clearPoints() { points.clear(); valid = true; }

    boolean valid() { return valid; }

    /** Loads the points of every loaded region; objects are matched as the scene is visited. */
    void load(WorldView wv)
    {
        if (wv == null || wv.getMapRegions() == null) { return; }
        for (int region : wv.getMapRegions())
        {
            if (points.containsKey(region)) { continue; }
            String json = configs.getConfiguration(GROUP, "region_" + region);
            if (Strings.isNullOrEmpty(json)) { continue; }
            try
            {
                List<ObjectPoint> list = new ArrayList<>();
                for (ObjectPoint p : gson.fromJson(json, ObjectPoint[].class))
                {
                    // As Object Markers: points named "null" are ambiguous legacy marks.
                    if (p != null && p.name != null && !p.name.equals("null")) { list.add(p); }
                }
                points.put(region, list);
            }
            catch (RuntimeException ex) { valid = false; }
        }
    }

    /** Object Markers' checkObjectPoints. */
    void check(TileObject object)
    {
        // No saved points loaded (as during a scene load, before the rebuild): nothing to match.
        if (object == null || object.getPlane() < 0 || points.isEmpty()) { return; }
        WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, object.getLocalLocation(), object.getPlane());
        List<ObjectPoint> regionPoints = points.get(worldPoint.getRegionID());
        if (regionPoints == null) { return; }
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
                int flags = (p.hull == Boolean.TRUE ? HF_HULL : 0) | (p.outline == Boolean.TRUE ? HF_OUTLINE : 0)
                    | (p.clickbox == Boolean.TRUE ? HF_CLICKBOX : 0) | (p.tile == Boolean.TRUE ? HF_TILE : 0);
                remove(object);
                objects.add(new Marked(object, composition, p.name, p.borderColor, p.fillColor, flags));
                break;
            }
        }
    }

    void remove(TileObject object) { objects.removeIf(o -> o.object == object); }

    void removeWorldView(WorldView wv) { objects.removeIf(o -> o.object.getWorldView() == wv); }

    /**
     * Marks to draw now, with defaults resolved as in ObjectIndicatorsOverlay.render:
     * the per-object style, else the configured one; missing colors from the config.
     */
    List<Resolved> visible()
    {
        if (objects.isEmpty()) { return Collections.emptyList(); }
        WorldView top = client.getTopLevelWorldView();
        int defaultFlags = (config.highlightHull() ? HF_HULL : 0) | (config.highlightOutline() ? HF_OUTLINE : 0)
            | (config.highlightClickbox() ? HF_CLICKBOX : 0) | (config.highlightTile() ? HF_TILE : 0);
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
            Color border = m.borderColor != null ? m.borderColor : config.markerColor();
            int flags = m.flags != 0 ? m.flags : defaultFlags;
            // Default hull fill is a=50, clickbox and tile use the border color at a/12.
            Color hullFill = m.fillColor != null ? m.fillColor : new Color(0, 0, 0, 50);
            Color otherFill = m.fillColor != null ? m.fillColor
                : new Color(border.getRed(), border.getGreen(), border.getBlue(), border.getAlpha() / 12);
            result.add(new Resolved(m.object, flags, border, hullFill, otherFill, config.borderWidth(), config.outlineFeather()));
        }
        return result;
    }

    static final class Resolved
    {
        final TileObject object;
        final int flags, feather;
        final Color border, hullFill, otherFill;
        final double borderWidth;

        Resolved(TileObject object, int flags, Color border, Color hullFill, Color otherFill, double borderWidth, int feather)
        {
            this.object = object; this.flags = flags; this.border = border; this.hullFill = hullFill;
            this.otherFill = otherFill; this.borderWidth = borderWidth; this.feather = feather;
        }
    }
}

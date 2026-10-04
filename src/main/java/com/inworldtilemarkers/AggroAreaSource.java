/*
 * Copyright (c) 2018, Woox <https://github.com/wooxsolo>
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
 * Display rules adapted from RuneLite's NPC Aggression Timer (NpcAggroAreaOverlay.render and renderPath,
 * https://github.com/runelite/runelite, tag runelite-parent-1.12.39), copyright (c) 2018 Woox,
 * BSD 2-Clause License; see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for In-World
 * Tile Markers: the plugin's own area lines are read through its public getters and returned
 * as scene lines instead of drawn.
 */
package com.inworldtilemarkers;

import java.awt.Color;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaConfig;
import net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin;

/** NPC Aggression Timer's unaggressive area lines, drawn by In-World Tile Markers. */
@Singleton
final class AggroAreaSource implements SourcePlugin.Source
{
    static final String PLUGIN = "net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaPlugin";
    static final String OVERLAY = "net.runelite.client.plugins.npcunaggroarea.NpcAggroAreaOverlay";
    /** As the overlay: only lines within 20 tiles of the player. */
    static final int MAX_LOCAL_DRAW_LENGTH = 20 * Perspective.LOCAL_TILE_SIZE;
    /** Tile edges per scene line, so each piece stays well inside one scene object. */
    private static final int EDGES_PER_LINE = 6;
    /** The overlay's stroke width. */
    private static final int WIDTH = 1;

    private final Client client;
    private final PluginManager plugins;
    private final NpcAggroAreaConfig config;
    private final InWorldTileMarkersConfig hdConfig;

    @Inject
    AggroAreaSource(Client client, PluginManager plugins, ConfigManager configs, InWorldTileMarkersConfig hdConfig)
    {
        this.client = client; this.plugins = plugins; this.hdConfig = hdConfig;
        // Read from ConfigManager: NPC Aggression Timer's config is not bound in In-World Tile Markers' injector.
        config = configs.getConfig(NpcAggroAreaConfig.class);
    }

    public void collect(List<Marker> out, List<ModelTarget> models)
    {
        Player player = client.getLocalPlayer();
        WorldView wv = client.getTopLevelWorldView();
        NpcAggroAreaPlugin plugin = plugin();
        if (player == null || wv == null || plugin == null || !plugin.isActive() || plugin.getSafeCenters()[1] == null) { return; }
        if (player.getHealthScale() == -1 && config.hideIfOutOfCombat()) { return; }
        GeneralPath[] all = plugin.getLinesToDisplay();
        int plane = wv.getPlane();
        GeneralPath lines = plane < all.length ? all[plane] : null;
        if (lines == null) { return; }
        // The aggressive colour while the timer runs, then the unaggressive one.
        Color color = config.unaggroAreaColor();
        Instant end = plugin.getEndTime();
        if (color == null || (end != null && Instant.now().isBefore(end))) { color = config.aggroAreaColor(); }
        if (color == null) { return; }
        // Extended: as far as the draw distance instead of the overlay's 20 tiles.
        int reach = MarkerSources.pluginRange(hdConfig, MAX_LOCAL_DRAW_LENGTH);
        lines(lines, player.getLocalLocation(), plane, color, wv.getId(), reach, out);
    }

    /**
     * The plugin's instance, from the plugin list: since RuneLite 1.13 only plugins that expose services can be a
     * {@code @PluginDependency}, and this one does not.
     */
    private NpcAggroAreaPlugin plugin()
    {
        for (net.runelite.client.plugins.Plugin p : plugins.getPlugins())
        {
            if (p instanceof NpcAggroAreaPlugin) { return (NpcAggroAreaPlugin) p; }
        }
        return null;
    }

    /** Splits the path into short polylines of connected tile edges near the player. */
    static void lines(GeneralPath path, LocalPoint player, int plane, Color color, int worldView, int reach, List<Marker> out)
    {
        float[] c = new float[6];
        List<int[]> current = new ArrayList<>();
        int n = 0, startX = 0, startY = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next())
        {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) { startX = Math.round(c[0]); startY = Math.round(c[1]); }
            // A close is a line back to the subpath's start.
            if (type == PathIterator.SEG_CLOSE) { c[0] = startX; c[1] = startY; type = PathIterator.SEG_LINETO; }
            int x = Math.round(c[0]), y = Math.round(c[1]);
            boolean near = Math.abs(x - player.getX()) <= reach && Math.abs(y - player.getY()) <= reach;
            int[] last = current.isEmpty() ? null : current.get(current.size() - 1);
            if (last != null && last[0] == x && last[1] == y) { continue; }
            if (type == PathIterator.SEG_LINETO && near && last != null)
            {
                current.add(new int[]{x, y});
                if (current.size() > EDGES_PER_LINE)
                {
                    n = emit(current, plane, color, worldView, n, out);
                    int[] end = current.get(current.size() - 1);
                    current.clear();
                    current.add(end);
                }
                continue;
            }
            n = emit(current, plane, color, worldView, n, out);
            current.clear();
            if ((type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) && near) { current.add(new int[]{x, y}); }
        }
        emit(current, plane, color, worldView, n, out);
    }

    private static int emit(List<int[]> points, int plane, Color color, int worldView, int n, List<Marker> out)
    {
        if (points.size() < 2) { return n; }
        int[] xs = new int[points.size()], ys = new int[points.size()];
        for (int i = 0; i < xs.length; i++) { xs[i] = points.get(i)[0]; ys[i] = points.get(i)[1]; }
        int[] middle = points.get(points.size() / 2);
        Marker m = new Marker("aggro:" + n, new LocalPoint(middle[0], middle[1], worldView), plane, 1, 1, color, Marker.NO_FILL,
            WIDTH, null, false);
        m.lineX = xs; m.lineY = ys;
        m.layer = Marker.AGGRO_AREA;
        out.add(m);
        return n + 1;
    }
}

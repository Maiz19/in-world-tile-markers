/*
 * Copyright (c) 2022, 2024, Trevor <https://github.com/TrevorMDev>
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
 * Pack loading adapted from Tile Packs (TilePackManager.loadPacks, loadSavedPacks and loadEnabledPacks, and
 * PointManager.getActivePoints) of https://github.com/TrevorMDev/tile-packs, commit
 * 8091ee842642c34fb88bb79c61e104500e91bd96, BSD 2-Clause License; see META-INF/LICENSE-tile-packs and
 * THIRD_PARTY_NOTICES.md. tilePacks.jsonc is its pack list of that commit, unchanged. Changes for In-World Tile Markers:
 * Tile Packs' settings are only read, and the tiles are returned to In-World Tile Markers' renderer instead of drawn.
 */
package com.inworldtilemarkers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** The tiles of the packs turned on in Tile Packs: its own packs from its pack list, custom packs from its settings. */
@Singleton
final class TilePackSource
{
    static final String GROUP = "tilePacks";

    private final ConfigManager configs;
    private final Gson gson;
    /** The pack list, read once: pack id to its tiles. */
    private Map<Integer, String> bundled;
    /** The turned-on packs' tiles per region; null when they are read again. */
    private Map<Integer, List<MarkerSources.TilePoint>> tiles;

    @Inject
    TilePackSource(ConfigManager configs, Gson gson) { this.configs = configs; this.gson = gson; }

    /** Tile Packs' settings changed: the packs are read again. */
    void clear() { tiles = null; }

    List<MarkerSources.TilePoint> tiles(int region)
    {
        if (tiles == null) { tiles = load(); }
        return tiles.getOrDefault(region, Collections.emptyList());
    }

    /** Whether any pack with tiles is turned on. */
    boolean any()
    {
        if (tiles == null) { tiles = load(); }
        return !tiles.isEmpty();
    }

    private Map<Integer, List<MarkerSources.TilePoint>> load()
    {
        Map<Integer, List<MarkerSources.TilePoint>> result = new HashMap<>();
        // A tile in two packs is drawn once, as the first pack has it.
        Set<Long> seen = new HashSet<>();
        for (int id : enabled())
        {
            for (MarkerSources.TilePoint p : MarkerSources.parse(gson, packTiles(id)))
            {
                if (seen.add((long) p.regionId << 16 | p.z << 12 | p.regionX << 6 | p.regionY))
                {
                    result.computeIfAbsent(p.regionId, r -> new ArrayList<>()).add(p);
                }
            }
        }
        return result;
    }

    /** The ids of the packs turned on, as Tile Packs' loadEnabledPacks. */
    private List<Integer> enabled()
    {
        try
        {
            List<Integer> ids = gson.fromJson(configs.getConfiguration(GROUP, "packs"), new TypeToken<List<Integer>>() { }.getType());
            if (ids != null) { ids.removeIf(Objects::isNull); return ids; }
        }
        catch (RuntimeException ignored)
        {
            // Not a list: no packs.
        }
        return Collections.emptyList();
    }

    /** A pack's tiles: a custom pack's from Tile Packs' settings, else those in the pack list. */
    private String packTiles(int id)
    {
        try
        {
            Pack saved = gson.fromJson(configs.getConfiguration(GROUP, "pack_" + id), Pack.class);
            if (saved != null && saved.packTiles != null) { return saved.packTiles; }
        }
        catch (RuntimeException ignored)
        {
            // Unreadable: the pack list's tiles, if it has the pack.
        }
        return bundled().get(id);
    }

    private Map<Integer, String> bundled()
    {
        if (bundled != null) { return bundled; }
        bundled = new HashMap<>();
        try (InputStream in = TilePackSource.class.getResourceAsStream("tilePacks.jsonc"))
        {
            // The file has comments: Gson reads leniently.
            Map<Integer, Pack> packs = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                new TypeToken<Map<Integer, Pack>>() { }.getType());
            for (Map.Entry<Integer, Pack> pack : packs.entrySet())
            {
                if (pack.getValue() != null && pack.getValue().packTiles != null) { bundled.put(pack.getKey(), pack.getValue().packTiles); }
            }
        }
        catch (Exception ignored)
        {
            // Without the list only custom packs are drawn.
        }
        return bundled;
    }

    /** Tile Packs' saved pack: its tiles, in Ground Markers' format, as one JSON text. */
    private static final class Pack
    {
        String packTiles;
    }
}

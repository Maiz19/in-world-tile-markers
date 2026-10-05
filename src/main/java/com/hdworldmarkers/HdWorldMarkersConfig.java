/*
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
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
 * The Tile Indicators options reuse the option names and descriptions of RuneLite's
 * TileIndicatorsConfig (copyright (c) 2018 Tomas Slusny, BSD 2-Clause; META-INF/LICENSE-runelite), the marking options
 * option names of its GroundMarkerConfig, ObjectIndicatorsConfig and NpcIndicatorsConfig, and the Agility options those
 * of its AgilityConfig (copyright (c) 2018 Cas; same license); the path
 * options those of Path Marker's PathMarkerConfig (copyright (c) 2022 GeChallengeM, BSD 2-Clause;
 * META-INF/LICENSE-path-marker). See THIRD_PARTY_NOTICES.md.
 */
package com.hdworldmarkers;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Set;
import net.runelite.client.config.*;

/**
 * Options follow the plugins HD World Markers adapts (Tile Indicators, Ground Markers, NPC Indicators,
 * Object Markers, Agility, Path Marker) with the same option names where the feature exists. Border widths are in
 * screen pixels, like the originals.
 */
@ConfigGroup(HdWorldMarkersConfig.GROUP)
public interface HdWorldMarkersConfig extends Config
{
    String GROUP = "hd-world-markers";

    @ConfigSection(name = "General", description = "General options", position = 0)
    String generalSection = "general";
    @ConfigSection(name = "Tile markers", description = "Tiles you mark: Shift + right-click a tile, Mark", position = 1)
    String groundSection = "ground";
    @ConfigSection(name = "Destination tile", description = "Tile Indicators: destination tile", position = 2)
    String destinationSection = "destinationTile";
    @ConfigSection(name = "Hovered tile", description = "Tile Indicators: hovered tile", position = 3)
    String hoveredSection = "hoveredTile";
    @ConfigSection(name = "Current tile", description = "Tile Indicators: your true tile", position = 4)
    String currentSection = "currentTile";
    @ConfigSection(name = "NPCs", description = "NPCs you tag by name: Shift + right-click an NPC, Tag-All", position = 5)
    String npcSection = "npcs";
    @ConfigSection(name = "NPC styles", description = "Per style the names of the NPCs that have it, and its colors. Tag style on a tagged NPC adds or removes its name", position = 6)
    String npcStyleSection = "npcStyles";
    @ConfigSection(name = "Objects", description = "Objects you mark: Shift + right-click an object, Mark object", position = 7)
    String objectSection = "objects";
    @ConfigSection(name = "Agility", description = "Agility course obstacles, shortcuts and marks of grace", position = 8)
    String agilitySection = "agility";
    @ConfigSection(name = "Active path", description = "Path Marker: the path you are walking", position = 9)
    String activePathSection = "activePath";

    @ConfigSection(name = "Hover path", description = "Path Marker: the path to the hovered tile", position = 10, closedByDefault = true)
    String hoverPathSection = "hoverPath";

    // General

    @Range(min = 8, max = 200)
    @ConfigItem(keyName = "distance", name = "Draw distance", description = "Maximum distance in tiles for saved markers. Your own tile, destination and paths are always drawn", position = 1, section = generalSection)
    default int distance() { return 200; }

    @ConfigItem(keyName = "tilesThroughWalls", name = "Through walls", description = "Draw marks in front of walls, objects and characters, like 2D overlays, and in the same colour from every angle", position = 3, section = generalSection)
    default boolean tilesThroughWalls() { return true; }

    @ConfigItem(keyName = "charactersInFrontOfTiles", name = "Player in front of marks", description = "With Through walls on, keep your own character in front of the marks", position = 4, section = generalSection)
    default boolean charactersInFrontOfTiles() { return true; }

    @ConfigItem(keyName = "warnDoubleDrawing", name = "Double drawing warning", description = "A chat message when another plugin draws the same marks (Ground Markers, Tile Packs, Agility's highlights, ...), so they show twice, with what to turn off", position = 5, section = generalSection)
    default boolean warnDoubleDrawing() { return true; }

    @ConfigItem(keyName = "predictWalk", name = "Predict walk target", description = "After Walk here, show the clicked tile as destination and a predicted path, also when you click beyond the loaded area (such as 117 HD's extended terrain). Beyond it, heights and walls are unknown: the path is a straight line.", position = 2, section = generalSection)
    default boolean predictWalk() { return true; }

    // Tile markers

    @ConfigItem(keyName = "markTiles", name = "Mark option", description = "Shift + right-click a tile: Mark, Unmark and Label", position = 0, section = groundSection)
    default boolean markTiles() { return true; }

    @Alpha
    @ConfigItem(keyName = "tileColor", name = "Tile color", description = "Color of marked tiles without a color of their own (Color), or of all with Remember tile colors off", position = 1, section = groundSection)
    default Color tileColor() { return new Color(207, 207, 207); }

    @Range(max = 255)
    @ConfigItem(keyName = "tileFillOpacity", name = "Fill opacity", description = "Opacity of the tiles' fill", position = 2, section = groundSection)
    default int tileFillOpacity() { return 50; }

    @ConfigItem(keyName = "tileBorderWidth", name = "Border width", description = "Width of the tiles' border", position = 3, section = groundSection)
    default double tileBorderWidth() { return 1.5; }

    @ConfigItem(keyName = "rememberTileColors", name = "Remember tile colors", description = "Tiles keep a color of their own (Color, and tiles from Ground Markers). Off: every tile in the Tile color", position = 6, section = groundSection)
    default boolean rememberTileColors() { return true; }

    @ConfigItem(keyName = "tilePacks", name = "Tile Packs", description = "Also draw the packs turned on in the Tile Packs plugin. Turn Tile Packs itself off once your packs are chosen, or they are drawn twice", position = 4, section = groundSection)
    default boolean tilePacks() { return true; }

    @ConfigItem(keyName = "showImportExport", name = "Import, export, sync, clear", description = "Import, Export, Sync and Clear on the world map orb's right-click menu. Import takes Ground Markers' export; Sync copies new marks from Ground Markers, Object Markers, NPC Indicators and Better NPC Highlight (its name lists); Clear removes the tiles of the area you are in", position = 5, section = groundSection)
    default boolean showImportExport() { return true; }

    // NPCs

    /** The NPC styles, in NPC Indicators' Tag style order, then the clickbox; each keeps its own list of names. */
    enum NpcStyle
    {
        HULL("Hull", "hull", "npcHullNames"),
        TILE("Tile", "tile", "npcTileNames"),
        TRUE_TILE("True tile", "truetile", "npcTrueTileNames"),
        SW_TILE("South-west tile", "swtile", "npcSouthWestTileNames"),
        SW_TRUE_TILE("South-west true tile", "swtruetile", "npcSouthWestTrueTileNames"),
        OUTLINE("Outline", "outline", "npcOutlineNames"),
        CLICKBOX("Clickbox", "clickbox", "npcClickboxNames");

        /** Its name in menus and here; its name as NPC Indicators saves it (tagstyle_); the option with its names. */
        final String label, saved, key;
        final int bit = 1 << ordinal();

        NpcStyle(String label, String saved, String key) { this.label = label; this.saved = saved; this.key = key; }

        @Override public String toString() { return label; }
    }

    @ConfigItem(keyName = "tagNpcs", name = "Tag option", description = "Shift + right-click an NPC: Tag-All or Un-tag-All, every NPC with its name", position = 0, section = npcSection)
    default boolean tagNpcs() { return true; }

    @ConfigItem(keyName = "npcTagStyles", name = "Tag styles", description = "Tag-All adds the name to these styles' lists under NPC styles. Changing this later leaves the names you tagged as they are. Ctrl + click picks more than one; none hides Tag-All", position = 1, section = npcSection)
    default Set<NpcStyle> npcTagStyles() { return EnumSet.of(NpcStyle.TRUE_TILE, NpcStyle.SW_TRUE_TILE); }

    @ConfigItem(keyName = "npcHullNames", name = "Hull names", description = "NPCs with their hull highlighted: names separated by commas, * matches any text", position = 0, section = npcStyleSection)
    default String npcHullNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcHullColor", name = "Hull color", description = "Border color of the hull", position = 1, section = npcStyleSection)
    default Color npcHullColor() { return new Color(0, 229, 255); }

    @Alpha
    @ConfigItem(keyName = "npcHullFill", name = "Hull fill", description = "Fill color of the hull; fully see-through (the default) draws no fill", position = 2, section = npcStyleSection)
    default Color npcHullFill() { return new Color(0, 229, 255, 0); }

    @ConfigItem(keyName = "npcTileNames", name = "Tile names", description = "NPCs with the tiles they stand on highlighted: names separated by commas, * matches any text", position = 3, section = npcStyleSection)
    default String npcTileNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcTileColor", name = "Tile color", description = "Border color of the tile", position = 4, section = npcStyleSection)
    default Color npcTileColor() { return new Color(189, 189, 189); }

    @Alpha
    @ConfigItem(keyName = "npcTileFill", name = "Tile fill", description = "Fill color of the tile", position = 5, section = npcStyleSection)
    default Color npcTileFill() { return new Color(0, 255, 255, 20); }

    @ConfigItem(keyName = "npcTrueTileNames", name = "True tile names", description = "NPCs with the tiles they are on for the server highlighted: names separated by commas, * matches any text", position = 6, section = npcStyleSection)
    default String npcTrueTileNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcTrueTileColor", name = "True tile color", description = "Border color of the true tile", position = 7, section = npcStyleSection)
    default Color npcTrueTileColor() { return new Color(189, 189, 189); }

    @Alpha
    @ConfigItem(keyName = "npcTrueTileFill", name = "True tile fill", description = "Fill color of the true tile", position = 8, section = npcStyleSection)
    default Color npcTrueTileFill() { return new Color(0, 255, 255, 20); }

    @ConfigItem(keyName = "npcSouthWestTileNames", name = "South west tile names", description = "NPCs with their south western tile highlighted: names separated by commas, * matches any text", position = 9, section = npcStyleSection)
    default String npcSouthWestTileNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcSouthWestTileColor", name = "South west tile color", description = "Border color of the south west tile", position = 10, section = npcStyleSection)
    default Color npcSouthWestTileColor() { return new Color(189, 189, 189); }

    @Alpha
    @ConfigItem(keyName = "npcSouthWestTileFill", name = "South west tile fill", description = "Fill color of the south west tile", position = 11, section = npcStyleSection)
    default Color npcSouthWestTileFill() { return new Color(0, 255, 255, 20); }

    @ConfigItem(keyName = "npcSouthWestTrueTileNames", name = "South west true tile names", description = "NPCs with their south western true tile highlighted: names separated by commas, * matches any text", position = 12, section = npcStyleSection)
    default String npcSouthWestTrueTileNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcSouthWestTrueTileColor", name = "South west true tile color", description = "Border color of the south west true tile", position = 13, section = npcStyleSection)
    default Color npcSouthWestTrueTileColor() { return new Color(189, 189, 189); }

    @Alpha
    @ConfigItem(keyName = "npcSouthWestTrueTileFill", name = "South west true tile fill", description = "Fill color of the south west true tile", position = 14, section = npcStyleSection)
    default Color npcSouthWestTrueTileFill() { return new Color(0, 255, 255, 20); }

    @ConfigItem(keyName = "npcOutlineNames", name = "Outline names", description = "NPCs with their outline highlighted: names separated by commas, * matches any text", position = 15, section = npcStyleSection)
    default String npcOutlineNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcOutlineColor", name = "Outline color", description = "Border color of the outline", position = 16, section = npcStyleSection)
    default Color npcOutlineColor() { return Color.CYAN; }

    @ConfigItem(keyName = "npcClickboxNames", name = "Clickbox names", description = "NPCs with their clickbox highlighted: names separated by commas, * matches any text", position = 17, section = npcStyleSection)
    default String npcClickboxNames() { return ""; }

    @Alpha
    @ConfigItem(keyName = "npcClickboxColor", name = "Clickbox color", description = "Border color of the clickbox", position = 18, section = npcStyleSection)
    default Color npcClickboxColor() { return Color.CYAN; }

    @Alpha
    @ConfigItem(keyName = "npcClickboxFill", name = "Clickbox fill", description = "Fill color of the clickbox", position = 19, section = npcStyleSection)
    default Color npcClickboxFill() { return new Color(0, 255, 255, 20); }

    @ConfigItem(keyName = "npcBorderWidth", name = "Border width", description = "Width of the highlight border", position = 2, section = npcSection)
    default double npcBorderWidth() { return 2; }

    @ConfigItem(keyName = "npcIgnoreDead", name = "Ignore dead NPCs", description = "No highlight once an NPC is dead", position = 3, section = npcSection)
    default boolean npcIgnoreDead() { return true; }

    @ConfigItem(keyName = "npcIgnorePets", name = "Ignore pets", description = "No highlight on pets", position = 4, section = npcSection)
    default boolean npcIgnorePets() { return true; }

    @ConfigItem(keyName = "npcSlayerTask", name = "Slayer task", description = "Highlight the NPCs of your Slayer task, as the Slayer plugin finds them (it must be on)", position = 5, section = npcSection)
    default boolean npcSlayerTask() { return false; }

    @ConfigItem(keyName = "npcSlayerTaskStyles", name = "Slayer task styles", description = "The styles your Slayer task's NPCs get, in the Slayer task color. Ctrl + click picks more than one", position = 6, section = npcSection)
    default Set<NpcStyle> npcSlayerTaskStyles() { return EnumSet.of(NpcStyle.HULL); }

    @Alpha
    @ConfigItem(keyName = "npcSlayerTaskColor", name = "Slayer task color", description = "Border color of your Slayer task's NPCs, unless their name has a Tag color", position = 7, section = npcSection)
    default Color npcSlayerTaskColor() { return new Color(224, 60, 49); }

    // Destination tile

    @ConfigItem(keyName = "highlightDestinationTile", name = "Highlight destination tile", description = "Highlights tile player is walking to", position = 0, section = destinationSection)
    default boolean highlightDestinationTile() { return true; }

    @Alpha
    @ConfigItem(keyName = "highlightDestinationColor", name = "Highlight color", description = "Configures the highlight color of current destination", position = 1, section = destinationSection)
    default Color highlightDestinationColor() { return new Color(1, 199, 69, 255); }

    @Alpha
    @ConfigItem(keyName = "destinationTileFillColor", name = "Fill color", description = "Configures the fill color of destination tile", position = 2, section = destinationSection)
    default Color destinationTileFillColor() { return new Color(0, 0, 0, 50); }

    @ConfigItem(keyName = "destinationTileBorderWidth", name = "Border width", description = "Width of the destination tile marker border", position = 3, section = destinationSection)
    default double destinationTileBorderWidth() { return 2; }

    @ConfigItem(keyName = "destinationTileCornersOnly", name = "Corners only", description = "Draw only the corners of the destination tile.", position = 4, section = destinationSection)
    default boolean destinationTileCornersOnly() { return true; }

    @ConfigItem(keyName = "destinationTileFadeout", name = "Fadeout", description = "Fade out the destination tile once you arrive.", position = 7, section = destinationSection)
    default boolean destinationTileFadeout() { return false; }

    @Range(min = 0, max = 10000)
    @ConfigItem(keyName = "destinationTileFadeoutDelay", name = "Fadeout delay", description = "Milliseconds the destination tile stays fully visible after you arrive, before fading", position = 7, section = destinationSection)
    default int destinationTileFadeoutDelay() { return 600; }

    @ConfigItem(keyName = "destinationTileFadeoutOutOfCombat", name = "Only fade out of combat", description = "Keep the destination tile visible while you are in combat; the fadeout (and its delay) starts once combat ends", position = 9, section = destinationSection)
    default boolean destinationTileFadeoutOutOfCombat() { return false; }

    @Range(min = 50, max = 5000)
    @ConfigItem(keyName = "destinationTileFadeoutTime", name = "Fadeout time", description = "Milliseconds to fade out the destination tile", position = 8, section = destinationSection)
    default int destinationTileFadeoutTime() { return 800; }

    @Range(min = 2, max = 20)
    @ConfigItem(keyName = "destinationTileCornerSize", name = "Destination Corner Size", description = "Each corner line is this fraction (1/size) of the tile side", position = 5, section = destinationSection)
    default int destinationTileCornerSize() { return 5; }

    // Hovered tile

    @ConfigItem(keyName = "highlightHoveredTile", name = "Highlight hovered tile", description = "Highlights tile player is hovering with mouse", position = 0, section = hoveredSection)
    default boolean highlightHoveredTile() { return true; }

    @Alpha
    @ConfigItem(keyName = "highlightHoveredColor", name = "Highlight color", description = "Configures the highlight color of hovered tile", position = 1, section = hoveredSection)
    default Color highlightHoveredColor() { return new Color(0, 0, 0, 0); }

    @Alpha
    @ConfigItem(keyName = "hoveredTileFillColor", name = "Fill color", description = "Configures the fill color of hovered tile", position = 2, section = hoveredSection)
    default Color hoveredTileFillColor() { return new Color(0, 0, 0, 50); }

    @ConfigItem(keyName = "hoveredTileBorderWidth", name = "Border width", description = "Width of the hovered tile marker border", position = 3, section = hoveredSection)
    default double hoveredTileBorderWidth() { return 2; }

    @ConfigItem(keyName = "hoveredTileCornersOnly", name = "Corners only", description = "Draw only the corners of the hovered tile.", position = 5, section = hoveredSection)
    default boolean hoveredTileCornersOnly() { return false; }

    @Range(min = 2, max = 20)
    @ConfigItem(keyName = "hoveredTileCornerSize", name = "Hovered Corner Size", description = "Each corner line is this fraction (1/size) of the tile side", position = 6, section = hoveredSection)
    default int hoveredTileCornerSize() { return 5; }

    // Current tile

    @ConfigItem(keyName = "highlightCurrentTile", name = "Highlight true tile", description = "Highlights true tile player is on as seen by server", position = 0, section = currentSection)
    default boolean highlightCurrentTile() { return true; }

    @Alpha
    @ConfigItem(keyName = "highlightCurrentColor", name = "Highlight color", description = "Configures the highlight color of current true tile", position = 1, section = currentSection)
    default Color highlightCurrentColor() { return Color.CYAN; }

    @Alpha
    @ConfigItem(keyName = "currentTileFillColor", name = "Fill color", description = "Configures the fill color of current true tile", position = 2, section = currentSection)
    default Color currentTileFillColor() { return new Color(0, 0, 0, 50); }

    @ConfigItem(keyName = "currentTileBorderWidth", name = "Border width", description = "Width of the true tile marker border", position = 3, section = currentSection)
    default double currentTileBorderWidth() { return 2; }

    @ConfigItem(keyName = "currentTileCornersOnly", name = "Corners only", description = "Draw only the corners of the current tile.", position = 4, section = currentSection)
    default boolean currentTileCornersOnly() { return true; }

    @ConfigItem(keyName = "currentTileFadeout", name = "Fadeout", description = "Fade out the true tile once the player stops moving.", position = 7, section = currentSection)
    default boolean currentTileFadeout() { return false; }

    @Range(min = 0, max = 10000)
    @ConfigItem(keyName = "currentTileFadeoutDelay", name = "Fadeout delay", description = "Milliseconds the true tile stays fully visible after you stop, before fading", position = 7, section = currentSection)
    default int currentTileFadeoutDelay() { return 600; }

    @ConfigItem(keyName = "currentTileFadeoutOutOfCombat", name = "Only fade out of combat", description = "Keep the true tile visible while you are in combat; the fadeout (and its delay) starts once combat ends", position = 9, section = currentSection)
    default boolean currentTileFadeoutOutOfCombat() { return true; }

    @Range(min = 50, max = 5000)
    @ConfigItem(keyName = "currentTileFadeoutTime", name = "Fadeout time", description = "Milliseconds to fade out the true tile", position = 8, section = currentSection)
    default int currentTileFadeoutTime() { return 1200; }

    @Range(min = 2, max = 20)
    @ConfigItem(keyName = "currentTileCornerSize", name = "Current Corner Size", description = "Each corner line is this fraction (1/size) of the tile side", position = 5, section = currentSection)
    default int currentTileCornerSize() { return 5; }

    // Objects

    @ConfigItem(keyName = "markObjects", name = "Mark object option", description = "Shift + right-click an object: Mark object or Unmark object", position = 0, section = objectSection)
    default boolean markObjects() { return true; }

    @Alpha
    @ConfigItem(keyName = "objectColor", name = "Marker color", description = "Color of marked objects without a color of their own (Mark border color)", position = 1, section = objectSection)
    default Color objectColor() { return new Color(162, 246, 255); }

    @Alpha
    @ConfigItem(keyName = "objectFillColor", name = "Fill color", description = "Fill of marked objects without a fill of their own (Mark fill color). Unset: dark for hulls, a light shade of the border for clickboxes and tiles", position = 2, section = objectSection)
    Color objectFillColor();

    @ConfigItem(keyName = "objectHull", name = "Highlight hull", description = "Highlight the object's hull", position = 3, section = objectSection)
    default boolean objectHull() { return true; }

    @ConfigItem(keyName = "objectOutline", name = "Highlight outline", description = "Highlight the object's outline", position = 4, section = objectSection)
    default boolean objectOutline() { return false; }

    @ConfigItem(keyName = "objectClickbox", name = "Highlight clickbox", description = "Highlight the object's clickbox", position = 5, section = objectSection)
    default boolean objectClickbox() { return false; }

    @ConfigItem(keyName = "objectTile", name = "Highlight tile", description = "Highlight the tiles the object stands on", position = 6, section = objectSection)
    default boolean objectTile() { return false; }

    @ConfigItem(keyName = "objectBorderWidth", name = "Border width", description = "Width of the highlight border", position = 7, section = objectSection)
    default double objectBorderWidth() { return 2; }

    // Agility, as RuneLite's Agility plugin

    @ConfigItem(keyName = "agilityObstacles", name = "Show clickboxes", description = "Show agility course and other obstacle clickboxes", position = 0, section = agilitySection)
    default boolean agilityObstacles() { return true; }

    @Alpha
    @ConfigItem(keyName = "agilityColor", name = "Overlay color", description = "Color of agility obstacles and shortcuts", position = 1, section = agilitySection)
    default Color agilityColor() { return Color.GREEN; }

    @ConfigItem(keyName = "agilityShortcuts", name = "Highlight agility shortcuts", description = "Highlight agility shortcuts; those above your level in orange", position = 2, section = agilitySection)
    default boolean agilityShortcuts() { return true; }

    @ConfigItem(keyName = "agilityMarks", name = "Highlight marks of grace", description = "Highlight retrievable marks of grace, and the obstacles in their color while there is one", position = 3, section = agilitySection)
    default boolean agilityMarks() { return true; }

    @Alpha
    @ConfigItem(keyName = "agilityMarkColor", name = "Mark highlight color", description = "Color of highlighted marks of grace", position = 4, section = agilitySection)
    default Color agilityMarkColor() { return Color.RED; }

    @ConfigItem(keyName = "agilityPortals", name = "Highlight portals", description = "Highlight the Prifddinas portals", position = 5, section = agilitySection)
    default boolean agilityPortals() { return true; }

    @Alpha
    @ConfigItem(keyName = "agilityPortalColor", name = "Portals color", description = "Color of highlighted Prifddinas portals", position = 6, section = agilitySection)
    default Color agilityPortalColor() { return Color.MAGENTA; }

    @ConfigItem(keyName = "agilityTraps", name = "Show trap overlay", description = "Highlight traps on agility courses", position = 7, section = agilitySection)
    default boolean agilityTraps() { return true; }

    @Alpha
    @ConfigItem(keyName = "agilityTrapColor", name = "Trap overlay color", description = "Color of agility traps", position = 8, section = agilitySection)
    default Color agilityTrapColor() { return Color.RED; }

    @ConfigItem(keyName = "agilityStick", name = "Highlight stick", description = "Highlight the retrievable stick in the Werewolf Agility Course", position = 9, section = agilitySection)
    default boolean agilityStick() { return true; }

    @Alpha
    @ConfigItem(keyName = "agilityStickColor", name = "Stick highlight color", description = "Color of the highlighted stick", position = 10, section = agilitySection)
    default Color agilityStickColor() { return Color.RED; }

    @ConfigItem(keyName = "agilitySepulchreObstacles", name = "Highlight Sepulchre obstacles", description = "Highlight pillars and stairs in the Hallowed Sepulchre", position = 11, section = agilitySection)
    default boolean agilitySepulchreObstacles() { return true; }

    @ConfigItem(keyName = "agilitySepulchreSkilling", name = "Highlight skill challenges", description = "Highlight skilling challenges in the Hallowed Sepulchre", position = 12, section = agilitySection)
    default boolean agilitySepulchreSkilling() { return true; }

    // Path Marker, active path

    enum DrawLocations { BOTH, GAME_WORLD, MINIMAP }
    enum DrawMode { FULL_PATH, TARGET_TILE }
    enum PathDisplaySetting { ALWAYS, WHILE_KEY_PRESSED, TOGGLE_ON_KEYPRESS, NEVER }
    enum MarkerStyle { TILE, DOT }

    @ConfigItem(keyName = "activePathDrawLocations", name = "Draw location(s)", description = "Marks your active path in the game world and/or on the minimap", position = 0, section = activePathSection)
    default DrawLocations activePathDrawLocations() { return DrawLocations.GAME_WORLD; }

    @ConfigItem(keyName = "activePathDrawMode", name = "Draw mode", description = "Marks the full path or only the target tile", position = 1, section = activePathSection)
    default DrawMode activePathDrawMode() { return DrawMode.FULL_PATH; }

    @ConfigItem(keyName = "activePathMarkerStyle", name = "Marker Style", description = "Tiles or dots", position = 1, section = activePathSection)
    default MarkerStyle activePathMarkerStyle() { return MarkerStyle.TILE; }

    @Alpha
    @ConfigItem(keyName = "activePathStroke1", name = "Main outline color", description = "Outline color of tiles you stand on", position = 2, section = activePathSection)
    default Color activePathStroke1() { return new Color(38, 38, 38, 69); }

    @Alpha
    @ConfigItem(keyName = "activePathFill1", name = "Main fill color", description = "Fill color of tiles you stand on", position = 3, section = activePathSection)
    default Color activePathFill1() { return new Color(13, 46, 10, 80); }

    @Alpha
    @ConfigItem(keyName = "activePathStroke2", name = "Secondary outline color", description = "Outline color of tiles you run past without standing on them", position = 4, section = activePathSection)
    default Color activePathStroke2() { return new Color(38, 38, 38, 69); }

    @Alpha
    @ConfigItem(keyName = "activePathFill2", name = "Secondary fill color", description = "Fill color of tiles you run past without standing on them", position = 5, section = activePathSection)
    default Color activePathFill2() { return new Color(38, 38, 38, 80); }

    @ConfigItem(keyName = "activePathDisplaySetting", name = "Display", description = "When to show the active path", position = 6, section = activePathSection)
    default PathDisplaySetting activePathDisplaySetting() { return PathDisplaySetting.ALWAYS; }

    @ConfigItem(keyName = "displayKeybindActivePath", name = "Keybind", description = "Key for the display setting", position = 7, section = activePathSection)
    default Keybind displayKeybindActivePath() { return Keybind.NOT_SET; }

    @ConfigItem(keyName = "pathBorderWidth", name = "Border width", description = "Width of path tile borders, for both paths", position = 8, section = activePathSection)
    default double pathBorderWidth() { return 1; }

    // Path Marker, hover path

    @ConfigItem(keyName = "hoverPathDrawLocations", name = "Draw location(s)", description = "Marks the hover path in the game world and/or on the minimap", position = 0, section = hoverPathSection)
    default DrawLocations hoverPathDrawLocations() { return DrawLocations.MINIMAP; }

    @ConfigItem(keyName = "hoverPathDrawMode", name = "Draw mode", description = "Marks the full path or only the target tile", position = 1, section = hoverPathSection)
    default DrawMode hoverPathDrawMode() { return DrawMode.FULL_PATH; }

    @ConfigItem(keyName = "hoverPathMarkerStyle", name = "Marker Style", description = "Tiles or dots", position = 1, section = hoverPathSection)
    default MarkerStyle hoverPathMarkerStyle() { return MarkerStyle.TILE; }

    @Alpha
    @ConfigItem(keyName = "hoverPathStroke1", name = "Main outline color", description = "Outline color of tiles you would stand on", position = 2, section = hoverPathSection)
    default Color hoverPathStroke1() { return new Color(255, 0, 255, 255); }

    @Alpha
    @ConfigItem(keyName = "hoverPathFill1", name = "Main fill color", description = "Fill color of tiles you would stand on", position = 3, section = hoverPathSection)
    default Color hoverPathFill1() { return new Color(255, 0, 255, 50); }

    @Alpha
    @ConfigItem(keyName = "hoverPathStroke2", name = "Secondary outline color", description = "Outline color of tiles you would run past", position = 4, section = hoverPathSection)
    default Color hoverPathStroke2() { return new Color(0, 255, 0, 255); }

    @Alpha
    @ConfigItem(keyName = "hoverPathFill2", name = "Secondary fill color", description = "Fill color of tiles you would run past", position = 5, section = hoverPathSection)
    default Color hoverPathFill2() { return new Color(0, 255, 0, 50); }

    @ConfigItem(keyName = "hoverPathDisplaySetting", name = "Display", description = "When to show the hover path", position = 6, section = hoverPathSection)
    default PathDisplaySetting hoverPathDisplaySetting() { return PathDisplaySetting.ALWAYS; }

    @ConfigItem(keyName = "displayKeybindHoverPath", name = "Keybind", description = "Key for the display setting", position = 7, section = hoverPathSection)
    default Keybind displayKeybindHoverPath() { return Keybind.NOT_SET; }

    @ConfigItem(keyName = "hoverPathMinimap", name = "Show on minimap", description = "Also draw the hover path on the minimap", position = 9, section = hoverPathSection)
    default boolean hoverPathMinimap() { return false; }

    @ConfigItem(keyName = "drawOnlyIfNoActivePath", name = "Draw only if no active path", description = "Marks the hover path only if you don't have an active path visible", position = 8, section = hoverPathSection)
    default boolean drawOnlyIfNoActivePath() { return false; }
}

/*
 * Copyright (c) 2018, SomeoneWithAnInternetConnection
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Cas <https://github.com/casvandongen>
 * Copyright (c) 2019, MrGroggle
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
 * The obstacle lists are RuneLite's Agility plugin's (Obstacles), and what is highlighted, and in which colors, follows
 * its AgilityPlugin.onTileObject and onItemSpawned and AgilityOverlay.render, of https://github.com/runelite/runelite, tag
 * runelite-parent-1.13.1, BSD 2-Clause License; see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for
 * HD World Markers: drawn by its renderer, in its own options; the Sepulchre's arrows and swords are left out.
 */
package com.hdworldmarkers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.game.AgilityShortcut;

/** Agility course obstacles, shortcuts, Prifddinas portals, traps, marks of grace and the stick, as the Agility plugin highlights them. */
@Singleton
final class AgilitySource
{
    private static final Set<Integer> OBSTACLE_IDS = ImmutableSet.of(
        // Gnome
        ObjectID.OBSTICAL_NET2, ObjectID.CLIMBING_BRANCH, ObjectID.CLIMBING_TREE, ObjectID.OBSTICAL_NET3, ObjectID.OBSTICAL_PIPE3_1,
        ObjectID.OBSTICAL_PIPE3_2, ObjectID.GNOME_LOG_BALANCE1, ObjectID.BALANCING_ROPE,
        // Brimhaven
        ObjectID.AGILITYARENA_PLANK3, ObjectID.AGILITYARENA_PLANK2, ObjectID.AGILITYARENA_PLANK, ObjectID.AGILITYARENA_ROPESWING, ObjectID.AGILITYARENA_PILLAR_TOP, ObjectID.AGILITYARENA_LOWWALL, ObjectID.AGILITYARENA_LOGBALANCE1, ObjectID.AGILITYARENA_LOGBALANCE3,
        ObjectID.AGILITYARENA_LEDGEBALANCE2, ObjectID.AGILITYARENA_LEDGEBALANCE, ObjectID.AGILITYARENA_MONKEYBARS_END, ObjectID.AGILITYARENA_ROPEBALANCE, ObjectID.AGILITYARENA_HANDHOLDS,
        // Draynor
        ObjectID.ROOFTOPS_DRAYNOR_WALLCLIMB, ObjectID.ROOFTOPS_DRAYNOR_TIGHTROPE_1, ObjectID.ROOFTOPS_DRAYNOR_TIGHTROPE_2, ObjectID.ROOFTOPS_DRAYNOR_WALLCROSSING, ObjectID.ROOFTOPS_DRAYNOR_WALLSCRAMBLE, ObjectID.ROOFTOPS_DRAYNOR_LEAPDOWN, ObjectID.ROOFTOPS_DRAYNOR_CRATE, ObjectID.FARMING_STYLE,
        // Al-Kharid
        ObjectID.ROOFTOPS_KHARID_WALLCLIMB, ObjectID.ROOFTOPS_KHARID_TIGHTROPE_1, ObjectID.ROOFTOPS_KHARID_ROPE_SWING, ObjectID.ROOFTOPS_KHARID_SLIDE_SIDE, ObjectID.ROOFTOPS_KHARID_BAMBOO_TREE_TOP, ObjectID.ROOFTOPS_KHARID_WALLCLIMB_2,
        ObjectID.ROOFTOPS_KHARID_TIGHTROPE_4, ObjectID.ROOFTOPS_KHARID_LEAPDOWN,
        // Pyramid
        ObjectID.AGILITY_PYRAMID_STEPS1, ObjectID.AGILITY_PYRAMID_LOW_WALL, ObjectID.AGILITY_PYRAMID_LEDGE_HOTSPOT, ObjectID.AGILITY_PYRAMID_PLANK_START, ObjectID.AGILITY_PYRAMID_WALLHANG_START_SOUTH_HOTSPOT, ObjectID.AGILITY_PYRAMID_LEDGEBALANCE_START_SOUTH_HOTSPOT, ObjectID.AGILITY_PYRAMID_STEPS1, ObjectID.AGILITY_PYRAMID_WALLHANG_START_WEST_HOTSPOT,
        ObjectID.AGILITY_PYRAMID_JUMP_HOTSPOT, ObjectID.AGILITY_PYRAMID_WALLHANG_START_HOTSPOT_GROUND, ObjectID.AGILITY_PYRAMID_LOW_WALL, ObjectID.AGILITY_PYRAMID_JUMP_HOTSPOT, ObjectID.AGILITY_PYRAMID_LEDGEBALANCE_START_WEST_HOTSPOT, ObjectID.AGILITY_PYRAMID_PLANK_START, ObjectID.AGILITY_PYRAMID_WALL_ROCKS, ObjectID.AGILITY_PYRAMID_DOOR_HOTSPOT,
        // Varrock
        ObjectID.ROOFTOPS_VARROCK_WALLCLIMB, ObjectID.ROOFTOPS_VARROCK_CLOTHESLINE, ObjectID.ROOFTOPS_VARROCK_LEAPTORUINS, ObjectID.ROOFTOPS_VARROCK_WALLSWING, ObjectID.ROOFTOPS_VARROCK_WALLSCRAMBLE, ObjectID.ROOFTOPS_VARROCK_LEAPTOBALCONY, ObjectID.ROOFTOPS_VARROCK_LEAPDOWN, ObjectID.ROOFTOPS_VARROCK_STEPUPROOF, ObjectID.ROOFTOPS_VARROCK_FINISH,
        // Penguin
        ObjectID.PENG_AGILITY_CRUSHCOURSE_STEPSTONE01, ObjectID.PENG_JUMP_STONE_CLICKZONE_01, ObjectID.PENG_JUMP_STONE_CLICKZONE_02, ObjectID.PENG_JUMP_STONE_CLICKZONE_03,
        ObjectID.PENG_JUMP_STONE_CLICKZONE_04, ObjectID.PENG_JUMP_STONE_CLICKZONE_05, ObjectID.PENG_JUMP_STONE_CLICKZONE_06, ObjectID.PENG_JUMP_STONE_CLICKZONE_07,
        ObjectID.PENG_ICICLEPILLAR_CLICKZONE, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS01, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS02, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS03, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS04, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS05, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS06, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS07, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS08, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS09, ObjectID.PENG_AGILITY_FENCING_DOOR,
        // Barbarian
        ObjectID.OBSTICAL_ROPESWING1, ObjectID.BARBARIAN_LOG_BALANCE1, ObjectID.AGILITY_OBSTICAL_NET_BARBARIAN, ObjectID.BALANCING_LEDGE1, ObjectID.BARBARIAN_LADDERTOP_NORIM, ObjectID.CASTLECRUMBLY1,
        // Canifis
        ObjectID.ROOFTOPS_CANIFIS_START_TREE, ObjectID.ROOFTOPS_CANIFIS_JUMP, ObjectID.ROOFTOPS_CANIFIS_JUMP_2, ObjectID.ROOFTOPS_CANIFIS_JUMP_5, ObjectID.ROOFTOPS_CANIFIS_JUMP_3, ObjectID.ROOFTOPS_CANIFIS_POLEVAULT, ObjectID.ROOFTOPS_CANIFIS_JUMP_4, ObjectID.ROOFTOPS_CANIFIS_LEAPDOWN,
        // Ape atoll
        ObjectID._100_ILM_STEPPING_STONE, ObjectID._100_ILM_CLIMBABLE_TREE, ObjectID._100_ILM_MONKEYBARS_START, ObjectID._100_ILM_CLIFF_CLIMB_1, ObjectID._100_ILM_ROPE_SWING, ObjectID._100_ILM_AGILITY_TREE_BASE,
        // Falador
        ObjectID.ROOFTOPS_FALADOR_WALLCLIMB, ObjectID.ROOFTOPS_FALADOR_TIGHTROPE_1, ObjectID.ROOFTOPS_FALADOR_HANDHOLDS_START, ObjectID.ROOFTOPS_FALADOR_GAP_1, ObjectID.ROOFTOPS_FALADOR_GAP_2, ObjectID.ROOFTOPS_FALADOR_TIGHTROPE_2,
        ObjectID.ROOFTOPS_FALADOR_TIGHTROPE_3, ObjectID.ROOFTOPS_FALADOR_GAP_3, ObjectID.ROOFTOPS_FALADOR_LEDGE_1, ObjectID.ROOFTOPS_FALADOR_LEDGE_2, ObjectID.ROOFTOPS_FALADOR_LEDGE_3A, ObjectID.ROOFTOPS_FALADOR_LEDGE_3B, ObjectID.ROOFTOPS_FALADOR_LEDGE_4, ObjectID.ROOFTOPS_FALADOR_EDGE,
        // Wilderness
        ObjectID.OBSTICAL_PIPE2, ObjectID.OBSTICAL_ROPESWING2, ObjectID.STEPPINGSTONE1, ObjectID.WILDERNESS_LOG_BALANCE1, ObjectID.WILDCLIMBINGROCK,
        // Seers
        ObjectID.ROOFTOPS_SEERS_WALLCLIMB, ObjectID.ROOFTOPS_SEERS_JUMP, ObjectID.ROOFTOPS_SEERS_TIGHTROPE, ObjectID.ROOFTOPS_SEERS_JUMP_1, ObjectID.ROOFTOPS_SEERS_JUMP_2, ObjectID.ROOFTOPS_SEERS_LEAPDOWN,
        // Dorgesh-Kaan
        ObjectID.DORGESH_CABLEBALANCE_START, ObjectID.DORGESH_CAVES_ROPE_SWING, ObjectID.DORGESH_CAVES_MONKEYBARS_END, ObjectID.DORGESH_CAVE_JUTTINGWALL, ObjectID.DORGESH_CAVES_PIPE_AGILITY2, ObjectID.DORGESH_PYLON_GRAPPLE_BASE,
        ObjectID.DORGESH_OLD_GENERATOR_CONSOLE, ObjectID.DORGESH_OLD_GENERATOR_BOILER, ObjectID.DORGESH_OLD_GENERATOR_STEPS2, ObjectID.DORGESH_OLD_GENERATOR_STEPS_BLANK_TOP, ObjectID.DORGESH_GENERATOR_STEPS_BLANK_TOP, ObjectID.DORGESH_GENERATOR_STEPS2,
        // Pollniveach
        ObjectID.ROOFTOPS_POLLNIVNEACH_BASKET, ObjectID.ROOFTOPS_POLLNIVNEACH_MARKETSTALL, ObjectID.ROOFTOPS_POLLNIVNEACH_HANGINGBANNER, ObjectID.ROOFTOPS_POLLNIVNEACH_GAP, ObjectID.ROOFTOPS_POLLNIVNEACH_TREE, ObjectID.ROOFTOPS_POLLNIVNEACH_WALLCLIMB,
        ObjectID.ROOFTOPS_POLLNIVNEACH_MONKEYBARS_START, ObjectID.ROOFTOPS_POLLNIVNEACH_TREETOP, ObjectID.ROOFTOPS_POLLNIVNEACH_LINE,
        // Rellaka
        ObjectID.ROOFTOPS_RELLEKKA_WALLCLIMB, ObjectID.ROOFTOPS_RELLEKKA_GAP_1, ObjectID.ROOFTOPS_RELLEKKA_TIGHTROPE_1, ObjectID.ROOFTOPS_RELLEKKA_GAP_2, ObjectID.ROOFTOPS_RELLEKKA_GAP_3, ObjectID.ROOFTOPS_RELLEKKA_TIGHTROPE_3, ObjectID.ROOFTOPS_RELLEKKA_DROPOFF,
        // Ardougne
        ObjectID.ROOFTOPS_ARDY_WALLCLIMB, ObjectID.ROOFTOPS_ARDY_JUMP, ObjectID.ROOFTOPS_ARDY_PLANK, ObjectID.ROOFTOPS_ARDY_JUMP_2, ObjectID.ROOFTOPS_ARDY_JUMP_3, ObjectID.ROOFTOPS_ARDY_WALLCROSSING, ObjectID.ROOFTOPS_ARDY_JUMP_4,
        // Meiyerditch
        ObjectID.SANG_BOAT_WATER_MULTILOC, ObjectID.SANG_BOAT_JUMP_ROCK, ObjectID.SANG_BOAT_WALL_CLIMB_UP_ROCK, ObjectID.SANG_BOAT_WALL_CLIMB_DOWN_ROCK, ObjectID.SANG_BOAT_EDGE_JUMP, ObjectID.MEIYERDITCH_WALL_FLOORBOARDS_MULTI_LOC, ObjectID.MIEYERDITCH_WALL_UNDERBOARDS_MULTI_LOC, ObjectID.MYQ3_RUBBLE_WEST_WALL,
        ObjectID.MYQ3_RUBBLE_EAST_WALL, ObjectID.MYQ3_AGIL_2_JUMP_SOUTH, ObjectID.MYQ3_AGIL_2_JUMP_NORTH, ObjectID.MYQ3_AGIL_3_JUMP_EAST, ObjectID.MYQ3_AGIL_3_JUMP_WEST, ObjectID.MYQ3_AGIL_4_FLOORACTIVE_MULTI_1, ObjectID.MYQ3_AGIL_4_FLOORACTIVE_MULTI_2,
        ObjectID.MYQ3_AGIL_5_CRAWL_WALL, ObjectID.MYQ3_AGIL_6_FLOORACTIVE_MULTI_1, ObjectID.MYQ3_AGIL_6_FLOORACTIVE_MULTI_2, ObjectID.MYQ3_AGIL_8_TRAPDOOR_TUNNEL_MULTI, ObjectID.MYQ3_AGIL_9_TUNNEL_SOUTH, ObjectID.MYQ3_AGIL_10_SHELF_CLIMB_UP, ObjectID.MYQ3_AGIL_10_SHELF_CLIMB_DOWN, ObjectID.MYQ3_AGIL_11_CRAWL_WALL,
        ObjectID.MYQ3_AGIL_12_JUMP_EAST, ObjectID.MYQ3_AGIL_12_JUMP_WEST, ObjectID.MYQ3_AGIL_14_LOCKED_DOOR, ObjectID.MYQ3_AGIL_17_JUMP_SOUTH, ObjectID.MYQ3_AGIL_17_JUMP_NORTH, ObjectID.MYQ3_AGIL_18_SHELF_CLIMB_UP,
        ObjectID.MYQ3_AGIL_18_SHELF_CLIMB_DOWN, ObjectID.MYQ3_AGIL_20_JUMP_SOUTH, ObjectID.MYQ3_AGIL_20_JUMP_NORTH, ObjectID.MYQ3_AGIL_22_TIGHTROPE_WEST, ObjectID.MYQ3_AGIL_22_TIGHTROPE_EAST,
        ObjectID.MYQ3_AGIL_24_FLOORACTIVE_MULTI_1, ObjectID.MYQ3_AGIL_24_FLOORACTIVE_MULTI_2, ObjectID.MYQ3_AGIL_25_SHELF_CLIMB_UP, ObjectID.MYQ3_AGIL_25_SHELF_CLIMB_DOWN, ObjectID.MYQ3_AGIL_26_SHELF_CLIMB_DOWN, ObjectID.MYQ3_AGIL_26_SHELF_CLIMB_UP, ObjectID.MYQ3_AGIL_27_JUMP_NORTH,
        ObjectID.MYQ3_AGIL_27_JUMP_SOUTH, ObjectID.MYQ3_AGIL_29_JUMP_SOUTH, ObjectID.MYQ3_AGIL_29_JUMP_NORTH, ObjectID.MYQ3_AGIL_30_JUMP_WEST, ObjectID.MYQ3_AGIL_30_JUMP_EAST,
        ObjectID.MYQ3_AGIL_33_LADDER_FLOOR_MULTI, ObjectID.MYQ3_AGIL_41_JUMP_EAST, ObjectID.MYQ3_AGIL_41_JUMP_WEST, ObjectID.AREA_SANGUINE_GHETTO_STAIRS_DOWN, ObjectID.AREA_SANGUINE_MYREQUE_SECRET_WALL_CLOSED, ObjectID.MYQ3_GHETTO_BARICADE_WALL_SECRETPASS, ObjectID.MYQ3_LADDER_DOWN,
        ObjectID.MYQ3_LADDER_UP, ObjectID.MYQ3_LADDER_DOWN_2, ObjectID.MYQ3_LADDER_UP_2, ObjectID.MYQ3_SECRET_ROCK_BARRICADE_UNLOCK, ObjectID.DARKM_OUTER_WALL_3H_MEYERDITCH_WALL_SHORTCUT_TOP, ObjectID.DARKM_OUTER_WALL_3H_MEYERDITCH_WALL_SHORTCUT_BOTTOM,
        // Werewolf
        ObjectID.WEREWOLF_STEPING_STONE, ObjectID.WEREWOLF_HURDLE_MID, ObjectID.WEREWOLF_HURDLE_END, ObjectID.WEREWOLF_HURDLE_END_MIRROR, ObjectID.WAA_PIPE, ObjectID._100_ILM_CLIFF_CLIMB_1, ObjectID.WEREWOLF_SLIDE_CENTER,
        ObjectID.WEREWOLF_SLIDE_SIDE, ObjectID.WEREWOLF_SLIDE_SIDE_MIRROR,
        // Prifddinas
        ObjectID.PRIF_AGILITY_START_LADDER, ObjectID.PRIF_AGILITY_TIGHTROPE_START1, ObjectID.PRIF_AGILITY_CHIMNEY_JUMP, ObjectID.PRIF_AGILITY_ROOF_JUMP, ObjectID.PRIF_AGILITY_DARK_HOLE_ACTIVE, ObjectID.PRIF_AGILITY_TREE_LADDER_LONG, ObjectID.PRIF_AGILITY_TREE_LADDER_SHORT,
        ObjectID.PRIF_AGILITY_ROPE_BRIDGE1, ObjectID.PRIF_AGILITY_TIGHTROPE1, ObjectID.PRIF_AGILITY_ROPE_BRIDGE2, ObjectID.PRIF_AGILITY_TIGHTROPE2, ObjectID.PRIF_AGILITY_TIGHTROPE3, ObjectID.PRIF_AGILITY_DARK_HOLE_END,
        // Rellekka Lighthouse
        ObjectID.HORROR_JUMPING_SPOT2, ObjectID.HORROR_JUMPING_SPOT4, ObjectID.HORROR_JUMPING_SPOT5, ObjectID.HORROR_JUMPING_SPOT7, ObjectID.HORROR_JUMPING_SPOT9, ObjectID.HORROR_JUMPING_SPOT10,
        ObjectID.HORROR_JUMPING_SPOT8, ObjectID.HORROR_JUMPING_SPOT6, ObjectID.HORROR_JUMPING_SPOT3, ObjectID.HORROR_JUMPING_SPOT1,
        // Shayzien
        ObjectID.SHAYZIEN_AGILITY_BOTH_START_LADDER, ObjectID.SHAYZIEN_AGILITY_BOTH_ROPE_CLIMB, ObjectID.SHAYZIEN_AGILITY_BOTH_ROPE_WALK,
        // Shayzien basic
        ObjectID.SHAYZIEN_AGILITY_LOW_BAR_CLIMB, ObjectID.SHAYZIEN_AGILITY_LOW_ROPE_WALK_1, ObjectID.SHAYZIEN_AGILITY_LOW_ROPE_WALK_2, ObjectID.SHAYZIEN_AGILITY_LOW_END_JUMP,
        // Shayzien hard
        ObjectID.SHAYZIEN_AGILITY_UP_SWING_JUMP_1, ObjectID.SHAYZIEN_AGILITY_UP_JUMP_PLATFORM_1, ObjectID.SHAYZIEN_AGILITY_UP_JUMP_PLATFORM_2, ObjectID.SHAYZIEN_AGILITY_UP_SWING_JUMP_2, ObjectID.SHAYZIEN_AGILITY_UP_END_JUMP,
        // Necropolis
        ObjectID.BCS_NECROPOLIS_ENTRY,
        // Colossal Wyrm
        ObjectID.VARLAMORE_WYRM_AGILITY_START_LADDER_TRIGGER, ObjectID.VARLAMORE_WYRM_AGILITY_BALANCE_1_TRIGGER, ObjectID.VARLAMORE_WYRM_AGILITY_END_ZIPLINE_TRIGGER,
        // Colossal Wyrm basic
        ObjectID.VARLAMORE_WYRM_AGILITY_BASIC_BALANCE_1_TRIGGER, ObjectID.VARLAMORE_WYRM_AGILITY_BASIC_MONKEYBARS_1_TRIGGER, ObjectID.VARLAMORE_WYRM_AGILITY_BASIC_LADDER_1_TRIGGER,
        // Colossal Wyrm advanced
        ObjectID.VARLAMORE_WYRM_AGILITY_ADVANCED_LADDER_1_TRIGGER, ObjectID.VARLAMORE_WYRM_AGILITY_ADVANCED_JUMP_1_TRIGGER, ObjectID.VARLAMORE_WYRM_AGILITY_ADVANCED_BALANCE_1_TRIGGER
    );

    private static final Set<Integer> PORTAL_OBSTACLE_IDS = ImmutableSet.of(
        // Prifddinas portals
        ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_1, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_2, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_3, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_4, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_5, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_6
    );

    private static final Set<Integer> TRAP_OBSTACLE_IDS = ImmutableSet.of(
        // Agility pyramid
        ObjectID.INVISWALL_SERVERSIDE, ObjectID.AGILITY_PYRAMID_PENNY_MULTILOC_LEVEL_2, ObjectID.AGILITY_PYRAMID_PENNY_MULTILOC_LEVEL_4
    );

    private static final List<Integer> TRAP_OBSTACLE_REGIONS = ImmutableList.of(12105, 13356);

    private static final Set<Integer> SEPULCHRE_OBSTACLE_IDS = ImmutableSet.of(
        // Stairs and Platforms (and one Gate)
        ObjectID.HALLOWED_PATH_END_GATE, ObjectID.HALLOWED_PATH_END_JUMPOVER_01_NORTH, ObjectID.HALLOWED_PATH_END_JUMPOVER_01_EAST, ObjectID.HALLOWED_PATH_END_JUMPOVER_01_SOUTH, ObjectID.HALLOWED_PATH_END_JUMPOVER_01_WEST, ObjectID.HALLOWED_PATH_END_JUMPOVER_02,
        ObjectID.HALLOWED_FLOOR_2_STEPPINGSTONE, ObjectID.HALLOWED_FLOOR_5_STEPPINGSTONE, ObjectID.HALLOWED_FLOOR_1_NORTHPATH_DROP, ObjectID.HALLOWED_FLOOR_1_EASTPATH_STAIRS, ObjectID.HALLOWED_FLOOR_1_SOUTHPATH_STAIRS, ObjectID.HALLOWED_FLOOR_1_WESTPATH_DROP,
        ObjectID.HALLOWED_FLOOR_2_NORTHPATH_STAIRS, ObjectID.HALLOWED_FLOOR_2_EASTPATH_STAIRS, ObjectID.HALLOWED_FLOOR_2_SOUTHPATH_STAIRS, ObjectID.HALLOWED_FLOOR_2_WESTPATH_DROP, ObjectID.HALLOWED_FLOOR_3_EASTPATH_DROP, ObjectID.HALLOWED_FLOOR_3_WESTPATH_DROP,
        ObjectID.HALLOWED_FLOOR_4_NORTHPATH_DROP, ObjectID.HALLOWED_FLOOR_4_SOUTHPATH_STAIRS, ObjectID.HALLOWED_FLOOR_5_DROP_1, ObjectID.HALLOWED_FLOOR_5_DROP_2
    );

    private static final Set<Integer> SEPULCHRE_SKILL_OBSTACLE_IDS = ImmutableSet.of(
        // Grapple, Portal, and Bridge skill obstacles
        // They are multilocs, thus we use the NullObjectID
        ObjectID.HALLOWED_RANGED_PILLAR_MULTI, ObjectID.HALLOWED_PRAYER_MULTI, ObjectID.HALLOWED_PRAYER_MULTI_MIRROR, ObjectID.HALLOWED_CONSTRUCTION_MULTI, ObjectID.HALLOWED_CONSTRUCTION_POOL_MULTI, ObjectID.HALLOWED_MAGIC_MULTI
    );

    /** Shortcuts by obstacle id; one id can be several shortcuts, told apart by place. */
    private static final Multimap<Integer, AgilityShortcut> SHORTCUT_OBSTACLE_IDS;

    static
    {
        ImmutableMultimap.Builder<Integer, AgilityShortcut> builder = ImmutableMultimap.builder();
        for (AgilityShortcut shortcut : AgilityShortcut.values())
        {
            for (int obstacle : shortcut.getObstacleIds()) { builder.put(obstacle, shortcut); }
        }
        SHORTCUT_OBSTACLE_IDS = builder.build();
    }

    /** Shortcuts above your Agility level, as the Agility plugin. */
    private static final Color HIGH_LEVEL = Color.ORANGE;
    /** The menu options the client makes for an object under the mouse: the id and a scene tile of the object. */
    private static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(MenuAction.GAME_OBJECT_FIRST_OPTION, MenuAction.GAME_OBJECT_SECOND_OPTION,
        MenuAction.GAME_OBJECT_THIRD_OPTION, MenuAction.GAME_OBJECT_FOURTH_OPTION, MenuAction.GAME_OBJECT_FIFTH_OPTION, MenuAction.EXAMINE_OBJECT,
        MenuAction.WIDGET_TARGET_ON_GAME_OBJECT);

    private final Client client;
    private final HdWorldMarkersConfig config;
    /** The obstacles in the scene, with their shortcut (null: not a shortcut). */
    private final Map<TileObject, AgilityShortcut> obstacles = new IdentityHashMap<>();
    private final Map<Tile, Integer> marks = new IdentityHashMap<>();
    private Tile stick;

    @Inject
    AgilitySource(Client client, HdWorldMarkersConfig config) { this.client = client; this.config = config; }

    void clear() { obstacles.clear(); marks.clear(); stick = null; }

    /** As the Agility plugin's onTileObject. */
    void add(TileObject object)
    {
        obstacles.remove(object);
        int id = object.getId();
        if (OBSTACLE_IDS.contains(id) || PORTAL_OBSTACLE_IDS.contains(id)
            || TRAP_OBSTACLE_IDS.contains(id) && TRAP_OBSTACLE_REGIONS.contains(object.getWorldLocation().getRegionID())
            || SEPULCHRE_OBSTACLE_IDS.contains(id) || SEPULCHRE_SKILL_OBSTACLE_IDS.contains(id))
        {
            obstacles.put(object, null);
        }
        if (!SHORTCUT_OBSTACLE_IDS.containsKey(id)) { return; }
        // The shortcut nearest this object of those with its id.
        AgilityShortcut closest = null;
        int distance = -1;
        for (AgilityShortcut shortcut : SHORTCUT_OBSTACLE_IDS.get(id))
        {
            if (!shortcut.matches(client, object)) { continue; }
            if (shortcut.getWorldLocation() == null) { closest = shortcut; break; }
            int d = shortcut.getWorldLocation().distanceTo2D(object.getWorldLocation());
            if (closest == null || d < distance) { closest = shortcut; distance = d; }
        }
        if (closest != null) { obstacles.put(object, closest); }
    }

    void remove(TileObject object) { obstacles.remove(object); }

    void removeWorldView(WorldView wv)
    {
        obstacles.keySet().removeIf(o -> o.getWorldView() == wv);
        marks.keySet().removeIf(t -> t.getLocalLocation() != null && t.getLocalLocation().getWorldView() == wv.getId());
        if (stick != null && stick.getLocalLocation() != null && stick.getLocalLocation().getWorldView() == wv.getId()) { stick = null; }
    }

    /** A ground item appeared or went: marks of grace (per tile, they stack) and the Werewolf course's stick. */
    void item(Tile tile, TileItem item, boolean spawned)
    {
        if (item.getId() == ItemID.GRACE) { marks.merge(tile, spawned ? 1 : -1, Integer::sum); marks.values().removeIf(n -> n <= 0); }
        if (item.getId() == ItemID.WAA_STICK) { stick = spawned ? tile : stick == tile ? null : stick; }
    }

    /** Traps, marks of grace and the stick: tiles. */
    void tiles(List<Marker> out)
    {
        if (obstacles.isEmpty()) { return; }
        if (config.agilityTraps())
        {
            Color color = config.agilityTrapColor();
            for (TileObject object : obstacles.keySet())
            {
                if (!TRAP_OBSTACLE_IDS.contains(object.getId())) { continue; }
                // OverlayUtil.renderPolygon's fill and width, which the Agility plugin's tile highlights use.
                Marker m = MarkerSources.footprint("agility:" + object.getHash(), object, color, Marker.BLACK_FILL, 2);
                if (m != null) { out.add(m); }
            }
        }
        if (config.agilityMarks()) { for (Tile tile : marks.keySet()) { tile(out, "mark", tile, config.agilityMarkColor()); } }
        if (config.agilityStick() && stick != null) { tile(out, "stick", stick, config.agilityStickColor()); }
    }

    private static void tile(List<Marker> out, String what, Tile tile, Color color)
    {
        if (tile.getLocalLocation() == null) { return; }
        Marker m = new Marker("agility:" + what + ":" + tile.getWorldLocation(), tile.getLocalLocation(), tile.getPlane(), 1, 1, color, Marker.BLACK_FILL, 2, null);
        m.layer = Marker.OBJECT;
        out.add(m);
    }

    /** The obstacles' clickboxes, in the Agility plugin's colors: darker under the mouse. */
    void models(List<ModelTarget> out)
    {
        if (obstacles.isEmpty()) { return; }
        int level = client.getBoostedSkillLevel(Skill.AGILITY);
        // The options once, not per obstacle: each is a call through the config proxy.
        boolean shortcuts = config.agilityShortcuts(), courses = config.agilityObstacles(), portals = config.agilityPortals();
        boolean sepulchre = config.agilitySepulchreObstacles(), skilling = config.agilitySepulchreSkilling();
        Color courseColor = config.agilityColor(), portalColor = config.agilityPortalColor();
        Color markColor = config.agilityMarks() && !marks.isEmpty() ? config.agilityMarkColor() : null;
        Set<TileObject> hovered = hovered();
        for (Map.Entry<TileObject, AgilityShortcut> entry : obstacles.entrySet())
        {
            TileObject object = entry.getKey();
            int id = object.getId();
            AgilityShortcut shortcut = entry.getValue();
            if (shortcut != null && !shortcuts || TRAP_OBSTACLE_IDS.contains(id) || OBSTACLE_IDS.contains(id) && !courses
                || SEPULCHRE_OBSTACLE_IDS.contains(id) && !sepulchre || SEPULCHRE_SKILL_OBSTACLE_IDS.contains(id) && !skilling)
            {
                continue;
            }
            WorldView wv = object.getWorldView();
            if (wv == null || object.getPlane() != wv.getPlane()) { continue; }
            Color color = shortcut == null || shortcut.getLevel() <= level ? courseColor : HIGH_LEVEL;
            if (markColor != null) { color = markColor; }
            if (PORTAL_OBSTACLE_IDS.contains(id))
            {
                if (!portals) { continue; }
                color = portalColor;
            }
            Renderable renderable = ModelTarget.renderable(object);
            if (renderable == null) { continue; }
            if (hovered.contains(object)) { color = color.darker(); }
            Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() / 5);
            out.add(ModelTarget.object("agility:" + object.getHash(), object, renderable, ModelTarget.offsetX(object), ModelTarget.offsetY(object),
                color, fill, 1, true, object::getClickbox));
        }
    }

    /**
     * The obstacles under the mouse, which the Agility plugin draws darker: those the client made a menu option for (an
     * object's id and one of its scene tiles). Each obstacle's clickbox was made every tick to see whether it held the mouse.
     */
    private Set<TileObject> hovered()
    {
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
        if (entries == null || entries.length == 0) { return Collections.emptySet(); }
        Set<TileObject> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MenuEntry e : entries)
        {
            if (!OBJECT_OPTIONS.contains(e.getType())) { continue; }
            for (TileObject o : obstacles.keySet())
            {
                if (on(o, e.getWorldViewId(), e.getParam0(), e.getParam1()) && seenAs(o, e.getIdentifier())) { out.add(o); }
            }
        }
        return out;
    }

    /** Whether the object lies on this scene tile of this world view. */
    private static boolean on(TileObject o, int worldView, int x, int y)
    {
        WorldView wv = o.getWorldView();
        if (wv == null || wv.getId() != worldView) { return false; }
        if (o instanceof GameObject)
        {
            Point min = ((GameObject) o).getSceneMinLocation(), max = ((GameObject) o).getSceneMaxLocation();
            return min != null && max != null && x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY();
        }
        LocalPoint l = o.getLocalLocation();
        return l != null && l.getSceneX() == x && l.getSceneY() == y;
    }

    /** Whether a menu's object id is this object's, or one of the forms it takes (a multiloc). */
    private boolean seenAs(TileObject o, int id)
    {
        if (o.getId() == id) { return true; }
        ObjectComposition composition = client.getObjectDefinition(o.getId());
        int[] impostors = composition == null ? null : composition.getImpostorIds();
        if (impostors != null) { for (int impostor : impostors) { if (impostor == id) { return true; } } }
        return false;
    }

    /** Whether there are obstacles in the scene. */
    boolean any() { return !obstacles.isEmpty(); }
}

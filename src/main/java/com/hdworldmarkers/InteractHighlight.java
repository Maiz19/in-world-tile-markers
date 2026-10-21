/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
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
 * What is hovered and interacted with, and the click's colour fade, follow RuneLite's Interact Highlight
 * (InteractHighlightPlugin, InteractHighlightOverlay) of https://github.com/runelite/runelite, BSD 2-Clause License;
 * see META-INF/LICENSE-runelite and THIRD_PARTY_NOTICES.md. Changes for HD World Markers: the outlines are drawn by
 * its renderer, in its own options and styles (hull, clickbox, tiles besides the outline); ground items too; what is
 * hovered is what a click would choose (MouseTargets.clicked), with the menu open the option the mouse is on.
 */
package com.hdworldmarkers;

import static net.runelite.api.MenuAction.*;

import com.hdworldmarkers.HdWorldMarkersConfig.InteractStyle;
import com.hdworldmarkers.HdWorldMarkersConfig.Interactable;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/** The NPC, object or ground item the mouse is on, and the one you clicked or interact with, in the styles picked for each. */
@Singleton
final class InteractHighlight
{
    private final Client client;
    private final HdWorldMarkersConfig config;
    private final MouseTargets mouse;
    private TileObject object;
    private MouseTargets.Item item;
    private Actor actor;
    private int clickTick, clickCycle;

    @Inject InteractHighlight(Client client, HdWorldMarkersConfig config, MouseTargets mouse) { this.client = client; this.config = config; this.mouse = mouse; }

    void clear() { object = null; item = null; actor = null; }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOADING) { object = null; item = null; }
        // The tick count starts again at login: a click of the last session no longer holds back what comes.
        if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING) { clear(); clickTick = 0; clickCycle = 0; }
    }

    @Subscribe public void onItemDespawned(ItemDespawned e) { if (item != null && e.getItem() == item.model) { item = null; } }

    @Subscribe public void onNpcSpawned(NpcSpawned e) { spawned(e.getNpc()); }

    private void spawned(Actor a)
    {
        Player me = client.getLocalPlayer();
        if (me != null && a == me.getInteracting()) { actor = a; }
    }

    @Subscribe public void onNpcDespawned(NpcDespawned e) { if (e.getNpc() == actor) { actor = null; } }

    /** Arrived where the click walked: the object is no longer what you go to. */
    @Subscribe public void onGameTick(GameTick e)
    {
        if (client.getTickCount() > clickTick && client.getLocalDestinationLocation() == null && actor == null) { object = null; item = null; }
    }

    @Subscribe public void onInteractingChanged(InteractingChanged e)
    {
        if (e.getSource() != client.getLocalPlayer() || client.getTickCount() <= clickTick) { return; }
        object = null; item = null;
        // Players are never highlighted (as in RuneLite's Interact Highlight): the Plugin Hub allows no player highlighting.
        actor = e.getTarget() instanceof NPC ? e.getTarget() : null;
    }

    @Subscribe public void onMenuOptionClicked(MenuOptionClicked e)
    {
        MenuEntry entry = e.getMenuEntry();
        switch (e.getMenuAction())
        {
            case WIDGET_TARGET_ON_GAME_OBJECT: case GAME_OBJECT_FIRST_OPTION: case GAME_OBJECT_SECOND_OPTION: case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION: case GAME_OBJECT_FIFTH_OPTION:
                clicked(mouse.object(entry), null);
                break;
            case WIDGET_TARGET_ON_NPC: case NPC_FIRST_OPTION: case NPC_SECOND_OPTION: case NPC_THIRD_OPTION: case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
                clicked(null, entry.getNpc());
                break;
            case WIDGET_TARGET_ON_GROUND_ITEM: case GROUND_ITEM_FIRST_OPTION: case GROUND_ITEM_SECOND_OPTION: case GROUND_ITEM_THIRD_OPTION:
            case GROUND_ITEM_FOURTH_OPTION: case GROUND_ITEM_FIFTH_OPTION:
                clicked(null, null);
                item = mouse.item(entry);
                break;
            // Clicks that end an interaction, and ones on a player (never highlighted).
            case WIDGET_TARGET_ON_WIDGET: case WALK: case WIDGET_TARGET_ON_PLAYER: case PLAYER_FIRST_OPTION: case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION: case PLAYER_FOURTH_OPTION: case PLAYER_FIFTH_OPTION: case PLAYER_SIXTH_OPTION: case PLAYER_SEVENTH_OPTION:
            case PLAYER_EIGHTH_OPTION:
                clear();
                break;
            default:
                if (e.isItemOp()) { clear(); }
        }
    }

    private void clicked(TileObject o, Actor a)
    {
        object = o; item = null; actor = a;
        clickTick = client.getTickCount();
        clickCycle = client.getGameCycle();
    }

    /** Something highlighted now: an NPC, object or ground item, in its border and fill colour, hovered or interacted with. */
    private interface Shown { void accept(Object thing, Color color, Color fill, boolean hovered); }

    /**
     * What is highlighted now, in its colours: what you interact with first, then what a click would choose (the top
     * option, as RuneLite's Interact Highlight; not what is under it), each once.
     */
    private void each(Shown shown)
    {
        // No style picked: off.
        boolean hover = !config.interactHoverStyles().isEmpty(), interact = !config.interactStyles().isEmpty();
        if (!hover && !interact) { return; }
        Set<Interactable> things = config.interactThings();
        boolean objects = things.contains(Interactable.OBJECTS), items = things.contains(Interactable.ITEMS), npcs = things.contains(Interactable.NPCS);
        // Not by identity: a ground item is looked up anew each time.
        Set<Object> done = new HashSet<>();
        if (interact)
        {
            Color end = config.interactColor(), endFill = config.interactFill();
            long fade = client.getGameCycle() - clickCycle;
            if (object != null && objects && done.add(object))
            {
                shown.accept(object, click(config.interactHoverColor(), end, fade), click(config.interactHoverFill(), endFill, fade), false);
            }
            if (item != null && items && done.add(item))
            {
                shown.accept(item, click(config.interactHoverColor(), end, fade), click(config.interactHoverFill(), endFill, fade), false);
            }
            if (actor instanceof NPC && npcs && !hidden((NPC) actor) && done.add(actor))
            {
                shown.accept(actor, click(config.interactNpcHoverColor(), end, fade), click(config.interactNpcHoverFill(), endFill, fade), false);
            }
        }
        if (!hover) { return; }
        Object top = mouse.clicked();
        if (top instanceof TileObject && objects || top instanceof MouseTargets.Item && items)
        {
            if (done.add(top)) { shown.accept(top, config.interactHoverColor(), config.interactHoverFill(), true); }
        }
        if (top instanceof NPC && npcs && !hidden((NPC) top) && done.add(top))
        {
            shown.accept(top, config.interactNpcHoverColor(), config.interactNpcHoverFill(), true);
        }
    }

    /** Hide in combat: an NPC you can attack (it has an Attack option) is not highlighted either. */
    private boolean hidden(NPC npc)
    {
        if (!config.interactHideInCombat()) { return false; }
        NPCComposition c = npc.getTransformedComposition();
        String[] options = c == null ? null : c.getActions();
        if (options == null) { return false; }
        for (String option : options) { if (option != null && "Attack".equals(Text.removeTags(option))) { return true; } }
        return false;
    }

    /** The styles of the moment, hovered or interacted with. */
    private Set<InteractStyle> styles(boolean hovered) { return hovered ? config.interactHoverStyles() : config.interactStyles(); }

    private static String key(boolean hovered, Object thing) { return (hovered ? "hover:" : "interact:") + Highlights.key(thing); }

    /** The outlines, hulls and clickboxes of the moment, the one you interact with first; in other world views (a boat) the outline to elsewhere. */
    void targets(List<ModelTarget> out, List<ModelTarget> elsewhere)
    {
        double width = config.interactBorderWidth();
        WorldView top = client.getTopLevelWorldView();
        each((thing, color, fill, hovered) -> Highlights.models(out, elsewhere, top, client, key(hovered, thing), thing,
            Highlights.of(styles(hovered)), color, fill, width));
    }

    /** The tile and true tile styles of the moment: an NPC's tiles (true tile: where the server has it), an object's footprint. */
    void tiles(List<Marker> out)
    {
        double width = config.interactBorderWidth();
        each((thing, color, fill, hovered) -> Highlights.tiles(out, Marker.HOVER, key(hovered, thing), thing, Highlights.of(styles(hovered)), color, fill, width));
    }

    /**
     * From the hover colour through white to the interact colour, over the ten game cycles after the click. White at
     * the colours' own opacity: a fill flashes as see-through as it is.
     */
    private static Color click(Color start, Color end, long cycles)
    {
        // Past the fade, or a game cycle count that started again.
        if (cycles >= 10 || cycles < 0) { return end; }
        Color white = new Color(255, 255, 255, Math.max(start.getAlpha(), end.getAlpha()));
        return cycles < 5 ? ColorUtil.colorLerp(start, white, cycles / 5f) : ColorUtil.colorLerp(white, end, (cycles - 5) / 5f);
    }
}

package com.hdworldmarkers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import static net.runelite.api.MenuAction.*;

/**
 * What is under the mouse, for the highlights that change there. Agility's darker obstacles count every option the
 * client made (names), so an obstacle stays darker while another option is on top (a character in front of it);
 * Interact highlight takes what a click would choose (clicked). An object is found on the tile its option names, by its
 * id or one of the forms it takes. Each obstacle's clickbox was once tested against the mouse every tick.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
final class MouseTargets
{
    static final Set<MenuAction> OBJECT_OPTIONS = EnumSet.of(GAME_OBJECT_FIRST_OPTION, GAME_OBJECT_SECOND_OPTION,
        GAME_OBJECT_THIRD_OPTION, GAME_OBJECT_FOURTH_OPTION, GAME_OBJECT_FIFTH_OPTION, EXAMINE_OBJECT, WIDGET_TARGET_ON_GAME_OBJECT);
    static final Set<MenuAction> NPC_OPTIONS = EnumSet.of(NPC_FIRST_OPTION, NPC_SECOND_OPTION, NPC_THIRD_OPTION, NPC_FOURTH_OPTION,
        NPC_FIFTH_OPTION, EXAMINE_NPC, WIDGET_TARGET_ON_NPC);
    static final Set<MenuAction> ITEM_OPTIONS = EnumSet.of(GROUND_ITEM_FIRST_OPTION, GROUND_ITEM_SECOND_OPTION, GROUND_ITEM_THIRD_OPTION,
        GROUND_ITEM_FOURTH_OPTION, GROUND_ITEM_FIFTH_OPTION, EXAMINE_ITEM_GROUND, WIDGET_TARGET_ON_GROUND_ITEM);
    private final Client client;

    /** A ground item that shows: the pile it lies in and its model there (one of the top three of the pile). */
    @RequiredArgsConstructor
    static final class Item
    {
        final ItemLayer layer;
        final TileItem model;

        @Override public boolean equals(Object o) { return o instanceof Item && ((Item) o).layer == layer && ((Item) o).model == model; }

        @Override public int hashCode() { return System.identityHashCode(layer) * 31 + System.identityHashCode(model); }
    }

    /** The client's menu options of the moment; none without a menu. */
    private MenuEntry[] entries()
    {
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
        return entries == null ? new MenuEntry[0] : entries;
    }

    /** The object options of the menu now, read once for many objects (see names). */
    List<MenuEntry> objectOptions()
    {
        List<MenuEntry> out = new ArrayList<>();
        for (MenuEntry e : entries()) { if (OBJECT_OPTIONS.contains(e.getType())) { out.add(e); } }
        return out;
    }

    /**
     * Whether an object option is on this object: its world view, a tile of its footprint, its id or one of the forms it
     * takes (last: a definition lookup).
     */
    boolean names(MenuEntry e, TileObject o)
    {
        WorldView wv = o.getWorldView();
        if (wv == null || wv.getId() != e.getWorldViewId()) { return false; }
        int x = e.getParam0(), y = e.getParam1();
        boolean on;
        if (o instanceof GameObject)
        {
            Point min = ((GameObject) o).getSceneMinLocation(), max = ((GameObject) o).getSceneMaxLocation();
            on = min != null && max != null && x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY();
        }
        else
        {
            LocalPoint l = o.getLocalLocation();
            on = l != null && l.getSceneX() == x && l.getSceneY() == y;
        }
        return on && seenAs(client, o, e.getIdentifier());
    }

    /**
     * What a click would choose now: the object, ground item (an Item) or NPC of the top option, or with the menu
     * open of the option the mouse is on (row). Null for anything else.
     */
    Object clicked()
    {
        MenuEntry[] entries = entries();
        if (entries.length == 0) { return null; }
        MenuEntry e = client.isMenuOpen() ? row(client, entries) : entries[entries.length - 1];
        if (e == null) { return null; }
        MenuAction type = e.getType();
        if (OBJECT_OPTIONS.contains(type)) { return object(e); }
        if (ITEM_OPTIONS.contains(type)) { return item(e); }
        return NPC_OPTIONS.contains(type) ? e.getNpc() : null;
    }

    /**
     * The client's menu layout: the "Choose Option" title, then one row per option. RuneLite's Menu API gives the menu's
     * place and size but not the row the mouse is on, so it is worked out from these.
     */
    private static final int MENU_TITLE = 19, MENU_ROW = 15;

    /** In an open menu, the option the mouse is on (the last one at the top); null on none. */
    static MenuEntry row(Client client, MenuEntry[] entries)
    {
        Menu menu = client.getMenu();
        Point mouse = client.getMouseCanvasPosition();
        if (menu == null || mouse == null) { return null; }
        int dy = mouse.getY() - menu.getMenuY() - MENU_TITLE, i = dy < 0 ? -1 : entries.length - 1 - dy / MENU_ROW;
        return i >= 0 && i < entries.length && mouse.getX() >= menu.getMenuX() && mouse.getX() < menu.getMenuX() + menu.getMenuWidth() ? entries[i] : null;
    }

    /** The ground item a menu option is on, when it shows (an item under the top three of its pile has no model). */
    Item item(MenuEntry e) { return item(tile(e), e.getIdentifier()); }

    /** The ground item of this id on the tile, when it shows. */
    static Item item(Tile tile, int id)
    {
        ItemLayer layer = tile == null ? null : tile.getItemLayer();
        if (layer == null) { return null; }
        Renderable[] models = {layer.getTop(), layer.getMiddle(), layer.getBottom()};
        for (Renderable r : models) { if (r instanceof TileItem && ((TileItem) r).getId() == id) { return new Item(layer, (TileItem) r); } }
        return null;
    }

    /** The object a menu option is on, from the scene tile it names; null when it is gone. */
    TileObject object(MenuEntry e) { return object(client, tile(e), e.getIdentifier()); }

    /** The object on the tile seen as this id: a game object, wall, decoration or ground object. */
    static TileObject object(Client client, Tile tile, int id)
    {
        if (tile == null) { return null; }
        for (GameObject o : tile.getGameObjects()) { if (o != null && seenAs(client, o, id)) { return o; } }
        TileObject[] others = {tile.getWallObject(), tile.getDecorativeObject(), tile.getGroundObject()};
        for (TileObject o : others) { if (o != null && seenAs(client, o, id)) { return o; } }
        return null;
    }

    /** The scene tile a menu option names. */
    private Tile tile(MenuEntry e)
    {
        WorldView wv = client.getWorldView(e.getWorldViewId());
        return wv == null ? null : tile(wv, e.getParam0(), e.getParam1(), wv.getPlane());
    }

    /**
     * A scene tile (scene x and y, as options give them), also in the extended tiles around the loaded area, which
     * start as much further out as they are larger than the scene; null outside them.
     */
    static Tile tile(WorldView wv, int sceneX, int sceneY, int plane)
    {
        Scene scene = wv.getScene();
        Tile[][][] tiles = scene == null ? null : scene.getExtendedTiles();
        if (tiles == null) { tiles = scene == null ? null : scene.getTiles(); }
        if (tiles == null || plane < 0 || plane >= tiles.length) { return null; }
        int offset = (tiles[plane].length - wv.getSizeX()) / 2, x = sceneX + offset, y = sceneY + offset;
        return x < 0 || y < 0 || x >= tiles[plane].length || y >= tiles[plane][x].length ? null : tiles[plane][x][y];
    }

    /** Whether a menu's object id is this object's, or one of the forms it takes (a multiloc), or the form it takes now. */
    static boolean seenAs(Client client, TileObject o, int id)
    {
        if (o.getId() == id) { return true; }
        ObjectComposition c = client.getObjectDefinition(o.getId());
        if (c == null) { return false; }
        int[] impostors = c.getImpostorIds();
        if (impostors == null) { return false; }
        for (int impostor : impostors) { if (impostor == id) { return true; } }
        ObjectComposition now = c.getImpostor();
        return now != null && now.getId() == id;
    }
}

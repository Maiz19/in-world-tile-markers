package com.hdworldmarkers;

import com.hdworldmarkers.HdWorldMarkersConfig.InteractStyle;
import java.awt.Color;
import java.util.Collection;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * An NPC, player, object or ground item in the styles picked for it, as Interact highlight and other plugins' marks
 * (ExternalMarks) draw them: outline, hull and clickbox as models, the tile styles as markers.
 */
final class Highlights
{
    private Highlights() { }

    /** The styles, as bits. True tile and the south-west tiles are an NPC's or player's; an object has its footprint. */
    static final int OUTLINE = 1, HULL = 2, CLICKBOX = 4, TILE = 8, TRUE_TILE = 16, SW_TILE = 32, SW_TRUE_TILE = 64;
    /** The styles' names in other plugins' messages, by bit. */
    private static final String[] NAMES = {"outline", "hull", "clickbox", "tile", "truetile", "swtile", "swtruetile"};

    static int of(Collection<InteractStyle> styles)
    {
        int bits = 0;
        for (InteractStyle s : styles)
        {
            switch (s)
            {
                case OUTLINE: bits |= OUTLINE; break;
                case HULL: bits |= HULL; break;
                case CLICKBOX: bits |= CLICKBOX; break;
                case TILE: bits |= TILE; break;
                case TRUE_TILE: bits |= TRUE_TILE; break;
                default: break;
            }
        }
        return bits;
    }

    /** A style's bit from its name in a message; 0 for an unknown name. */
    static int named(String name)
    {
        for (int i = 0; i < NAMES.length; i++) { if (NAMES[i].equalsIgnoreCase(name)) { return 1 << i; } }
        return 0;
    }

    /** What a thing's marks are keyed by: its kind, world view and identity there. */
    static String key(Object thing)
    {
        if (thing instanceof TileObject) { TileObject o = (TileObject) thing; return "object:" + o.getWorldView().getId() + ":" + o.getHash(); }
        if (thing instanceof MouseTargets.Item)
        {
            MouseTargets.Item i = (MouseTargets.Item) thing;
            return "item:" + i.layer.getWorldView().getId() + ":" + i.layer.getHash() + ":" + i.model.getId();
        }
        Actor a = (Actor) thing;
        return "actor:" + a.getWorldView().getId() + ":" + (a instanceof NPC ? "n" + ((NPC) a).getIndex() : "p" + ((Player) a).getId());
    }

    /** A thing's outline, hull and clickbox; in other world views than top (a boat) only the outline, to elsewhere. */
    static void models(List<ModelTarget> out, List<ModelTarget> elsewhere, WorldView top, Client client, String key, Object thing,
        int styles, Color color, Color fill, double width)
    {
        if (thing instanceof Actor)
        {
            Actor a = (Actor) thing;
            if (a.getWorldView() != top)
            {
                if ((styles & OUTLINE) != 0) { elsewhere.add(ModelTarget.npcOutline(key, a, color, width)); }
                return;
            }
            if ((styles & OUTLINE) != 0) { out.add(ModelTarget.npcOutline(key + ":outline", a, color, width)); }
            if ((styles & HULL) != 0) { out.add(ModelTarget.npc(key + ":hull", a, color, fill, width)); }
            if ((styles & CLICKBOX) != 0) { out.add(ModelTarget.npcClickbox(key + ":clickbox", a, color, fill, width, client)); }
            return;
        }
        if (thing instanceof MouseTargets.Item)
        {
            ItemLayer layer = ((MouseTargets.Item) thing).layer;
            TileItem model = ((MouseTargets.Item) thing).model;
            boolean here = layer.getWorldView() == top;
            if ((styles & OUTLINE) != 0) { (here ? out : elsewhere).add(ModelTarget.objectOutline(key + ":outline", layer, model, 0, 0, color, width)); }
            if (!here) { return; }
            if ((styles & HULL) != 0) { out.add(ModelTarget.object(key + ":hull", layer, model, 0, 0, color, fill, width, false, layer::getClickbox)); }
            if ((styles & CLICKBOX) != 0) { out.add(ModelTarget.object(key + ":clickbox", layer, model, 0, 0, color, fill, width, true, layer::getClickbox)); }
            return;
        }
        TileObject o = (TileObject) thing;
        if (o.getWorldView() != top)
        {
            if ((styles & OUTLINE) != 0) { elsewhere.add(ModelTarget.objectOutline(key, o, ModelTarget.renderable(o), 0, 0, color, width)); }
            return;
        }
        if ((styles & OUTLINE) != 0) { MarkerSources.addParts(out, key, o, color, Marker.NO_FILL, width, true); }
        if ((styles & HULL) != 0) { MarkerSources.addParts(out, key, o, color, fill, width, false); }
        Renderable r = ModelTarget.renderable(o);
        if ((styles & CLICKBOX) != 0 && r != null)
        {
            out.add(ModelTarget.object(key + ":clickbox", o, r, ModelTarget.offsetX(o), ModelTarget.offsetY(o), color, fill, width, true, o::getClickbox));
        }
    }

    /**
     * A thing's tile styles on this marker layer: an NPC's or player's tiles (true tile: where the server has it; the
     * south-west tiles: one tile, of its footprint), an object's or item's footprint (any tile style, once).
     */
    static void tiles(List<Marker> out, int layer, String key, Object thing, int styles, Color color, Color fill, double width)
    {
        if ((styles & (TILE | TRUE_TILE | SW_TILE | SW_TRUE_TILE)) == 0) { return; }
        if (thing instanceof TileObject || thing instanceof MouseTargets.Item)
        {
            TileObject o = thing instanceof TileObject ? (TileObject) thing : ((MouseTargets.Item) thing).layer;
            Marker m = MarkerSources.footprint(key + ":tile", o, color, fill, width);
            if (m != null) { m.layer = layer; out.add(m); }
            return;
        }
        Actor a = (Actor) thing;
        NPCComposition c = a instanceof NPC ? ((NPC) a).getTransformedComposition() : null;
        int size = c == null ? 1 : Math.max(1, Math.min(64, c.getSize())), plane = a.getWorldView().getPlane(), offset = (size - 1) * 64;
        LocalPoint local = a.getLocalLocation();
        if ((styles & TILE) != 0 && local != null) { out.add(marker(layer, key + ":tile", local, plane, size, color, fill, width)); }
        if ((styles & SW_TILE) != 0 && local != null) { out.add(marker(layer, key + ":sw", local.plus(-offset, -offset), plane, 1, color, fill, width)); }
        // The server's tile is the south-west one of the footprint.
        LocalPoint server = (styles & (TRUE_TILE | SW_TRUE_TILE)) != 0 ? LocalPoint.fromWorld(a.getWorldView(), a.getWorldLocation()) : null;
        if (server == null) { return; }
        if ((styles & TRUE_TILE) != 0) { out.add(marker(layer, key + ":true", server.plus(offset, offset), plane, size, color, fill, width)); }
        if ((styles & SW_TRUE_TILE) != 0) { out.add(marker(layer, key + ":swtrue", server, plane, 1, color, fill, width)); }
    }

    private static Marker marker(int layer, String key, LocalPoint point, int plane, int size, Color color, Color fill, double width)
    {
        Marker m = new Marker(key, point, plane, size, size, color, fill, width, null);
        m.layer = layer;
        return m;
    }
}

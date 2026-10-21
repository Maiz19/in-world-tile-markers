package com.hdworldmarkers;

import java.awt.Color;
import java.awt.Shape;
import java.util.function.Supplier;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * One model part whose hull, clickbox or outline is marked: an NPC (or player), or one renderable
 * of an object (walls and decorations have two). The model is read at render time.
 */
final class ModelTarget
{
    final String key;
    final Actor actor;
    final TileObject object;
    final Renderable renderable;
    final int offsetX, offsetY;
    final Color color, fill;
    final float borderWidth;
    /** Clickbox: RuneLite's clickbox shape instead of the convex hull. */
    final boolean clickbox;
    /** Outline: the model's silhouette, with the line outside it, instead of the convex hull. */
    final boolean outline;
    private final Supplier<Shape> fallback;

    private ModelTarget(String key, Actor actor, TileObject object, Renderable renderable, int offsetX, int offsetY,
        Color color, Color fill, double borderWidth, boolean clickbox, boolean outline, Supplier<Shape> fallback)
    {
        this.outline = outline;
        this.key = key; this.actor = actor; this.object = object; this.renderable = renderable;
        this.offsetX = offsetX; this.offsetY = offsetY; this.color = color; this.fill = fill;
        this.borderWidth = (float) Math.max(0, Math.min(16, borderWidth));
        this.clickbox = clickbox; this.fallback = fallback;
    }

    /** An NPC's (or player's) hull. */
    static ModelTarget npc(String key, Actor npc, Color color, Color fill, double borderWidth)
    { return new ModelTarget(key, npc, null, null, 0, 0, color, fill, borderWidth, false, false, npc::getConvexHull); }

    /** An NPC's (or player's) clickbox; without a scene route RuneLite's own (Perspective.getClickbox). */
    static ModelTarget npcClickbox(String key, Actor npc, Color color, Color fill, double borderWidth, Client client)
    { return new ModelTarget(key, npc, null, null, 0, 0, color, fill, borderWidth, true, false, () -> clickbox(client, npc)); }

    static Shape clickbox(Client client, Actor npc)
    {
        net.runelite.api.coords.LocalPoint lp = npc.getLocalLocation();
        return lp == null ? null : Perspective.getClickbox(client, npc.getWorldView(), npc.getModel(), npc.getCurrentOrientation(), lp.getX(), lp.getY(),
            height(client, npc, lp));
    }

    /** An NPC's or player's outline; without a scene route RuneLite's outline renderer draws it (no 2D shape). */
    static ModelTarget npcOutline(String key, Actor actor, Color color, double borderWidth)
    { return new ModelTarget(key, actor, null, null, 0, 0, color, Marker.NO_FILL, borderWidth, false, true, () -> null); }

    static ModelTarget objectOutline(String key, TileObject object, Renderable renderable, int offsetX, int offsetY, Color color, double borderWidth)
    { return new ModelTarget(key, null, object, renderable, offsetX, offsetY, color, Marker.NO_FILL, borderWidth, false, true, () -> null); }

    /** fallback is the client's own 2D shape for this part, used when the scene route is unavailable. */
    static ModelTarget object(String key, TileObject object, Renderable renderable, int offsetX, int offsetY,
        Color color, Color fill, double borderWidth, boolean clickbox, Supplier<Shape> fallback)
    { return new ModelTarget(key, null, object, renderable, offsetX, offsetY, color, fill, borderWidth, clickbox, false, fallback); }

    /**
     * Current geometry. Actor models may live in a shared client buffer; copy them
     * before the next call. Static scene objects can hold unlit ModelData instead
     * of a Model, so the vertices are read from either.
     */
    Mesh<?> mesh()
    {
        try
        {
            if (actor != null) { return actor.getModel(); }
            if (renderable == null) { return null; }
            return renderable instanceof Mesh ? (Mesh<?>) renderable : renderable.getModel();
        }
        catch (NullPointerException ex)
        {
            // Client geometry can be temporarily unavailable while an object changes. Retry next frame.
            return null;
        }
    }

    LocalPoint location()
    {
        if (actor != null) { return actor.getLocalLocation(); }
        return new LocalPoint(object.getX() + offsetX, object.getY() + offsetY, object.getWorldView().getId());
    }

    int plane() { return actor != null ? actor.getWorldView().getPlane() : object.getPlane(); }

    /** Game objects keep a model orientation; wall, decoration and ground models are already rotated. */
    int orientation()
    {
        if (actor != null) { return actor.getCurrentOrientation(); }
        return object instanceof GameObject ? ((GameObject) object).getModelOrientation() : 0;
    }

    int height(Client client)
    {
        if (actor != null) { return height(client, actor, actor.getLocalLocation()); }
        // A ground item lies on what is under it (a table), its pile's height above the ground.
        return object instanceof ItemLayer ? object.getZ() - ((ItemLayer) object).getHeight() : object.getZ();
    }

    /**
     * Where the client places an actor's model: on its footprint's height, raised by its animation (an NPC on a dock or
     * deck bobs with it). The tile height alone put hulls and outlines that much too low, seen most from a low camera.
     */
    static int height(Client client, Actor a, LocalPoint at)
    {
        return Perspective.getFootprintTileHeight(client, at, a.getWorldView().getPlane(), a.getFootprintSize()) - a.getAnimationHeightOffset();
    }

    Shape shape()
    {
        try { return fallback.get(); }
        // The 2D clickbox/hull can read the same unavailable client model as mesh().
        catch (NullPointerException ex) { return null; }
    }

    /**
     * Where a decoration's model sits relative to its object (walls, decorations on them): the scene draws the
     * clickbox from the model's own projection, so without it the clickbox showed beside the model.
     */
    static int offsetX(TileObject object) { return object instanceof DecorativeObject ? ((DecorativeObject) object).getXOffset() : 0; }

    static int offsetY(TileObject object) { return object instanceof DecorativeObject ? ((DecorativeObject) object).getYOffset() : 0; }

    /** The object's (first) model. */
    static Renderable renderable(TileObject object)
    {
        if (object instanceof GameObject) { return ((GameObject) object).getRenderable(); }
        if (object instanceof WallObject) { return ((WallObject) object).getRenderable1(); }
        if (object instanceof DecorativeObject) { return ((DecorativeObject) object).getRenderable(); }
        if (object instanceof GroundObject) { return ((GroundObject) object).getRenderable(); }
        return null;
    }
}

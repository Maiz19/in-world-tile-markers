package com.inworldtilemarkers;

import java.awt.Color;
import java.awt.Shape;
import java.util.function.Supplier;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * One model part whose hull or clickbox is marked: an NPC, or one renderable
 * of an object (walls and decorations have two). The model is read at render time.
 */
final class ModelTarget
{
    final String key;
    final NPC npc;
    final TileObject object;
    final Renderable renderable;
    final int offsetX, offsetY;
    final Color color, fill;
    final float borderWidth;
    /** Clickbox: RuneLite's clickbox shape instead of the convex hull. */
    final boolean clickbox;
    /** Outline: the model's silhouette, with the line outside it, instead of the convex hull. */
    final boolean outline;
    /** Depth layer: SceneShapeRenderer.HULL_LAYER, or above it for a plugin's highlight drawn over the others. */
    int layer = SceneShapeRenderer.HULL_LAYER;
    private final Supplier<Shape> fallback;

    private ModelTarget(String key, NPC npc, TileObject object, Renderable renderable, int offsetX, int offsetY,
        Color color, Color fill, double borderWidth, boolean clickbox, boolean outline, Supplier<Shape> fallback)
    {
        this.outline = outline;
        this.key = key; this.npc = npc; this.object = object; this.renderable = renderable;
        this.offsetX = offsetX; this.offsetY = offsetY; this.color = color; this.fill = fill;
        this.borderWidth = (float) Math.max(0, Math.min(16, borderWidth));
        this.clickbox = clickbox; this.fallback = fallback;
    }

    static ModelTarget npc(String key, NPC npc, Color color, Color fill, double borderWidth)
    { return new ModelTarget(key, npc, null, null, 0, 0, color, fill, borderWidth, false, false, npc::getConvexHull); }

    /** An NPC's clickbox; without a scene route RuneLite's own, as Better NPC Highlight draws it. */
    static ModelTarget npcClickbox(String key, NPC npc, Color color, Color fill, double borderWidth, Client client)
    { return new ModelTarget(key, npc, null, null, 0, 0, color, fill, borderWidth, true, false, () -> clickbox(client, npc)); }

    static Shape clickbox(Client client, NPC npc)
    {
        net.runelite.api.coords.LocalPoint lp = npc.getLocalLocation();
        return lp == null ? null : Perspective.getClickbox(client, npc.getWorldView(), npc.getModel(), npc.getCurrentOrientation(), lp.getX(), lp.getY(),
            Perspective.getTileHeight(client, lp, npc.getWorldLocation().getPlane()));
    }

    /** An NPC outline; without a scene route RuneLite's outline renderer draws it (no 2D shape). */
    static ModelTarget npcOutline(String key, NPC npc, Color color, double borderWidth)
    { return new ModelTarget(key, npc, null, null, 0, 0, color, Marker.NO_FILL, borderWidth, false, true, () -> null); }

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
            if (npc != null) { return npc.getModel(); }
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
        if (npc != null) { return npc.getLocalLocation(); }
        return new LocalPoint(object.getX() + offsetX, object.getY() + offsetY, object.getWorldView().getId());
    }

    int plane() { return npc != null ? npc.getWorldView().getPlane() : object.getPlane(); }

    /** Game objects keep a model orientation; wall, decoration and ground models are already rotated. */
    int orientation()
    {
        if (npc != null) { return npc.getCurrentOrientation(); }
        return object instanceof GameObject ? ((GameObject) object).getModelOrientation() : 0;
    }

    int height(Client client)
    {
        return npc != null ? Perspective.getTileHeight(client, npc.getLocalLocation(), plane()) : object.getZ();
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

    /** The convex hull of the object's (first) model, as RuneLite's getConvexHull. */
    static Shape hull(TileObject object)
    {
        if (object instanceof GameObject) { return ((GameObject) object).getConvexHull(); }
        if (object instanceof WallObject) { return ((WallObject) object).getConvexHull(); }
        if (object instanceof DecorativeObject) { return ((DecorativeObject) object).getConvexHull(); }
        return object instanceof GroundObject ? ((GroundObject) object).getConvexHull() : null;
    }

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

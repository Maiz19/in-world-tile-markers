package com.inworldtilemarkers;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.*;
import java.util.List;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * Finds which object or NPC a clickbox or convex hull drawn by another plugin's overlay belongs to, so In-World Tile Markers
 * can draw that object's clickbox or hull live in the scene, in the overlay's colours. The overlay drew the shape this
 * frame, with this camera; the candidate whose own clickbox or hull has exactly the same bounds is the one. The plugin
 * thereby still decides what is highlighted (its rules, timeouts, hover colours) without In-World Tile Markers knowing them.
 */
final class ShapeIdentifier
{
    /** Tiles around where the shape's centre meets the ground that are searched for objects. */
    private static final int OBJECT_RADIUS = 6;
    /** Candidates, nearest the shape's centre first, whose clickbox or hull is compared. */
    private static final int MAX_VERIFIED = 12;

    private final Client client;
    private final String prefix;
    /** The target identified per shape order last frame, and its bounds: overlays draw in a stable order. */
    private List<Found> guesses = new ArrayList<>();
    private List<Found> found = new ArrayList<>();
    private List<ModelTarget> identified = Collections.emptyList();

    /** A highlighted object or NPC, how it was drawn and its colours this frame. */
    static final class Found
    {
        final Object target;
        /** Convex hull; otherwise the clickbox. */
        final boolean hull;
        final Rectangle bounds;
        /** Where the target's anchor lay relative to the shape's centre, to recognise it next frame. */
        double anchorX, anchorY;
        Color border = Marker.NO_FILL, fill = Marker.NO_FILL;
        float width;

        Found(Object target, boolean hull, Rectangle bounds) { this.target = target; this.hull = hull; this.bounds = bounds; }
    }

    /** Only NPCs this accepts are identified. */
    private final java.util.function.Predicate<NPC> npcs;

    ShapeIdentifier(Client client, String prefix, java.util.function.Predicate<NPC> npcs) { this.client = client; this.prefix = prefix; this.npcs = npcs; }

    void beginFrame() { found = new ArrayList<>(); missedNow.clear(); }

    /**
     * Shapes that matched nothing, by size and place, with the frames they are skipped for: a shape no object or NPC
     * has (an arrow, a marker) cost a full search every frame.
     */
    private final Map<Rectangle, Integer> skipped = new HashMap<>(), missedNow = new HashMap<>();
    private static final int SKIP_FRAMES = 10;

    private boolean missedBefore(Rectangle bounds)
    {
        Integer left = skipped.get(bounds);
        return left != null && left > 0;
    }

    private void misses(Rectangle bounds)
    {
        Integer left = skipped.get(bounds);
        missedNow.put(bounds, left == null || left <= 0 ? SKIP_FRAMES : left - 1);
    }

    /** Ends a frame: the targets found become marks, and the guesses for the next frame. */
    void endFrame()
    {
        Map<Object, Found> unique = new IdentityHashMap<>();
        List<ModelTarget> out = new ArrayList<>();
        for (Found f : found)
        {
            if (unique.put(f.target, f) != null) { continue; }
            out.add(target(f));
        }
        identified = out;
        guesses = found;
        skipped.clear();
        skipped.putAll(missedNow);
    }

    void clear()
    {
        guesses = new ArrayList<>();
        found = new ArrayList<>();
        identified = Collections.emptyList();
    }

    /** The objects and NPCs identified in the last frame, as marks drawn live. */
    void collect(List<ModelTarget> out) { out.addAll(identified); }

    /** The objects identified in the last frame. */
    void objects(Set<TileObject> out) { for (ModelTarget t : identified) { if (t.object != null) { out.add(t.object); } } }

    /**
     * The target of a shape drawn with this colour: the border when stroke is set (width), else the fill. Returns
     * false when no object or NPC has exactly this clickbox or hull.
     */
    boolean take(Shape shape, Color color, float width, boolean border, WorldView wv, ModelShapes.Camera camera)
    {
        if (color == null) { return false; }
        Rectangle bounds = shape.getBounds();
        if (bounds.isEmpty()) { return false; }
        // renderHoverableArea draws the border, then fills the same shape; other overlays fill first. Either way the
        // second call belongs to the last one found, if that has no border or fill yet.
        Found last = found.isEmpty() ? null : found.get(found.size() - 1);
        Found f = last != null && last.bounds.equals(bounds) && (border ? last.border.getAlpha() == 0 : last.fill.getAlpha() == 0) ? last : null;
        if (f == null)
        {
            int order = found.size();
            Found guess = order < guesses.size() ? guesses.get(order) : null;
            if (guess == null && missedBefore(bounds)) { misses(bounds); return false; }
            f = guess != null && (same(guess, bounds, wv) || exact(guess, bounds)) && !(guess.target instanceof NPC && !npcs.test((NPC) guess.target))
                ? new Found(guess.target, guess.hull, bounds) : identify(bounds, wv, camera);
            if (f == null) { misses(bounds); return false; }
            Point p = anchor(f.target, wv);
            if (p != null) { f.anchorX = p.getX() - bounds.getCenterX(); f.anchorY = p.getY() - bounds.getCenterY(); }
            found.add(f);
        }
        if (border) { f.border = color; f.width = width; }
        else { f.fill = color; }
        return true;
    }

    /**
     * The guess still holds: the shape is about as large as last frame's and the target's anchor lies where it lay in
     * it (within 3 pixels), so a like object next to it does not pass. Checked without computing its clickbox again,
     * which is costly; a mismatch identifies the shape afresh.
     */
    private boolean same(Found guess, Rectangle bounds, WorldView wv)
    {
        Rectangle last = guess.bounds;
        // A walking NPC's hull changes shape every animation frame: about as large, with its anchor about where it
        // was in the shape. Checking the anchor's place, not only nearness, keeps a shape from passing to the next
        // NPC when one of several highlighted ones dies and the rest move up one in the drawing order. Objects keep
        // their size and their anchor within 3 pixels.
        boolean npc = guess.target instanceof NPC;
        double grow = npc ? 0.3 : 0.15, min = npc ? 6 : 4;
        if (Math.abs(bounds.width - last.width) > Math.max(min, last.width * grow)
            || Math.abs(bounds.height - last.height) > Math.max(min, last.height * grow)) { return false; }
        Point p = anchor(guess.target, wv);
        float slack = npc ? Math.max(6, 0.2f * Math.max(bounds.width, bounds.height)) : 3;
        return p != null && Math.abs(p.getX() - bounds.getCenterX() - guess.anchorX) <= slack
            && Math.abs(p.getY() - bounds.getCenterY() - guess.anchorY) <= slack;
    }

    /**
     * The guess's own clickbox or hull has these bounds now: the same target, also when the camera moved so fast that its
     * anchor moved within the shape (a tall rooftop obstacle as the camera turns). One shape computed instead of a search,
     * which computes up to MAX_VERIFIED of them twice and, failing, leaves the shape to the plugin's own 2D drawing.
     */
    private boolean exact(Found guess, Rectangle bounds)
    {
        Shape s = shapeOf(guess.target, guess.hull);
        return s != null && matches(bounds, s.getBounds(), guess.target instanceof NPC ? NPC_TOLERANCE : 0);
    }

    /** Whether a canvas point lies within 32 pixels of the bounds. */
    private static boolean near(Point p, Rectangle bounds)
    {
        double dx = Math.max(bounds.getMinX() - p.getX(), Math.max(0, p.getX() - bounds.getMaxX()));
        double dy = Math.max(bounds.getMinY() - p.getY(), Math.max(0, p.getY() - bounds.getMaxY()));
        return dx <= 32 && dy <= 32;
    }

    private Found identify(Rectangle bounds, WorldView wv, ModelShapes.Camera camera)
    {
        double cx = bounds.getCenterX(), cy = bounds.getCenterY();
        // NPCs first: cheap to list, and most highlighted shapes that move are theirs.
        List<Object> npcs = new ArrayList<>();
        for (NPC npc : wv.npcs()) { if (npc != null && this.npcs.test(npc)) { npcs.add(npc); } }
        Found npc = identifyAmong(bounds, npcs, wv);
        if (npc != null) { return npc; }
        // Objects around where the shape meets the ground, on every level: a highlighted object need not be on the
        // player's (a rooftop course's first wall stands on the ground while the player is on a roof, and back).
        List<Object> candidates = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int level = 0; level < wv.getScene().getTiles().length; level++)
        {
            float[] ground = WalkPredictor.ground(camera, (float) cx, (float) (bounds.getMaxY() - 1), wv, level);
            if (ground == null) { ground = WalkPredictor.ground(camera, (float) cx, (float) cy, wv, level); }
            if (ground != null) { objectsAround(wv, level, (int) ground[0] >> 7, (int) ground[1] >> 7, seen, candidates); }
        }
        return identifyAmong(bounds, candidates, wv);
    }

    /** Of these candidates, the one whose clickbox or hull has exactly these bounds, nearest the shape's centre first. */
    Found identifyAmong(Rectangle bounds, List<Object> candidates, WorldView wv)
    {
        double cx = bounds.getCenterX(), cy = bounds.getCenterY();
        // Only those whose anchor lies near the shape are compared.
        List<Object[]> ranked = new ArrayList<>();
        for (Object c : candidates)
        {
            Point p = anchor(c, wv);
            if (p == null || !near(p, bounds)) { continue; }
            // Anchors inside the shape first (an object's base lies at its clickbox's bottom, not its centre), then
            // nearest the centre.
            double d = Math.hypot(p.getX() - cx, p.getY() - cy) + (bounds.contains(p.getX(), p.getY()) ? 0 : 10_000);
            ranked.add(new Object[]{c, d});
        }
        ranked.sort(Comparator.comparingDouble(r -> (double) r[1]));
        for (int i = 0; i < Math.min(MAX_VERIFIED, ranked.size()); i++)
        {
            Object c = ranked.get(i)[0];
            for (boolean hull : c instanceof NPC ? new boolean[]{true, false} : new boolean[]{false, true})
            {
                Shape s = shapeOf(c, hull);
                if (s != null && matches(bounds, s.getBounds(), c instanceof NPC ? NPC_TOLERANCE : 0)) { return new Found(c, hull, bounds); }
            }
        }
        return null;
    }

    /**
     * An NPC's hull may differ by a pixel or two between two calls in one frame while it walks and animates (between
     * tiles, with animation smoothing); objects must match exactly.
     */
    private static final int NPC_TOLERANCE = 2;

    private static boolean matches(Rectangle drawn, Rectangle own, int tolerance)
    {
        return Math.abs(drawn.x - own.x) <= tolerance && Math.abs(drawn.y - own.y) <= tolerance
            && Math.abs(drawn.x + drawn.width - own.x - own.width) <= tolerance
            && Math.abs(drawn.y + drawn.height - own.y - own.height) <= tolerance;
    }

    private static void objectsAround(WorldView wv, int level, int tx, int ty, Set<Object> seen, List<Object> out)
    {
        Tile[][] tiles = wv.getScene().getTiles()[level];
        for (int x = Math.max(0, tx - OBJECT_RADIUS); x <= Math.min(tiles.length - 1, tx + OBJECT_RADIUS); x++)
        {
            for (int y = Math.max(0, ty - OBJECT_RADIUS); y <= Math.min(tiles[x].length - 1, ty + OBJECT_RADIUS); y++)
            {
                Tile tile = tiles[x][y];
                if (tile == null) { continue; }
                if (tile.getWallObject() != null && seen.add(tile.getWallObject())) { out.add(tile.getWallObject()); }
                if (tile.getDecorativeObject() != null && seen.add(tile.getDecorativeObject())) { out.add(tile.getDecorativeObject()); }
                if (tile.getGroundObject() != null && seen.add(tile.getGroundObject())) { out.add(tile.getGroundObject()); }
                // A game object larger than a tile stands on several: once only.
                for (GameObject o : tile.getGameObjects()) { if (o != null && seen.add(o)) { out.add(o); } }
            }
        }
    }

    /** Where a candidate stands on the canvas: an NPC at half its height, an object at its base. */
    private Point anchor(Object c, WorldView wv)
    {
        if (c instanceof NPC)
        {
            NPC npc = (NPC) c;
            LocalPoint lp = npc.getLocalLocation();
            return lp == null ? null : Perspective.localToCanvas(client, lp, wv.getPlane(), npc.getLogicalHeight() / 2);
        }
        return ((TileObject) c).getCanvasLocation();
    }

    /** A hull, or the clickbox (for an NPC as Better NPC Highlight draws it, from its model). */
    private Shape shapeOf(Object c, boolean hull)
    {
        if (c instanceof NPC) { return hull ? ((NPC) c).getConvexHull() : ModelTarget.clickbox(client, (NPC) c); }
        return hull ? ModelTarget.hull((TileObject) c) : ((TileObject) c).getClickbox();
    }

    private ModelTarget target(Found f)
    {
        Color border = f.border, fill = f.fill;
        float width = f.border.getAlpha() > 0 ? Math.max(1, f.width) : 0;
        if (f.target instanceof NPC)
        {
            NPC npc = (NPC) f.target;
            return f.hull ? ModelTarget.npc(prefix + "npc:" + npc.getIndex() + ":hull", npc, border, fill, width)
                : ModelTarget.npcClickbox(prefix + "npc:" + npc.getIndex() + ":clickbox", npc, border, fill, width, client);
        }
        TileObject object = (TileObject) f.target;
        Renderable renderable = ModelTarget.renderable(object);
        int dx = ModelTarget.offsetX(object), dy = ModelTarget.offsetY(object);
        String key = prefix + "object:" + object.getHash() + (f.hull ? ":hull" : ":clickbox");
        if (f.hull) { return ModelTarget.object(key, object, renderable, dx, dy, border, fill, width, false, () -> ModelTarget.hull(object)); }
        // The float clickbox, which does not wobble as the camera moves (walls and decorations with two models: RuneLite's).
        return ModelTarget.object(key, object, renderable, dx, dy, border, fill, width, true, object::getClickbox);
    }
}

package com.inworldtilemarkers;

import java.awt.*;
import java.util.*;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;

/**
 * The tile shapes of another plugin's paused overlays, drawn by In-World Tile Markers. IndicatorOverlay runs the
 * overlays once per frame on {@link CapturingGraphics} ({@link #render}): every polygon they fill or outline
 * that is exactly a tile (or square area up to 5x5) polygon of the scene becomes a mark for the scene from the next frame;
 * text, icons, timers, clickboxes and any polygon that matches no tile are drawn in 2D as the overlay drew them.
 * The overlay's own rules (its settings, colours, distances) are thereby kept exactly.
 */
final class TileCapture implements SourcePlugin.Source
{
    /** Consecutive failures of its overlays after which they draw themselves again, until the next rebuild. */
    private static final int MAX_FAILURES = 3;
    /**
     * Area sizes tried (getCanvasTileAreaPoly): single tiles up to 15x15, such as NPC footprints and Sailing's wreck
     * areas. Odd sizes are centred on a tile, even sizes on a tile corner.
     */
    private static final int MAX_SIZE = 15;

    private final Client client;
    private final String prefix;
    /**
     * Lines (Line2D) between two points on the ground become scene lines: each end is traced back to where it meets
     * the ground. Only for overlays that draw lines on the ground (Quest Helper's world lines); elsewhere a line can
     * be a screen-space arrow.
     */
    private final boolean captureLines;
    /** Depth layer of its marks: above the other marks for a plugin whose highlights are drawn over theirs. */
    int markLayer = Marker.EXTERNAL, modelLayer = SceneShapeRenderer.HULL_LAYER;
    /** The plugin whose highlights win: what it highlighted last frame is left out here (one highlight per obstacle). */
    SourcePlugin yieldTo;
    /** 4-point polygons that match no tile lie on the ground (Sailing's boat bounds): each corner traced to the ground. */
    boolean quads;
    /**
     * How far the plugin draws its marks itself, in local units (square: in tiles both ways, as WorldPoint.distanceTo),
     * 0 for no limit. With "Extend plugin ranges" its marks that went out of that range are kept, up to extendTo.
     */
    int range;
    boolean squareRange;
    /** Local units up to which marks beyond a plugin's own range are kept; 0 when "Extend plugin ranges" is off. */
    static int extendTo;
    /** "Timers scale with the world": round shapes are sized as part of the world (see worldPerPixel). */
    static boolean scaleRound = true;
    private final Map<String, Marker> seen = new HashMap<>();
    private final Map<String, ModelTarget> seenModels = new HashMap<>();
    /** Clickboxes and hulls whose object or NPC is found (ShapeIdentifier) are drawn live in the scene; null: not tried. */
    private final ShapeIdentifier identifier;
    private List<Marker> captured = Collections.emptyList();
    private int failures;
    /** The tile matched per shape order last frame: overlays draw in a stable order, so it is tried first. */
    private List<LocalPoint> guesses = new ArrayList<>();
    /** The NPC matched per NPC tile order last frame, tried first the same way. */
    private List<Actor> npcGuesses = new ArrayList<>();
    /**
     * Four-point polygons that were no tile (such as a box drawn around text), by their exact points, and the frame
     * until which they are not looked for again: each costs up to 90 tile polygons and a pass over the NPCs.
     */
    private final Map<Long, Integer> notTiles = new HashMap<>();
    private int frame;
    private static final int SKIP_FRAMES = 10;
    /** The places in the world round shapes hung at last frame, and this frame (see anchor). */
    private List<Anchor> anchors = new ArrayList<>(), nextAnchors = new ArrayList<>();
    /**
     * How near its centre (canvas pixels) a place projects for a new round shape, its plugin rounding the centre to a
     * pixel; and for one kept from last frame, whose place was taken from a centre rounded as well.
     */
    private static final float ANCHOR_TOLERANCE = 1.5f, KEEP_TOLERANCE = 2.5f;

    /** The places {x, y, height above the ground} a round shape may hang at, and the one it takes. */
    private static final class Anchor
    {
        final List<float[]> places;
        final float[] taken;
        Anchor(List<float[]> places, float[] taken) { this.places = places; this.taken = taken; }
    }

    /** A tile's border and fill as captured this frame: OverlayUtil.renderPolygon draws and fills the same polygon. */
    private static final class Shape
    {
        final LocalPoint tile;
        final int size;
        /** The walking NPC whose tile this is (NPC.getCanvasTilePoly), followed every frame; null for a scene tile. */
        final Actor npc;
        Color border = Marker.NO_FILL, fill = Marker.NO_FILL;
        float width;
        /** Corners only (1/corners of each side), the south-west tile of a large NPC (its shift), a ground quad. */
        int corners, shift;
        int[] qx, qy;

        Shape(LocalPoint tile, int size, Actor npc) { this.tile = tile; this.size = size; this.npc = npc; }
    }

    TileCapture(Client client, String prefix, boolean captureLines, boolean identify, Index index)
    {
        this.client = client; this.prefix = prefix; this.captureLines = captureLines; this.index = index;
        identifier = identify ? new ShapeIdentifier(client, prefix, npc -> npcs == null || npcs.test(npc)) : null;
    }

    /** Only NPCs this accepts are drawn here (all when null): the rest of the plugin's NPC marks stay its own drawing. */
    java.util.function.Predicate<NPC> npcs;

    /** When the plugin draws something that is no mark (Shortest Path's debug overlays), its overlays stay its own. */
    java.util.function.BooleanSupplier usableWhen = () -> true;

    /** Whether In-World Tile Markers can draw its tiles: not after its overlays failed repeatedly when run here. */
    public boolean usable() { return failures < MAX_FAILURES && usableWhen.getAsBoolean(); }

    /** Client thread only (rebuild, shutdown). */
    void clear()
    {
        captured = Collections.emptyList();
        guesses = new ArrayList<>();
        npcGuesses = new ArrayList<>();
        notTiles.clear();
        index.clear();
        seen.clear();
        seenModels.clear();
        anchors = new ArrayList<>();
        lineEnds = new ArrayList<>();
        failures = 0;
        if (identifier != null) { identifier.clear(); }
    }

    /** The objects this plugin highlighted last frame (identified clickboxes and hulls). */
    void objects(Set<net.runelite.api.TileObject> out) { if (identifier != null) { identifier.objects(out); } }

    /** The scene tiles (local x << 32 | y) this plugin highlighted last frame. */
    void tiles(Set<Long> out) { for (Marker m : captured) { if (m.follow == null && m.lineX == null) { out.add((long) m.point.getX() << 32 | m.point.getY()); } } }

    /** The objects and NPCs identified in the last frame, drawn live with the colours their overlay used. */
    public void collect(List<Marker> tiles, List<ModelTarget> models)
    {
        Set<net.runelite.api.TileObject> objects = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> claimed = new HashSet<>();
        if (yieldTo != null && yieldTo.drawing()) { yieldTo.capture().objects(objects); yieldTo.capture().tiles(claimed); }
        for (Marker m : captured) { if (!claimed.contains((long) m.point.getX() << 32 | m.point.getY())) { tiles.add(m); } }
        if (identifier == null) { return; }
        int first = models.size();
        identifier.collect(models);
        models.subList(first, models.size()).removeIf(t -> objects.contains(t.object));
        if (range > 0 && extendTo > 0) { keepFar(models.subList(first, models.size())); }
        for (int i = first; i < models.size(); i++) { models.get(i).layer = modelLayer; }
    }

    /**
     * Extend plugin ranges: objects this plugin highlighted that it no longer draws only because they lie beyond its own
     * range stay, while they are still in the scene and within extendTo. One it stopped drawing nearer is dropped.
     */
    private void keepFar(List<ModelTarget> models)
    {
        Set<String> now = new HashSet<>();
        for (ModelTarget t : models) { now.add(t.key); if (t.object != null) { seenModels.put(t.key, t); } }
        seenModels.values().removeIf(t -> !now.contains(t.key) && !(far(t.object.getLocalLocation()) && present(t.object)));
        for (ModelTarget t : seenModels.values()) { if (!now.contains(t.key)) { models.add(t); } }
    }

    private boolean far(LocalPoint p)
    {
        Player me = client.getLocalPlayer();
        LocalPoint at = me == null ? null : me.getLocalLocation();
        if (at == null || p == null) { return false; }
        int dx = Math.abs(p.getX() - at.getX()), dy = Math.abs(p.getY() - at.getY());
        double d = squareRange ? Math.max(dx, dy) : Math.hypot(dx, dy);
        return d >= range - 128 && d <= extendTo;
    }

    private static boolean present(net.runelite.api.TileObject o)
    {
        WorldView wv = o.getWorldView();
        LocalPoint lp = o.getLocalLocation();
        net.runelite.api.Tile[][][] tiles = wv == null || wv.getScene() == null ? null : wv.getScene().getTiles();
        int x = lp.getSceneX(), y = lp.getSceneY();
        if (tiles == null || o.getPlane() >= tiles.length || x < 0 || y < 0 || x >= tiles[0].length || y >= tiles[0][0].length) { return false; }
        net.runelite.api.Tile t = tiles[o.getPlane()][x][y];
        return t != null && (t.getWallObject() == o || t.getDecorativeObject() == o || t.getGroundObject() == o
            || Arrays.asList(t.getGameObjects()).contains(o));
    }

    /** Runs the paused overlays once on g: tile polygons become marks for the scene, everything else is drawn on g. */
    void render(List<Overlay> paused, Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null || paused.isEmpty()) { captured = Collections.emptyList(); anchors = new ArrayList<>(); lineEnds = new ArrayList<>(); if (identifier != null) { identifier.clear(); } return; }
        frame++;
        nextAnchors = new ArrayList<>();
        nextLineEnds = new ArrayList<>();
        if (!notTiles.isEmpty()) { notTiles.values().removeIf(until -> until < frame); }
        int plane = wv.getPlane();
        index.prepare(client, wv, plane);
        last = null;
        if (identifier != null) { identifier.beginFrame(); }
        Map<Long, Shape> shapes = new LinkedHashMap<>();
        List<Marker> lines = new ArrayList<>();
        List<LocalPoint> matched = new ArrayList<>();
        List<Actor> matchedNpcs = new ArrayList<>();
        ModelShapes.Camera camera = ModelShapes.Camera.of(client);
        CapturingGraphics.Sink sink = new CapturingGraphics.Sink()
        {
            @Override public boolean fill(java.awt.Shape shape, Color color)
            {
                if (round(shape)) { return screen(shape, color, true, 0, wv, plane, lines, camera); }
                Shape s = shape(shape, color);
                if (s == null) { s = quad(shape, color); }
                if (s == null) { return identified(shape, color, 0, false); }
                s.fill = color;
                return true;
            }

            @Override public boolean corners(Polygon p, Color color, float width, int divisor)
            {
                Shape s = shape(p, color);
                if (s == null) { return false; }
                s.border = color;
                s.width = width;
                s.corners = divisor;
                return true;
            }

            /** With quads, a 4-point polygon that is no tile, its corners traced to the ground. */
            private Shape quad(java.awt.Shape shape, Color color)
            {
                if (!quads || !(shape instanceof Polygon) || ((Polygon) shape).npoints != 4 || color == null) { return null; }
                Polygon p = (Polygon) shape;
                int[] qx = new int[4], qy = new int[4];
                for (int i = 0; i < 4; i++)
                {
                    float[] g = WalkPredictor.ground(camera, p.xpoints[i], p.ypoints[i], wv, plane);
                    if (g == null) { return null; }
                    qx[i] = Math.round(g[0]);
                    qy[i] = Math.round(g[1]);
                }
                long key = Long.MIN_VALUE / 2 + (Arrays.hashCode(qx) * 31L + Arrays.hashCode(qy));
                Shape s = shapes.computeIfAbsent(key, k -> new Shape(new LocalPoint((qx[0] + qx[2]) / 2, (qy[0] + qy[2]) / 2, wv), 1, null));
                s.qx = qx;
                s.qy = qy;
                return s;
            }

            @Override public boolean draw(java.awt.Shape shape, Color color, Stroke stroke)
            {
                if (round(shape) && stroke instanceof BasicStroke) { return screen(shape, color, false, ((BasicStroke) stroke).getLineWidth(), wv, plane, lines, camera); }
                // A line, also as a polygon of two points (Port Tasks).
                java.awt.geom.Line2D l = shape instanceof java.awt.geom.Line2D ? (java.awt.geom.Line2D) shape
                    : shape instanceof Polygon && ((Polygon) shape).npoints == 2 ? new java.awt.geom.Line2D.Float(((Polygon) shape).xpoints[0],
                    ((Polygon) shape).ypoints[0], ((Polygon) shape).xpoints[1], ((Polygon) shape).ypoints[1]) : null;
                if (l != null && stroke instanceof BasicStroke && color != null)
                {
                    boolean taken = line(l, color, ((BasicStroke) stroke).getLineWidth(), wv, plane, lines, camera);
                    return taken;
                }
                Shape s = stroke instanceof BasicStroke ? shape(shape, color) : null;
                if (s == null && stroke instanceof BasicStroke) { s = quad(shape, color); }
                if (s == null)
                {
                    float width = stroke instanceof BasicStroke ? ((BasicStroke) stroke).getLineWidth() : 1;
                    return identified(shape, color, width, true);
                }
                s.border = color;
                s.width = ((BasicStroke) stroke).getLineWidth();
                return true;
            }

            private boolean identified(java.awt.Shape shape, Color color, float width, boolean border)
            {
                return identifier != null && !(shape instanceof java.awt.geom.Line2D) && identifier.take(shape, color, width, border, wv, camera);
            }

            private Shape shape(java.awt.Shape shape, Color color)
            {
                if (!(shape instanceof Polygon) || ((Polygon) shape).npoints != 4 || color == null) { return null; }
                Polygon p = (Polygon) shape;
                long key = 17;
                for (int i = 0; i < 4; i++) { key = key * 31 + p.xpoints[i]; key = key * 31 + p.ypoints[i]; }
                Integer until = notTiles.get(key);
                if (until != null && until >= frame) { return null; }
                int order = matched.size();
                LocalPoint guess = order < guesses.size() ? guesses.get(order) : null;
                long[] found = match(p, guess, wv, camera);
                if (found == null)
                {
                    // A walking NPC's or player's tile lies between tiles (getCanvasTilePoly at its current location).
                    int npcOrder = matchedNpcs.size();
                    Actor actor = matchActor(p, npcOrder < npcGuesses.size() ? npcGuesses.get(npcOrder) : null, wv);
                    if (actor == null) { notTiles.put(key, frame + SKIP_FRAMES); return null; }
                    if (npcs != null && !(actor instanceof NPC && npcs.test((NPC) actor))) { return null; }
                    matchedNpcs.add(actor);
                    int size = size(actor), shift = southWest ? (size - 1) * 64 : 0;
                    long id = (actor instanceof NPC ? -1L - ((NPC) actor).getIndex() : -100_000L - ((Player) actor).getId()) - (shift > 0 ? 1_000_000L : 0);
                    Shape s = shapes.computeIfAbsent(id, k -> new Shape(actor.getLocalLocation(), shift > 0 ? 1 : size, actor));
                    s.shift = shift;
                    return s;
                }
                LocalPoint tile = new LocalPoint((int) found[0], (int) found[1], wv);
                int size = (int) found[2];
                if (npcs != null && !accepted(tile, size, wv)) { return null; }
                matched.add(tile);
                last = tile;
                return shapes.computeIfAbsent(found[0] << 24 | found[1] << 4 | size, k -> new Shape(tile, size, null));
            }
        };
        boolean ok = true;
        for (Overlay overlay : paused)
        {
            Graphics2D capture = new CapturingGraphics((Graphics2D) g.create(), sink);
            try { overlay.render(capture); }
            catch (RuntimeException ex) { ok = false; }
            finally { capture.dispose(); }
        }
        // A one-off failure is skipped; repeated ones hand the drawing back.
        failures = ok ? 0 : failures + 1;
        List<Marker> out = new ArrayList<>(shapes.size());
        for (Shape s : shapes.values())
        {
            String key = s.npc instanceof NPC ? prefix + "npc:" + ((NPC) s.npc).getIndex() + (s.shift > 0 ? ":sw" : ":tile")
                : s.npc instanceof Player ? prefix + "player:" + ((Player) s.npc).getId() + ":tile"
                : s.qx != null ? prefix + "quad:" + s.tile.getX() + ":" + s.tile.getY()
                : prefix + s.tile.getX() + ":" + s.tile.getY() + ":" + s.size;
            Marker m = new Marker(key, s.tile, plane, s.size, s.size, s.border, s.fill, s.border.getAlpha() > 0 ? s.width : 0, null, false);
            m.follow = s.npc;
            m.followShift = s.shift;
            m.cornerDivisor = s.corners;
            m.quadX = s.qx;
            m.quadY = s.qy;
            m.layer = markLayer;
            out.add(m);
        }
        out.addAll(lines);
        if (range > 0 && extendTo > 0)
        {
            Set<String> now = new HashSet<>();
            for (Marker m : out) { now.add(m.key); if (m.follow == null) { seen.put(m.key, m); } }
            seen.values().removeIf(m -> !now.contains(m.key) && !far(m.point));
            for (Marker m : seen.values()) { if (!now.contains(m.key)) { out.add(m); } }
        }
        if (identifier != null) { identifier.endFrame(); }
        captured = out;
        anchors = nextAnchors;
        lineEnds = nextLineEnds;
        guesses = matched;
        npcGuesses = matchedNpcs;
    }

    private static boolean round(java.awt.Shape s) { return s instanceof java.awt.geom.Arc2D || s instanceof java.awt.geom.Ellipse2D; }

    /**
     * A round shape on the screen (a timer pie, its rim, a dot) as a scene mark kept around the place its centre hangs at
     * (see anchor), sized as part of the world (see worldPerPixel); false (not taken) when the centre meets no ground.
     */
    private boolean screen(java.awt.Shape shape, Color color, boolean fill, float width, WorldView wv, int plane, List<Marker> out, ModelShapes.Camera camera)
    {
        if (color == null) { return false; }
        // The circle's centre: a pie's own bounds shrink with it as its timer runs down.
        java.awt.geom.RectangularShape r = (java.awt.geom.RectangularShape) shape;
        java.awt.geom.Rectangle2D b = r.getFrame();
        float cx = (float) r.getCenterX(), cy = (float) r.getCenterY();
        float[] g = anchor(camera, cx, cy, wv, plane);
        if (g == null) { return false; }
        // A full pie as a circle: its centre point would be a slit in it.
        if (shape instanceof java.awt.geom.Arc2D && Math.abs(((java.awt.geom.Arc2D) shape).getAngleExtent()) >= 359.9)
        {
            shape = new java.awt.geom.Ellipse2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
        }
        List<float[]> outline = SceneShapeRenderer.polygons(shape);
        // An empty pie (no time left) is drawn as nothing, as its plugin does.
        if (outline.isEmpty()) { return true; }
        float[] points = outline.get(0);
        Marker m = new Marker(prefix + "screen:" + out.size(), new LocalPoint(Math.round(g[0]), Math.round(g[1]), wv), plane, 1, 1,
            fill ? Marker.NO_FILL : color, fill ? color : Marker.NO_FILL, fill ? 0 : width, null, false);
        m.lift = Math.round(g[2]);
        m.offX = new float[points.length / 2];
        m.offY = new float[points.length / 2];
        float units = scaleRound ? worldPerPixel(wv, plane, camera) : 0;
        m.worldSized = units > 0;
        for (int i = 0; i < m.offX.length; i++)
        {
            m.offX[i] = (points[i * 2] - cx) * (units > 0 ? units : 1);
            m.offY[i] = (points[i * 2 + 1] - cy) * (units > 0 ? units : 1);
        }
        m.layer = SceneShapeRenderer.HULL_LAYER;
        out.add(m);
        return true;
    }

    /** World units per canvas pixel at your distance when this plugin's first round shape was seen; 0 until then. */
    private float worldPerPixel;

    /**
     * Round shapes as part of the world: plugins draw timer pies the same size on the screen at every zoom, so zoomed out
     * they covered their rock and more. A shape keeps the size its plugin drew the first one at, at your distance, and from
     * then on scales with the world each frame, as the rock does (see Marker.worldSized). 0 while it cannot be told.
     */
    private float worldPerPixel(WorldView wv, int plane, ModelShapes.Camera camera)
    {
        Player me = client.getLocalPlayer();
        LocalPoint at = me == null ? null : me.getLocalLocation();
        if (worldPerPixel == 0 && at != null)
        {
            float[] p = new float[3];
            camera.project(at.getX(), at.getY(), Terrain.height(wv, at.getX(), at.getY(), plane), p);
            if (p[2] > 0) { worldPerPixel = p[2] / camera.scale; }
        }
        return worldPerPixel;
    }

    /**
     * Where a shape drawn around a canvas point hangs in the world {x, y, height above the ground}. Plugins draw timers
     * over an object or a tile centre (a rock, an ore vein) at some height, and the ray through the point passes over
     * every such place lined up with it, each at a depth of its own. A shape keeps the places of last frame's shape that
     * still project onto it, and the one it took while that does: as the camera moves, only the right one is left. A
     * new shape takes the object nearest its centre, else a tile centre, else the ground point; at most 512 up.
     */
    private float[] anchor(ModelShapes.Camera camera, float cx, float cy, WorldView wv, int plane)
    {
        for (Anchor a : anchors)
        {
            List<float[]> kept = new ArrayList<>();
            for (float[] p : a.places) { if (error(p, camera, cx, cy, wv, plane) <= KEEP_TOLERANCE) { kept.add(p); } }
            if (kept.isEmpty()) { continue; }
            Anchor next = new Anchor(kept, kept.contains(a.taken) ? a.taken : kept.get(0));
            nextAnchors.add(next);
            return next.taken;
        }
        float[] ground = WalkPredictor.ground(camera, cx, cy, wv, plane), near = new float[3];
        if (ground == null) { return null; }
        camera.unproject(cx, cy, ModelShapes.NEAR, near);
        float[] ray = {near[0], near[1], near[2], ground[0] - near[0], ground[1] - near[1], ground[2] - near[2]};
        float length = ray[3] * ray[3] + ray[4] * ray[4];
        List<float[]> objects = new ArrayList<>(), centres = new ArrayList<>();
        float[] best = null;
        net.runelite.api.Tile[][] tiles = wv.getScene().getTiles()[plane];
        Set<Object> seenObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> seenTiles = new HashSet<>();
        int steps = (int) (Math.sqrt(length) / 64) + 1;
        for (int i = 0; i <= steps && length > 0; i++)
        {
            int tx = (int) (near[0] + ray[3] * i / steps) >> 7, ty = (int) (near[1] + ray[4] * i / steps) >> 7;
            for (int x = tx - 1; x <= tx + 1; x++)
            {
                for (int y = ty - 1; y <= ty + 1; y++)
                {
                    net.runelite.api.Tile t = x < 0 || y < 0 || x >= tiles.length || y >= tiles[x].length ? null : tiles[x][y];
                    if (t == null || !seenTiles.add((long) x << 32 | y)) { continue; }
                    float[] centre = place(x * 128 + 64, y * 128 + 64, ray, length, camera, cx, cy, wv, plane);
                    if (centre != null) { centres.add(centre); }
                    List<net.runelite.api.TileObject> on = new ArrayList<>(Arrays.asList(t.getGameObjects()));
                    on.add(t.getWallObject());
                    for (net.runelite.api.TileObject o : on)
                    {
                        if (o == null || !seenObjects.add(o)) { continue; }
                        float[] p = place(o.getLocalLocation().getX(), o.getLocalLocation().getY(), ray, length, camera, cx, cy, wv, plane);
                        if (p == null) { continue; }
                        objects.add(p);
                        if (best == null || error(p, camera, cx, cy, wv, plane) < error(best, camera, cx, cy, wv, plane)) { best = p; }
                    }
                }
            }
        }
        List<float[]> places = new ArrayList<>(objects);
        places.addAll(centres);
        places.add(new float[]{ground[0], ground[1], 0});
        Anchor next = new Anchor(places, best != null ? best : places.get(0));
        nextAnchors.add(next);
        return next.taken;
    }

    /**
     * The place {x, y, height above the ground} on the ray {start, direction} right over local (x, y), when that lies
     * between the start and the ground, at most 512 up, and projects onto the centre; else null.
     */
    private static float[] place(float x, float y, float[] ray, float length, ModelShapes.Camera camera, float cx, float cy, WorldView wv, int plane)
    {
        float s = ((x - ray[0]) * ray[3] + (y - ray[1]) * ray[4]) / length;
        float[] p = {x, y, Terrain.height(wv, (int) x, (int) y, plane) - (ray[2] + ray[5] * s)};
        return s >= 0 && s <= 1 && p[2] >= 0 && p[2] <= 512 && error(p, camera, cx, cy, wv, plane) <= ANCHOR_TOLERANCE ? p : null;
    }

    /** How far (canvas pixels) the place {x, y, height above the ground} projects from canvas point (cx, cy). */
    private static double error(float[] p, ModelShapes.Camera camera, float cx, float cy, WorldView wv, int plane)
    {
        float[] q = new float[3];
        camera.project(p[0], p[1], Terrain.height(wv, (int) p[0], (int) p[1], plane) - p[2], q);
        return q[2] > 0 ? Math.hypot(q[0] - cx, q[1] - cy) : Double.MAX_VALUE;
    }

    /** Pieces of a scene line are at most this long (local units), so each fits one scene object and follows the terrain. */
    private static final int LINE_PIECE = 6 * 128;

    /** Each line's ends {ax, ay, bx, by} last frame and this one, in the order the lines are drawn. */
    private List<float[]> lineEnds = new ArrayList<>(), nextLineEnds = new ArrayList<>();
    /** Quest Helper ends a line that leaves the loaded area on its edge: local 0 or this (WorldLines.drawLinesOnWorld). */
    private static final int EDGE = 13056;

    /**
     * A scene line between where both ends of a canvas line lie, added to out in pieces; false (not taken) when an end is
     * not found.
     */
    private boolean line(java.awt.geom.Line2D l, Color color, float width, WorldView wv, int plane, List<Marker> out, ModelShapes.Camera camera)
    {
        int order = nextLineEnds.size();
        float[] before = order < lineEnds.size() ? lineEnds.get(order) : null;
        float[] a = end(l.getX1(), l.getY1(), before, 0, last == null ? null : new float[]{last.getX(), last.getY()}, wv, plane, camera);
        float[] b = a == null ? null : end(l.getX2(), l.getY2(), before, 2, a, wv, plane, camera);
        if (b == null) { return false; }
        nextLineEnds.add(new float[]{a[0], a[1], b[0], b[1]});
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int pieces = Math.max(1, (int) Math.ceil(length / LINE_PIECE));
        for (int i = 0; i < pieces; i++)
        {
            int x0 = Math.round(a[0] + (b[0] - a[0]) * i / pieces), y0 = Math.round(a[1] + (b[1] - a[1]) * i / pieces);
            int x1 = Math.round(a[0] + (b[0] - a[0]) * (i + 1) / pieces), y1 = Math.round(a[1] + (b[1] - a[1]) * (i + 1) / pieces);
            Marker m = new Marker(prefix + "line:" + out.size(), new LocalPoint((x0 + x1) / 2, (y0 + y1) / 2, wv), plane, 1, 1, color,
                Marker.NO_FILL, width, null, false);
            m.lineX = new int[]{x0, x1};
            m.lineY = new int[]{y0, y1};
            m.layer = markLayer;
            out.add(m);
        }
        return true;
    }

    /**
     * Where a line's end at canvas point (x, y) lies: where it lay last frame (before, from i) while it still projects
     * there, else on the tile centre projecting there nearest near (Shortest Path's lines run between them). Quest Helper's
     * also end where they leave the loaded area, else anywhere on the ground. Far away a pixel spans many tiles: a ray to
     * the ground there can miss by tiles, or find nothing.
     */
    private float[] end(double x, double y, float[] before, int i, float[] near, WorldView wv, int plane, ModelShapes.Camera camera)
    {
        if (before != null)
        {
            net.runelite.api.Point c = Perspective.localToCanvas(client, new LocalPoint((int) before[i], (int) before[i + 1], wv), plane);
            if (c != null && Math.abs(c.getX() - x) <= 1 && Math.abs(c.getY() - y) <= 1) { return new float[]{before[i], before[i + 1]}; }
        }
        float[] at = centre(x, y, near, wv, plane);
        if (at != null || !captureLines) { return at; }
        at = edge((float) x, (float) y, wv, plane, camera);
        return at != null ? at : WalkPredictor.ground(camera, (float) x, (float) y, wv, plane);
    }

    /** Where the camera ray through canvas point (x, y) crosses an edge of the loaded area at the ground, or null. */
    private static float[] edge(float x, float y, WorldView wv, int plane, ModelShapes.Camera camera)
    {
        float[] p = new float[3], q = new float[3];
        camera.unproject(x, y, 100, p);
        camera.unproject(x, y, 10000, q);
        float[] best = null;
        // Within half a tile's height of the ground: a pixel far away spans some height.
        double gap = 64;
        for (int k = 0; k < 4; k++)
        {
            int axis = k & 1;
            float d = q[axis] - p[axis], t = ((k < 2 ? 0 : EDGE) - p[axis]) / d;
            if (Math.abs(d) < 1e-3f || !(t > 0)) { continue; }
            float ex = p[0] + (q[0] - p[0]) * t, ey = p[1] + (q[1] - p[1]) * t, ez = p[2] + (q[2] - p[2]) * t;
            if (ex < -1 || ey < -1 || ex > EDGE + 1 || ey > EDGE + 1) { continue; }
            double off = Math.abs(ez - Terrain.height(wv, Math.max(0, Math.min(EDGE, (int) ex)), Math.max(0, Math.min(EDGE, (int) ey)), plane));
            if (off < gap) { gap = off; best = new float[]{ex, ey}; }
        }
        return best;
    }

    /**
     * The tile centre and area size {x, y, size} whose canvas polygon (Perspective.getCanvasTileAreaPoly) this is, or
     * null: the guess and its neighbours first, else the tiles around where the polygon's centre meets the ground.
     */
    private long[] match(Polygon p, LocalPoint guess, WorldView wv, ModelShapes.Camera camera)
    {
        // Last frame's tile for this shape, then the neighbours of this frame's previous one (a path's next step).
        long[] found = guess == null ? null : around(p, guess.getX() >> 7, guess.getY() >> 7, wv);
        if (found == null && last != null) { found = around(p, last.getX() >> 7, last.getY() >> 7, wv); }
        if (found != null) { return found; }
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += p.xpoints[i]; cy += p.ypoints[i]; }
        float[] ground = WalkPredictor.ground(camera, cx / 4, cy / 4, wv, wv.getPlane());
        found = ground == null ? null : around(p, (int) ground[0] >> 7, (int) ground[1] >> 7, wv);
        // Behind a hill the ray meets the hill: the tiles whose centre projects near the polygon's centre.
        int x = Math.round(cx / 4), y = Math.round(cy / 4);
        for (int attempt = 0; found == null && attempt < 2; attempt++)
        {
            int m = 24 + (index.stale ? STALE_MARGIN : 0);
            for (int[] t : index.within(client, x - m, y - m, x + m, y + m, wv))
            {
                if ((found = at(p, t[0], t[1], wv)) != null) { break; }
            }
            if (found == null && !index.rebuild()) { break; }
        }
        return found;
    }

    /** The tile matched last in this frame: a path's tiles are drawn one after another. */
    private LocalPoint last;
    /** The scene tiles by where their centres project, shared by every plugin's capture (see Index). */
    private final Index index;
    private static final int STALE_MARGIN = 96;

    /**
     * Every scene tile {x, y, canvas x, canvas y} by the canvas cell its centre projects to: no ray is cast, so terrain
     * between the camera and a tile does not matter. Built when first needed for a scene. After the camera moved it is
     * kept, searched with STALE_MARGIN and every candidate checked against the current camera, and rebuilt (projecting
     * every scene tile) only when that finds nothing, at most once per camera, for all captures.
     */
    static final class Index
    {
        private static final int CELL = 32;
        private final Map<Long, List<int[]>> cells = new HashMap<>();
        private int scene, camera, plane;
        boolean stale;
        private boolean rebuilt;

        void prepare(Client client, WorldView wv, int plane)
        {
            int scene = Objects.hash(client.getViewportWidth(), client.getViewportHeight(), plane, wv.getBaseX(), wv.getBaseY());
            int camera = Objects.hash(client.getCameraX(), client.getCameraY(), client.getCameraZ(), client.getCameraPitch(), client.getCameraYaw(), client.getScale());
            if (scene != this.scene) { cells.clear(); this.scene = scene; rebuilt = false; }
            else if (camera != this.camera) { stale = !cells.isEmpty(); rebuilt = false; }
            this.camera = camera;
            this.plane = plane;
        }

        /** Rebuilds a stale index for the current camera, once; whether it did. */
        boolean rebuild()
        {
            if (!stale || rebuilt) { return false; }
            cells.clear();
            rebuilt = true;
            return true;
        }

        void clear() { cells.clear(); }

        /** Scene tiles whose centre projects within these canvas bounds. */
        List<int[]> within(Client client, int x0, int y0, int x1, int y1, WorldView wv)
        {
            if (cells.isEmpty())
            {
                for (int x = 0; x < wv.getSizeX(); x++)
                {
                    for (int y = 0; y < wv.getSizeY(); y++)
                    {
                        net.runelite.api.Point c = Perspective.localToCanvas(client, new LocalPoint(x * 128 + 64, y * 128 + 64, wv), plane);
                        if (c != null) { cells.computeIfAbsent(cell(c.getX() / CELL, c.getY() / CELL), k -> new ArrayList<>()).add(new int[]{x, y, c.getX(), c.getY()}); }
                    }
                }
                stale = false;
            }
            List<int[]> result = new ArrayList<>();
            for (int cx = Math.floorDiv(x0, CELL); cx <= Math.floorDiv(x1, CELL); cx++)
            {
                for (int cy = Math.floorDiv(y0, CELL); cy <= Math.floorDiv(y1, CELL); cy++)
                {
                    for (int[] t : cells.getOrDefault(cell(cx, cy), Collections.emptyList()))
                    {
                        if (t[2] >= x0 && t[2] <= x1 && t[3] >= y0 && t[3] <= y1) { result.add(t); }
                    }
                }
            }
            return result;
        }

        private static long cell(int cx, int cy) { return ((long) cx << 32) | (cy & 0xffffffffL); }
    }

    /**
     * The local {x, y} of the scene tile whose centre projects exactly to this canvas point, else within a pixel of it (a
     * plugin that projects in its own way, Port Tasks), or null. Far away several round to one pixel: the one nearest near
     * (a line's other end), if given.
     */
    private float[] centre(double x, double y, float[] near, WorldView wv, int plane)
    {
        int px = (int) Math.round(x), py = (int) Math.round(y);
        for (int tolerance = 0; tolerance <= 1; tolerance++)
        {
            List<int[]> candidates = new ArrayList<>();
            if (last != null)
            {
                for (int dx = -2; dx <= 2; dx++) { for (int dy = -2; dy <= 2; dy++) { candidates.add(new int[]{(last.getX() >> 7) + dx, (last.getY() >> 7) + dy}); } }
            }
            for (int attempt = 0; attempt < 3; attempt++)
            {
                LocalPoint best = null;
                double nearest = Double.MAX_VALUE;
                for (int[] t : candidates)
                {
                    if (t[0] < 0 || t[1] < 0 || t[0] >= wv.getSizeX() || t[1] >= wv.getSizeY()) { continue; }
                    LocalPoint lp = new LocalPoint(t[0] * 128 + 64, t[1] * 128 + 64, wv);
                    net.runelite.api.Point c = Perspective.localToCanvas(client, lp, plane);
                    double d = near == null ? 0 : Math.hypot(lp.getX() - near[0], lp.getY() - near[1]);
                    if (c != null && Math.abs(c.getX() - px) <= tolerance && Math.abs(c.getY() - py) <= tolerance && d < nearest) { best = lp; nearest = d; }
                }
                if (best != null) { last = best; return new float[]{best.getX(), best.getY()}; }
                if (attempt == 1 && !index.rebuild()) { break; }
                int m = (index.stale ? STALE_MARGIN : 0) + tolerance;
                candidates = index.within(client, px - m, py - m, px + m, py + m, wv);
            }
        }
        return null;
    }

    /**
     * The NPC whose canvas tile polygon (NPC.getCanvasTilePoly, at its current, possibly between-tiles location) is
     * exactly p: last frame's one first, else the NPCs standing near it on the canvas.
     */
    private Actor matchActor(Polygon p, Actor guess, WorldView wv)
    {
        southWest = false;
        if (guess != null && (samePolygon(p, guess.getCanvasTilePoly()) || southWest(p, guess, wv))) { return guess; }
        Rectangle bounds = p.getBounds();
        for (NPC npc : wv.npcs())
        {
            if (npc != guess && standsIn(npc, bounds, wv) && samePolygon(p, npc.getCanvasTilePoly())) { return npc; }
        }
        // The south-west tile of a large NPC between tiles (Better NPC Highlight's south-west tile style).
        for (NPC npc : wv.npcs())
        {
            if (npc != guess && size(npc) > 1 && southWest(p, npc, wv)) { return npc; }
        }
        for (Player player : wv.players())
        {
            if (player != guess && standsIn(player, bounds, wv) && samePolygon(p, player.getCanvasTilePoly())) { return player; }
        }
        return null;
    }

    /** Whether an accepted NPC stands on the area of this size around tile, by its true tile or where it is drawn. */
    private boolean accepted(LocalPoint tile, int size, WorldView wv)
    {
        int x0 = (tile.getX() - size * 64) >> 7, y0 = (tile.getY() - size * 64) >> 7;
        for (NPC npc : wv.npcs())
        {
            int n = size(npc);
            LocalPoint server = LocalPoint.fromWorld(wv, npc.getWorldLocation()), drawn = npc.getLocalLocation();
            for (LocalPoint sw : new LocalPoint[]{server, drawn == null ? null : drawn.plus(-(n - 1) * 64, -(n - 1) * 64)})
            {
                int x = sw == null ? -99 : sw.getX() >> 7, y = sw == null ? -99 : sw.getY() >> 7;
                if (x < x0 + size && x + n > x0 && y < y0 + size && y + n > y0 && npcs.test(npc)) { return true; }
            }
        }
        return false;
    }

    /** Whether the last actor matched by its south-west tile only. */
    private boolean southWest;

    private boolean southWest(Polygon p, Actor actor, WorldView wv)
    {
        LocalPoint lp = actor.getLocalLocation();
        int shift = (size(actor) - 1) * 64;
        southWest = shift > 0 && lp != null && samePolygon(p, Perspective.getCanvasTilePoly(client, new LocalPoint(lp.getX() - shift, lp.getY() - shift, wv)));
        return southWest;
    }

    private static int size(Actor actor)
    {
        NPCComposition composition = actor instanceof NPC ? ((NPC) actor).getTransformedComposition() : null;
        return composition == null ? 1 : Math.max(1, composition.getSize());
    }

    /** The tile's centre projects inside its polygon: only actors standing inside the bounds are compared. */
    private boolean standsIn(Actor actor, Rectangle bounds, WorldView wv)
    {
        LocalPoint lp = actor == null ? null : actor.getLocalLocation();
        net.runelite.api.Point c = lp == null ? null : Perspective.localToCanvas(client, lp, wv.getPlane());
        return c != null && bounds.contains(c.getX(), c.getY());
    }

    static boolean samePolygon(Polygon p, Polygon q)
    {
        if (q == null || p.npoints != q.npoints) { return false; }
        for (int i = 0; i < p.npoints; i++) { if (p.xpoints[i] != q.xpoints[i] || p.ypoints[i] != q.ypoints[i]) { return false; } }
        return true;
    }

    private static double area(Polygon p)
    {
        double a = 0;
        for (int i = 0, j = p.npoints - 1; i < p.npoints; j = i++) { a += (double) p.xpoints[j] * p.ypoints[i] - (double) p.xpoints[i] * p.ypoints[j]; }
        return Math.abs(a / 2);
    }

    /** The tile within one of (tx, ty) whose area polygon equals p, as {x, y, size}, or null. */
    private long[] around(Polygon p, int tx, int ty, WorldView wv)
    {
        for (int r = 0; r <= 1; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    long[] found = Math.max(Math.abs(dx), Math.abs(dy)) == r ? at(p, tx + dx, ty + dy, wv) : null;
                    if (found != null) { return found; }
                }
            }
        }
        return null;
    }

    /** The area around tile (x, y) whose polygon equals p, of the sizes its area suggests (one tile's polygon for scale). */
    private long[] at(Polygon p, int x, int y, WorldView wv)
    {
        if (x < 0 || y < 0 || x >= wv.getSizeX() || y >= wv.getSizeY()) { return null; }
        Polygon one = Perspective.getCanvasTilePoly(client, new LocalPoint(x * 128 + 64, y * 128 + 64, wv));
        int estimate = one == null ? 1 : (int) Math.round(Math.sqrt(area(p) / Math.max(1, area(one))));
        for (int size = Math.max(1, estimate - 1); size <= Math.min(MAX_SIZE, estimate + 1); size++)
        {
            int offset = size % 2 == 1 ? 64 : 0;
            LocalPoint lp = new LocalPoint(x * 128 + offset, y * 128 + offset, wv);
            Polygon q = Perspective.getCanvasTileAreaPoly(client, lp, size);
            if (q != null && samePolygon(p, q)) { return new long[]{lp.getX(), lp.getY(), size}; }
        }
        return null;
    }

}

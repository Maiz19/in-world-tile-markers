package com.hdworldmarkers;

import java.awt.Color;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

/**
 * The shared scene renderer. Every frame, each tile footprint and model hull
 * is projected to the canvas, given a border of an exact pixel width, and
 * written back into the scene as flat geometry. The GPU rasterizes it at the
 * scene's full resolution, so it is not blurred by the stretched 2D layer.
 *
 * Vertices are unprojected slightly towards the camera along their own view
 * ray. That leaves their screen position unchanged but keeps them above the
 * terrain in depth-tested renderers.
 *
 * All shapes that belong to one tile are merged into a single scene object,
 * whose radius stays inside that tile. The client gives every tile only a few
 * object slots and an object takes one on each tile its radius touches; one
 * object per shape (up to 3x3 tiles each) overflowed them, and the client then
 * dropped a different set of objects every frame.
 */
@Singleton
final class SceneShapeRenderer
{
    private static final int MIN_VERTICES = 64, MIN_FACES = 96, MIN_RADIUS = 256;
    private final Client client;
    private final CarrierModels carriers;
    private final Map<Long, Bucket> buckets = new HashMap<>();
    /** This frame's shapes per bucket key (without part), split over the buckets in rank order in end(). */
    private final Map<Long, Stage> stages = new HashMap<>();
    private final Set<String> appended = new HashSet<>();
    /**
     * Projections per NPC or object (an object's second model part chained, see projection), kept across frames so
     * their arrays are reused. Styles of one NPC share its projection within a frame; static object models keep theirs,
     * silhouette included, while the camera and the object stay where they were.
     */
    private final Map<Object, Projection> projections = new IdentityHashMap<>();
    private int frame;
    /**
     * New outline traces per frame are limited to this much time; the rest move their last trace along (see model). A
     * real NPC model takes about half a millisecond, so a few are traced every frame and a crowd each every few frames.
     */
    private static final long OUTLINE_BUDGET_NANOS = 1_500_000;
    /** Frames an outline's last trace may stand in for a new one over the budget; then it is traced anyway. */
    static final int STALE_OUTLINE_FRAMES = 2;
    private long outlineNanos;

    /** Models no scene object uses now, kept for the next ones: making and uploading a model costs. */
    private final java.util.Deque<Spare> spareCarriers = new ArrayDeque<>();

    /** A model given up by its scene object, with what the next owner must clear of it. */
    private static final class Spare
    {
        final Model model;
        final int radius, usedFaces, usedVertices;
        Spare(Model model, int radius, int usedFaces, int usedVertices)
        { this.model = model; this.radius = radius; this.usedFaces = usedFaces; this.usedVertices = usedVertices; }
    }

    /** Takes the scene object out of the scene and keeps its model as a spare, if there is room. */
    private void retire(Bucket b)
    {
        b.hide();
        Spare spare = b.release();
        if (spare != null && spareCarriers.size() < MAX_SPARES) { spareCarriers.addLast(spare); }
    }
    /** Many tagged NPCs take more objects than 64: after a teleport the rest were all made anew in one frame. */
    private static final int MAX_SPARES = 256;
    /** Spares made ahead while logging in (see prewarm): half for tiles, half for hulls, clickboxes and outlines. */
    private static final int PREWARM = 64;

    /**
     * Makes spare models ahead, in frames that draw nothing yet (logging in), at most budgetNanos per frame: every
     * object of the first frame that drew needed one, all made in that frame. Normals as begin sets them.
     */
    void prewarm(long budgetNanos)
    {
        carriers.nextFrame();
        long start = System.nanoTime();
        while (spareCarriers.size() < PREWARM && System.nanoTime() - start < budgetNanos)
        {
            boolean small = spareCarriers.size() % 2 == 0;
            int radius = small ? MIN_RADIUS : 768;
            Model created = small ? carriers.create(MIN_VERTICES, MIN_FACES, radius) : carriers.create(512, 768, radius);
            if (created == null) { break; }
            FlatModel.normals(created);
            spareCarriers.addLast(new Spare(created, radius, 0, 6));
        }
    }

    /** Frame-local owned data: actor mesh arrays can be overwritten by another getModel(). */
    private static final class Projection
    {
        float[] x = new float[0], y = new float[0], hull;
        int[] a = new int[0], b = new int[0], c = new int[0];
        boolean[] hidden;
        /** Number of faces copied this frame; 0 until an outline needs them. */
        int faces, n;
        float depth;
        boolean outside;
        /** Some vertices were not projected (at or too near the camera): their faces are left out of every shape. */
        boolean partial;
        /** The model's clickbox is its projected bounding box (Model.useBoundingBox), not a union of face rectangles. */
        boolean boundingBox;
        List<float[]> loops;
        /**
         * The last outline traced, for a frame over the outline budget, and the frame and the projected points' bounds it
         * was traced for (centre and size): moved to the bounds of now rather than another shape.
         */
        List<float[]> traced;
        int tracedFrame;
        float tracedCenterX, tracedCenterY, tracedWidth, tracedHeight;
        /** The projected points' bounds: centre and size. */
        float centerX, centerY, width, height2d;
        /** The projected bounding box's convex hull (clickbox bounds), or null; and the clickbox from it. */
        float[] boundsHull;
        List<float[]> clickbox;
        int frame, facesFrame, localX, localY, height, orientation;
        ModelShapes.Camera camera;
        /** The previous projection's points, kept to see whether a new one came out the same (see project). */
        float[] lastX = new float[0], lastY = new float[0];
        /** Hidden faces, summed, and the viewport of the projection: all a clickbox or outline depends on besides points. */
        long hiddenSum;
        int viewport;
        /** The object's model part this is of (null for an NPC); its other part, of walls and decorations, is next. */
        Renderable part;
        Projection next;
    }
    private final Silhouette.Scratch silhouetteScratch = new Silhouette.Scratch();
    private final ScreenOutline outline = new ScreenOutline();
    private final float[] point = new float[3];
    /** Scratch for tiles, model hulls, outlines and clickboxes; ScreenOutline copies what it needs. */
    private float[] px = new float[64], py = new float[64], pd = new float[64];
    private ModelShapes.Camera camera;
    private float pixel = 1;
    /**
     * 117 HD casts a shadow from each face more opaque than its threshold (0.71, or 0.01 with its
     * "shadow transparency"), and floating through-walls marks move with the camera, so their shadows
     * shimmer over the ground. Through-walls faces are drawn at most floatingAlphaCap opaque.
     */
    int floatingAlphaCap = 255;
    /** Shadow transparency off: just under its 0.71 threshold. */
    static final int HD_CAP_OPAQUE_SHADOWS = 180;
    /**
     * 117 HD with its default shading caps the lightness of every coloured face (undoVanillaShading:
     * 127 - 72 * (saturation / 7) ^ 0.05, 55 at full saturation); only grey may be lighter. Light colours,
     * such as pink, would all turn into their pure colour: they are drawn as that colour with white over it.
     */
    boolean hdLightnessCap;
    /** Scratch for FlatModel.layers: {hsl, alpha, white alpha} of border and fill. */
    private final int[] borderLayers = new int[3], fillLayers = new int[3];
    private boolean unavailable;
    private int layer;
    /** Through walls: shapes are pulled towards the camera, gathered in one object where possible. */
    private boolean xray, xrayCameraNear;
    /**
     * Through walls, marks leave out the local player's silhouette (set per frame by cutAround): they still show through
     * every wall and object, and the player stands in front of them. The silhouette is cut out when the client draws, with
     * the camera and pose it draws with, as Improved Tile Indicators clears the player from its overlay; null for none.
     */
    private PlayerSilhouette player;
    private final PlayerSilhouette playerShape = new PlayerSilhouette();
    /**
     * Room a through-walls object keeps besides what its shapes' cuts take as seen now (see split): for faces the player
     * comes over as the camera moves.
     */
    private static final int CUT_ROOM = 600;
    /** Scratch for a shape's cut as it is drawn, which sizes the objects (see cutCosts). */
    private final ScreenOutline sizer = new ScreenOutline();
    /** Per outline face of the shape being appended, what the player's cut makes of it (sizer.before); null for no cut. */
    private int[] shapeCut;
    private LocalPoint xrayAnchor;
    private int xrayLevel, xrayHeight;
    /**
     * Depth of the top layer's through-walls points. 117 HD draws see-through faces without writing depth, sorted by
     * their distance in whole units (ties in face order), and fully opaque faces apart, before them, with depth test
     * and writes. It sorts with the client's whole-unit camera, while the marks follow the exact camera; where faces
     * overlap on the screen they lie on one ray, so that difference moves them alike. Each lower layer sits
     * XRAY_LAYER_DEPTH farther (a light colour's white layer half a step nearer than the colour): a whole unit or more
     * apart they keep the ranking, and in one unit the face order does (lowest rank first, white after its colour). The
     * top layer starts just beyond XRAY_MIN_DEPTH and the layer bias (XRAY_LAYER_SPAN), in the middle of a unit.
     * Whatever is nearer the camera than the marks covers them, such as a wall or object right in front of a low camera
     * (at 200, a quarter of the camera angles around a walled room hid marks), so all layers lie close together.
     */
    private static final float XRAY_DEPTH = 115.5f;
    private static final float XRAY_LAYER_DEPTH = 2, XRAY_TOP_LAYER = 20;
    /**
     * Objects of one bucket key sit at the same place, so the renderer's order among them (by distance to the
     * camera) is undecided: each object with higher ranks after it hangs this much lower, thus farther away. At the
     * camera's height a few units were a tie once 117 HD rounds the camera to whole units.
     */
    private static final int PART_STEP = 32;
    /**
     * Nearest depth of a through-walls vertex. 117 HD clips what is nearer the camera than twice its near plane of 50:
     * its reverse-Z projection (Mat4.perspectiveInfiniteReverseZ) gives clip z 2 x 50 over clip w the depth, so the
     * current and destination tile, pulled to 91 and 97, were not drawn at all. Its camera is the one the marks follow;
     * the GPU plugin, whose own camera may differ slightly, skips a see-through model whole if any vertex is nearer than
     * 50 (ModelUploader.uploadSortedModel).
     */
    private static final float XRAY_MIN_DEPTH = 2 * ModelShapes.NEAR + 10;
    /** Depth taken by the layer bias (0.25 per layer, up to Marker.CURRENT = 18 and a white layer), above XRAY_MIN_DEPTH. */
    private static final float XRAY_LAYER_SPAN = 5;
    /*
     * The GPU plugin draws nothing of a see-through model whose diameter is 6000 or more
     * (ModelUploader.uploadSortedModel; Zone.renderAlpha likewise). Carrier bounds are six extreme
     * vertices at +-radius on each axis, giving a diameter of about 2.83 x radius, so carriers stay
     * within MAX_RADIUS; through-walls points move towards the camera only as far as XRAY_REACH
     * from their object's anchor.
     */
    static final int MAX_RADIUS = 2000, XRAY_REACH = 1900;
    /** Ground points of a shape sharing the through-walls object stay this close to its anchor. */
    private static final int XRAY_SHARED = XRAY_REACH - 300;
    /**
     * With the camera this close (horizontally) to the through-walls anchor, the object hangs at the camera's
     * height and every shape can share it: its points are pulled to at most a few hundred units from the camera.
     */
    private static final int XRAY_CAMERA = XRAY_REACH - 700;
    private float[] tx = new float[64], ty = new float[64], tz = new float[64];
    /**
     * Layers of model marks, each drawn over the one before where they overlap: clickboxes, hulls, outlines. One layer
     * for all three left their order to the renderer's sort, which drew an NPC's outline over its clickbox in one frame
     * and under it in the next.
     */
    static final int CLICKBOX_LAYER = 14, HULL_LAYER = 15, OUTLINE_LAYER = 16;

    @Inject
    SceneShapeRenderer(Client client, CarrierModels carriers) { this.client = client; this.carriers = carriers; }

    /**
     * Starts a frame; canvasPerPixel converts screen pixels to canvas units (1 / stretch scale).
     * Shapes are rebuilt every frame: one that is not appended is simply not drawn. With xrayAnchor set
     * (in the drawn zone nearest the camera), every shape is drawn through walls: each vertex moves along
     * its view ray towards the camera, keeping its screen position and its order relative to other shapes.
     */
    void begin(ModelShapes.Camera camera, float canvasPerPixel, LocalPoint xrayAnchor, int xrayLevel)
    {
        this.xray = xrayAnchor != null;
        this.xrayAnchor = xrayAnchor;
        this.xrayLevel = xrayLevel;
        this.camera = camera;
        pixel = canvasPerPixel;
        appended.clear();
        frame++;
        outlineNanos = 0;
        player = null;
        unavailable = false;
        carriers.nextFrame();
        for (Bucket b : buckets.values()) { b.vertices = 0; b.faces = 0; b.needVertices = 0; b.needFaces = 0; b.keys.clear(); }
        for (Stage s : stages.values()) { s.vertices = 0; s.faces = 0; s.count = 0; }
        if (xray)
        {
            // Renderers sort see-through models by the distance from their position to the camera, farthest
            // first: at the camera's height the shared object comes after everything else in its zone.
            float dx = camera.x - xrayAnchor.getX(), dy = camera.y - xrayAnchor.getY();
            xrayCameraNear = dx * dx + dy * dy <= (float) XRAY_CAMERA * XRAY_CAMERA;
            xrayHeight = xrayCameraNear ? Math.round(camera.z)
                : Terrain.heightOnLevel(client.getTopLevelWorldView(), xrayAnchor.getX(), xrayAnchor.getY(), xrayLevel);
        }
    }

    /** Draws a tile footprint; returns whether it is in the scene this frame. */
    boolean tile(Marker m)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (unavailable || m.point.getWorldView() != wv.getId()) { return false; }
        if ((m.borderWidth <= 0 || m.color.getAlpha() == 0) && m.fill.getAlpha() == 0) { return culled(m.key); }
        layer = m.layer;
        LocalPoint at = m.point;
        int level = Terrain.level(wv, at.getSceneX(), at.getSceneY(), m.plane);
        int height = Terrain.heightOnLevel(wv, at.getX(), at.getY(), level);
        if (m.dot || m.offX != null) { return facing(m, at, level, height); }
        int w = m.width, h = m.height, n = 2 * (w + h);
        ensure(n);
        int x0 = at.getX() - w * 64, y0 = at.getY() - h * 64, k = 0;
        // Walk the perimeter counterclockwise, sampling height at every tile boundary.
        for (int i = 0; i < w; i++) { k = sample(wv, level, x0 + i * 128, y0, k); }
        for (int j = 0; j < h; j++) { k = sample(wv, level, x0 + w * 128, y0 + j * 128, k); }
        for (int i = w; i > 0; i--) { k = sample(wv, level, x0 + i * 128, y0 + h * 128, k); }
        for (int j = h; j > 0; j--) { k = sample(wv, level, x0, y0 + j * 128, k); }
        if (k < 0)
        {
            // Wholly behind the camera: RuneLite's 2D tile is nothing either, so the overlay need not try it every frame.
            camera.project(at.getX(), at.getY(), height, point);
            return point[2] < ModelShapes.NEAR - 64 * (w + h) - 1024 ? culled(m.key) : false;
        }
        // Off screen by its corners alone (a tile's border and mitres stay within a few widths of them): not built.
        if (beside(n, 4 * width(m.borderWidth) + 2)) { return culled(m.key); }
        boolean built = around(at, height, n, m.borderWidth);
        if (m.cornerDivisor <= 0) { return shape(m, at, level, built, m.fill); }
        if (!built || oversized()) { return false; }
        if (offscreen()) { return culled(m.key); }
        // Corners only: the fill covers the whole footprint, the border only its corners.
        if (m.fill.getAlpha() > 0) { draw(m.key, at, level, m.color, m.fill, false); }
        if (m.borderWidth > 0) { corners(m, at, w, h, level); }
        return true;
    }

    /**
     * The four corner lines of a footprint, each 1/divisor of its side along the
     * projected perimeter, as Corner Tile Indicators' renderPolygonCorners
     * does on screen.
     */
    private void corners(Marker m, LocalPoint at, int w, int h, int level)
    {
        // Perimeter indices of the four corners: south-west, south-east, north-east, north-west.
        int[] ends = {0, w, w + h, 2 * w + h};
        float[] sx = new float[3], sy = new float[3], sd = new float[3];
        for (int k = 0; k < 4; k++)
        {
            int corner = ends[k], next = ends[(k + 1) % 4], prev = ends[(k + 3) % 4];
            float t = 1f / m.cornerDivisor;
            sx[0] = lerp(px[corner], px[prev], t); sy[0] = lerp(py[corner], py[prev], t); sd[0] = lerp(pd[corner], pd[prev], t);
            sx[1] = px[corner]; sy[1] = py[corner]; sd[1] = pd[corner];
            sx[2] = lerp(px[corner], px[next], t); sy[2] = lerp(py[corner], py[next], t); sd[2] = lerp(pd[corner], pd[next], t);
            if (outline.buildStrip(sx, sy, sd, 3, width(m.borderWidth)))
            {
                draw(m.key, at, level, m.color, Marker.NO_FILL, true);
            }
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** A border width in canvas units: at least a hundredth, so a zero width still builds. */
    private float width(float borderWidth) { return Math.max(0.01f, borderWidth * pixel); }

    /** Builds the n points in px, py and pd around their centre at the given height; false when it lies too near. */
    private boolean around(LocalPoint at, int height, int n, float borderWidth)
    {
        camera.project(at.getX(), at.getY(), height, point);
        return point[2] >= PARTIAL_NEAR && outline.build(px, py, pd, n, point[0], point[1], point[2], width(borderWidth));
    }

    /** Draws the tile shape just built (not when it could not be, or is oversized); off screen it is culled. */
    private boolean shape(Marker m, LocalPoint at, int level, boolean built, Color fill)
    {
        if (!built || oversized()) { return false; }
        if (offscreen()) { return culled(m.key); }
        return draw(m.key, at, level, m.color, fill, m.borderWidth > 0);
    }

    /** Path Marker's dot style: an 8 pixel circle. */
    static final float[] DOT_X = new float[16], DOT_Y = new float[16];
    static { for (int i = 0; i < 16; i++) { DOT_X[i] = (float) Math.cos(i * Math.PI / 8) * 4; DOT_Y[i] = (float) Math.sin(i * Math.PI / 8) * 4; } }

    /** The dot around the tile's centre, its size in screen pixels. */
    /**
     * A shape standing towards the camera around where the marker's point projects: Path Marker's dot in pixels, or a
     * timer pie (Marker.offX) in local units, scaled by the camera at its depth.
     */
    private boolean facing(Marker m, LocalPoint at, int level, int height)
    {
        float[] ox = m.offX != null ? m.offX : DOT_X, oy = m.offX != null ? m.offY : DOT_Y;
        int n = ox.length;
        ensure(n);
        camera.project(at.getX(), at.getY(), height - m.lift, point);
        if (!(point[2] >= PARTIAL_NEAR)) { return false; }
        float scale = m.offX != null ? camera.scale / point[2] : pixel;
        for (int i = 0; i < n; i++) { px[i] = point[0] + ox[i] * scale; py[i] = point[1] + oy[i] * scale; pd[i] = point[2]; }
        return shape(m, at, level, outline.buildPolygon(px, py, pd, n, width(m.borderWidth), false), m.fill);
    }

    private int sample(WorldView wv, int level, int x, int y, int k)
    {
        if (k < 0) { return k; }
        // Corners on the far scene edge belong to the last tile (heightOnLevel clamps).
        camera.project(x, y, Terrain.heightOnLevel(wv, x, y, level), point);
        if (!(point[2] >= PARTIAL_NEAR)) { return -1; }
        px[k] = point[0]; py[k] = point[1]; pd[k] = point[2];
        return k + 1;
    }

    /** Draws a model's hull, clickbox or outline; returns whether it is in the scene this frame. */
    boolean model(ModelTarget t)
    {
        layer = t.outline ? OUTLINE_LAYER : t.clickbox ? CLICKBOX_LAYER : HULL_LAYER;
        LocalPoint location = t.location();
        if (unavailable || location == null || location.getWorldView() != client.getTopLevelWorldView().getId())
        { return false; }
        int height = t.height(client);
        if (t.npc == null && t.renderable == null) { return false; }
        Projection projected = projection(t);
        if (projected.frame != frame && !unchanged(t, projected, location, height))
        {
            Mesh<?> mesh = t.mesh();
            if (mesh == null) { return false; }
            project(t, mesh, projected, location, height);
        }
        projected.frame = frame;
        float depth = projected.depth;
        // Nothing of it projects (all at or behind the camera): RuneLite draws nothing of it either, so no 2D shape.
        // Off screen (not for clickboxes) it is handled by the scene route: do not repeat this work in the 2D fallback.
        if (Float.isNaN(depth) || projected.outside && !t.clickbox) { return culled(t.key); }
        int level = Terrain.level(client.getTopLevelWorldView(), location.getSceneX(), location.getSceneY(), t.plane());
        boolean asHull = false;
        if (t.outline && projected.loops == null)
        {
            // Over this frame's budget: the outline traced last, moved to where the model is now, rather than a hull border
            // for a frame (outlines flipped between the two in a crowd); a hull border only before the first trace. At
            // most a few frames old: past that it is traced anyway, or the farthest outlines would never be traced again.
            boolean over = outlineNanos > OUTLINE_BUDGET_NANOS;
            if (over && projected.traced != null && frame - projected.tracedFrame <= STALE_OUTLINE_FRAMES)
            {
                projected.loops = moved(projected.traced, projected.tracedCenterX, projected.tracedCenterY, projected.tracedWidth,
                    projected.tracedHeight, projected.centerX, projected.centerY, projected.width, projected.height2d);
            }
            else
            {
                asHull = over && projected.traced == null || !faces(t, projected);
                if (!asHull)
                {
                    long start = System.nanoTime();
                    projected.loops = projected.traced = Silhouette.trace(projected.x, projected.y, projected.a, projected.b,
                        projected.c, projected.faces, projected.hidden, silhouetteScratch);
                    projected.tracedFrame = frame;
                    projected.tracedCenterX = projected.centerX; projected.tracedCenterY = projected.centerY;
                    projected.tracedWidth = projected.width; projected.tracedHeight = projected.height2d;
                    outlineNanos += System.nanoTime() - start;
                }
            }
        }
        if (t.outline && !asHull || t.clickbox)
        {
            // A clickbox is RuneLite's, computed the same way from the float projection (FloatClickbox): RuneLite rounds
            // every face's rectangle to whole pixels, so its edge wobbled as the camera moved. Kept for static objects
            // while the camera stands still. Only when it cannot be made (off screen, behind the camera) RuneLite's own
            // is drawn.
            List<float[]> polygons = t.outline ? projected.loops : twoModels(t.object) ? null : clickbox(t, projected);
            if (polygons == null)
            {
                java.awt.Shape shape = t.shape();
                polygons = shape == null ? Collections.emptyList() : polygons(shape);
            }
            // A union of rectangles can enclose holes, wound the other way round; RuneLite leaves them unfilled
            // (even-odd), so they get their border only instead of being filled again over the rest. An outline is
            // all border, outside the silhouette.
            double outer = polygons.isEmpty() ? 0 : Math.signum(FloatClickbox.area(largest(polygons)));
            boolean any = false;
            for (float[] full : polygons)
            {
                boolean hole = polygons.size() > 1 && Math.signum(FloatClickbox.area(full)) != outer;
                int h = load(fit(full), depth);
                if (outline.buildPolygon(px, py, pd, h, width(t.borderWidth), t.outline) && !offscreen())
                {
                    draw(t.key, location, level, t.color, hole ? Marker.NO_FILL : t.fill, t.borderWidth > 0);
                    any = true;
                }
            }
            // Close to the camera the faces left out can leave nothing to trace: then no shape this frame rather than
            // RuneLite's 2D one, which flickered in and out as the camera moved.
            return any || projected.partial && culled(t.key);
        }
        if (projected.hull == null) { projected.hull = ModelShapes.convexHull(projected.x, projected.y, projected.n); }
        if (projected.hull == null) { return projected.partial && culled(t.key); }
        int h = load(projected.hull, depth);
        float cx = 0, cy = 0;
        for (int i = 0; i < h; i++) { cx += px[i]; cy += py[i]; }
        // Just in front of the nearest vertex, so the shape covers the model like the 2D overlay does.
        if (!outline.build(px, py, pd, h, cx / h, cy / h, depth, width(t.borderWidth))) { return false; }
        if (offscreen()) { return culled(t.key); }
        return draw(t.key, location, level, t.color, asHull ? Marker.NO_FILL : t.fill, t.borderWidth > 0);
    }

    /**
     * Loops traced for one projection, moved and scaled from its bounds (centre and size) to another's: where a model
     * that walked, or that the camera turned or zoomed on, is now.
     */
    static List<float[]> moved(List<float[]> loops, float fromX, float fromY, float fromWidth, float fromHeight,
        float toX, float toY, float toWidth, float toHeight)
    {
        float sx = fromWidth > 0 && toWidth > 0 ? toWidth / fromWidth : 1, sy = fromHeight > 0 && toHeight > 0 ? toHeight / fromHeight : 1;
        List<float[]> out = new ArrayList<>(loops.size());
        for (float[] loop : loops)
        {
            float[] m = new float[loop.length];
            for (int i = 0; i < loop.length; i += 2)
            {
                m[i] = toX + (loop[i] - fromX) * sx;
                m[i + 1] = toY + (loop[i + 1] - fromY) * sy;
            }
            out.add(m);
        }
        return out;
    }

    /** Copies a polygon {x0, y0, ...} into px, py and pd at the given depth; returns its number of points. */
    private int load(float[] polygon, float depth)
    {
        int h = polygon.length / 2;
        ensure(h);
        for (int i = 0; i < h; i++) { px[i] = polygon[i * 2]; py[i] = polygon[i * 2 + 1]; pd[i] = depth; }
        return h;
    }

    /**
     * The projection of a target's model part, shared by its styles: per NPC, or per object and renderable. Not per
     * renderable alone: the client gives identical objects one static model, and every copy then got the first one's
     * projection, drawn in its place.
     */
    private Projection projection(ModelTarget t)
    {
        Object owner = t.npc != null ? t.npc : t.object;
        Projection first = projections.get(owner), p = first;
        while (p != null && p.part != t.renderable) { p = p.next; }
        if (p == null)
        {
            p = new Projection();
            p.part = t.renderable;
            p.next = first;
            projections.put(owner, p);
        }
        return p;
    }

    /** The projections of a chain that were used this frame, still chained, or null when none was. */
    private Projection usedThisFrame(Projection p)
    {
        if (p == null) { return null; }
        Projection next = usedThisFrame(p.next);
        if (p.frame != frame) { return next; }
        p.next = next;
        return p;
    }

    /**
     * A static object model (not an actor, not animated) whose camera, place and rotation are
     * those of its last projection: its projection and silhouette still hold.
     */
    private boolean unchanged(ModelTarget t, Projection p, LocalPoint location, int height)
    {
        return t.npc == null && t.renderable instanceof Mesh
            && p.camera != null && p.camera.same(camera) && p.localX == location.getX() && p.localY == location.getY()
            && p.height == height && p.orientation == t.orientation() && p.frame == frame - 1;
    }

    /**
     * Points nearer the camera than this (about one tile) are left out of hulls, outlines and clickboxes, and a tile,
     * path or line with such a point is not drawn: they project to huge canvas positions, which drew a screen-wide
     * shape for a frame when the camera passed an object or came in close at login.
     */
    static final float PARTIAL_NEAR = 150;

    private void project(ModelTarget t, Mesh<?> mesh, Projection p, LocalPoint location, int height)
    {
        int n = mesh.getVerticesCount();
        // The last projection's points and shapes, to keep when this one comes out the same: an NPC standing still,
        // or its animation between two of its frames, while the camera does not move. Its clickbox and outline were
        // made again every frame, the largest part of the scene's time.
        int lastN = p.n, lastFaces = p.faces, lastViewport = p.viewport;
        long lastHidden = p.hiddenSum;
        List<float[]> lastClickbox = p.clickbox, lastLoops = p.loops;
        float[] lastHull = p.hull, swapX = p.lastX, swapY = p.lastY;
        p.lastX = p.x; p.lastY = p.y;
        p.x = swapX; p.y = swapY;
        if (p.x.length < n) { p.x = new float[n]; p.y = new float[n]; }
        p.n = n;
        p.hull = null;
        p.loops = null;
        p.clickbox = null;
        // Every projection gets the clickbox bounds from the mesh at hand (one pass over the vertices, eight projections),
        // whichever style made it: fetching the mesh again for a clickbox rebuilt an NPC's animated model a second time.
        p.boundsHull = boundsHull(mesh, n, location.getX(), location.getY(), height, t.orientation());
        p.facesFrame = 0;
        p.faces = 0;
        p.camera = camera; p.localX = location.getX(); p.localY = location.getY(); p.height = height; p.orientation = t.orientation();
        p.boundingBox = mesh instanceof Model && ((Model) mesh).useBoundingBox();
        // A model partly at or behind the camera keeps its other vertices, as RuneLite's clickbox and hull skip such
        // faces.
        p.depth = ModelShapes.projectModel(camera, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
            n, location.getX(), location.getY(), height, t.orientation(), p.x, p.y);
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        p.partial = false;
        for (int i = 0; i < n; i++)
        {
            if (Float.isNaN(p.x[i])) { p.partial = true; continue; }
            minX = Math.min(minX, p.x[i]); maxX = Math.max(maxX, p.x[i]);
            minY = Math.min(minY, p.y[i]); maxY = Math.max(maxY, p.y[i]);
        }
        p.centerX = (minX + maxX) / 2; p.centerY = (minY + maxY) / 2; p.width = maxX - minX; p.height2d = maxY - minY;
        // Conservative margin includes the widest supported border and its mitres.
        float margin = 64 * pixel;
        int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
        int vw = client.getViewportWidth(), vh = client.getViewportHeight();
        p.viewport = Objects.hash(vx, vy, vw, vh);
        p.outside = maxX + margin < vx || maxY + margin < vy
            || minX - margin > vx + vw || minY - margin > vy + vh;
        // Actor models live in a shared client buffer: copy their faces now, into reused arrays, in case
        // another style of this NPC needs them after another actor's getModel(). Objects only for outlines.
        if (t.outline || t.npc != null) { copyFaces(mesh, p); }
        if (n == lastN && p.faces == lastFaces && p.faces > 0 && p.hiddenSum == lastHidden && p.viewport == lastViewport
            && Arrays.equals(p.x, 0, n, p.lastX, 0, n) && Arrays.equals(p.y, 0, n, p.lastY, 0, n))
        {
            p.clickbox = lastClickbox; p.loops = lastLoops; p.hull = lastHull;
        }
    }

    /**
     * Face topology for outlines, copied once per projection. NPCs copy it when projected; a static
     * object's hull projection gets it here when its outline follows.
     */
    private boolean faces(ModelTarget t, Projection p)
    {
        // Copied faces stay valid until the next projection; one copy attempt per frame otherwise.
        if (p.faces > 0) { return true; }
        if (p.facesFrame == frame) { return false; }
        Mesh<?> mesh = t.mesh();
        if (mesh == null || mesh.getVerticesCount() != p.n) { return false; }
        copyFaces(mesh, p);
        return p.faces > 0;
    }

    private void copyFaces(Mesh<?> mesh, Projection p)
    {
        p.facesFrame = frame;
        p.faces = 0;
        int faces = mesh.getFaceCount();
        if (p.outside || Float.isNaN(p.depth) || faces <= 0) { return; }
        if (p.a.length < faces) { p.a = new int[faces]; p.b = new int[faces]; p.c = new int[faces]; }
        System.arraycopy(mesh.getFaceIndices1(), 0, p.a, 0, faces);
        System.arraycopy(mesh.getFaceIndices2(), 0, p.b, 0, faces);
        System.arraycopy(mesh.getFaceIndices3(), 0, p.c, 0, faces);
        for (int f = 0; f < faces; f++)
        {
            // Topology that does not fit the projected vertices is not traced.
            if (p.a[f] < 0 || p.b[f] < 0 || p.c[f] < 0 || p.a[f] >= p.n || p.b[f] >= p.n || p.c[f] >= p.n) { return; }
        }
        int[] colors = mesh instanceof Model ? ((Model) mesh).getFaceColors3() : null;
        if (colors == null) { p.hidden = null; }
        else
        {
            if (p.hidden == null || p.hidden.length < faces) { p.hidden = new boolean[faces]; }
            for (int f = 0; f < faces; f++) { p.hidden[f] = f < colors.length && colors[f] == -2; }
        }
        long hiddenSum = 0;
        if (p.hidden != null) { for (int f = 0; f < faces; f++) { if (p.hidden[f]) { hiddenSum += f * 31L + 1; } } }
        p.hiddenSum = hiddenSum;
        p.faces = faces;
    }

    /** Whether every point of the current shape lies within reach of the given anchor. */
    private boolean within(float x, float y, float height, float reach)
    {
        for (int i = 0; i < outline.vertices; i++)
        {
            float dx = tx[i] - x, dy = ty[i] - y, dz = tz[i] - height;
            if (dx * dx + dy * dy + dz * dz > reach * reach) { return false; }
        }
        return true;
    }

    /**
     * Appends the current outline to the stage of the anchor's tile, in world coordinates. The stages are split
     * over scene objects in rank order and written once per object in end().
     */
    private boolean draw(String key, LocalPoint anchor, int level, Color border, Color fill, boolean hasBorder)
    {
        // Through walls applies to every shape, tiles as well as hulls, clickboxes and outlines: all of them
        // then sit at the same place in front of the camera, so lighting renderers (117 HD: shadows, fog,
        // lights) shade them all alike.
        // World points of the shape: on the ground for through walls (moved to the camera at draw time),
        // else just above it towards the camera. Later layers slightly nearer, so shapes never z-fight.
        place(xray, false);
        WorldView top = client.getTopLevelWorldView();
        boolean shared = xray && (xrayCameraNear || within(xrayAnchor.getX(), xrayAnchor.getY(), xrayHeight, XRAY_SHARED));
        if (shared)
        {
            // One object in the drawn zone nearest the camera: a tile hidden behind a wall may not be drawn
            // by the client. Shapes too far from it for one model get a through-walls object on their own tile.
            anchor = xrayAnchor;
            level = xrayLevel;
        }
        // Shapes beyond the loaded area hang on the nearest edge tile; the client only places objects inside it.
        int tileX = Math.max(0, Math.min(top.getSizeX() - 1, anchor.getX() >> 7));
        int tileY = Math.max(0, Math.min(top.getSizeY() - 1, anchor.getY() >> 7));
        // Floating marks under 117 HD stay under its shadow threshold.
        int cap = xray ? floatingAlphaCap : 255;
        // The cap applies to the colour as a whole, before it is split into layers that blend to it.
        layers(border, Math.min(cap, hasBorder ? border.getAlpha() : 0), borderLayers);
        layers(fill, Math.min(cap, fill.getAlpha()), fillLayers);
        int borderHsl = borderLayers[0], borderAlpha = borderLayers[1], borderWhite = borderLayers[2];
        int fillHsl = fillLayers[0], fillAlpha = fillLayers[1], fillWhite = fillLayers[2];
        // The white layer: a second copy of the outline, just nearer the camera so it is drawn over the colour.
        boolean white = borderWhite > 0 || fillWhite > 0;
        // A single shape too large for any scene object is left out; a bucket past the carrier limits fails the whole frame.
        int addVertices = outline.vertices * (white ? 2 : 1), addFaces = outline.faces * (white ? 2 : 1);
        if (!carriers.fits(addVertices, addFaces, 2)) { return false; }
        if (!shared && !within(tileX * 128 + 64, tileY * 128 + 64, Terrain.heightOnLevel(top, tileX * 128 + 64, tileY * 128 + 64, level),
                xray ? XRAY_REACH - 200 : MAX_RADIUS - 300)) { return false; }
        // Through-walls shapes get their own buckets: they are moved to the camera, others are not.
        long base = ((long) level << 32) | ((long) tileX << 16) | tileY | (xray ? 1L << 40 : 0) | (shared ? 1L << 39 : 0);
        Stage st = stages.get(base);
        if (st == null) { st = new Stage(tileX, tileY, level, anchor.getWorldView()); stages.put(base, st); }
        st.ensure(st.vertices + addVertices, st.faces + addFaces);
        st.xray = xray;
        st.shared = shared;
        st.begin(layer, key);
        shapeCut = xray ? cutCosts() : null;
        append(st, layer, borderHsl, borderAlpha, fillHsl, fillAlpha);
        if (white)
        {
            if (!xray) { place(false, true); }
            append(st, layer + 0.5f, FlatModel.WHITE, borderWhite, FlatModel.WHITE, fillWhite);
        }
        st.end();
        appended.add(key);
        return true;
    }

    /** FlatModel.layers per colour, alpha and lightness cap: a colour's HSL takes powers, for every shape every frame. */
    private final Map<Long, int[]> layerCache = new HashMap<>();

    private void layers(Color color, int alpha, int[] out)
    {
        long key = (long) color.getRGB() << 9 | alpha << 1 | (hdLightnessCap ? 1 : 0);
        int[] known = layerCache.get(key);
        if (known == null)
        {
            if (layerCache.size() > 1024) { layerCache.clear(); }
            known = new int[3];
            FlatModel.layers(color, alpha, hdLightnessCap, known);
            layerCache.put(key, known);
        }
        System.arraycopy(known, 0, out, 0, 3);
    }

    /** The camera the client draws with now; the last one again while it has not changed (getModel asks per object). */
    private volatile ModelShapes.Camera lastDrawCamera;

    private ModelShapes.Camera drawCamera()
    {
        ModelShapes.Camera last = lastDrawCamera, now = ModelShapes.Camera.of(client, last);
        if (now != last) { lastDrawCamera = now; }
        return now;
    }

    /**
     * World points (tx, ty, tz) of the outline: on the ground for through walls (moved to the camera at draw time),
     * else just above it towards the camera, later layers slightly nearer so shapes never z-fight. The white layer
     * of a light colour is always a little nearer than its colour layer, also where the layer offset bottoms out.
     */
    private void place(boolean throughWalls, boolean white)
    {
        if (tx.length < outline.vertices) { tx = new float[outline.vertices * 2]; ty = new float[outline.vertices * 2]; tz = new float[outline.vertices * 2]; }
        float layerBias = layer * 0.001f;
        for (int i = 0; i < outline.vertices; i++)
        {
            float d = outline.depth[i];
            float at = throughWalls ? d : d - Math.max(4, d * 0.01f) - Math.max(1, d * layerBias) - (white ? Math.max(0.5f, d * 0.0005f) : 0);
            camera.unproject(outline.x[i], outline.y[i], at, point);
            tx[i] = point[0]; ty[i] = point[1]; tz[i] = point[2];
        }
    }

    /** Adds the outline's vertices (tx, ty, tz) and its visible faces to the stage. */
    private void append(Stage b, float vertexLayer, int borderHsl, int borderAlpha, int fillHsl, int fillAlpha)
    {
        int first = b.vertices;
        for (int i = 0; i < outline.vertices; i++)
        {
            b.layer[first + i] = vertexLayer;
            b.wx[first + i] = tx[i]; b.wy[first + i] = ty[i]; b.wz[first + i] = tz[i];
        }
        b.vertices += outline.vertices;
        for (int f = 0; f < outline.faces; f++)
        {
            boolean isBorder = f < outline.borderFaces;
            int alpha = isBorder ? borderAlpha : fillAlpha;
            if (alpha <= 0) { continue; }
            int k = b.faces++;
            b.fa[k] = first + outline.a[f]; b.fb[k] = first + outline.b[f]; b.fc[k] = first + outline.c[f];
            b.color[k] = isBorder ? borderHsl : fillHsl;
            b.alpha[k] = alpha;
            // What the player's cut makes of the face, with half as much again to grow as the camera moves.
            int points = shapeCut == null ? 0 : shapeCut[2 * f + 2] - shapeCut[2 * f];
            int pieces = shapeCut == null ? 1 : shapeCut[2 * f + 3] - shapeCut[2 * f + 1];
            b.cutVertices[k] = points + points / 2;
            b.cutFaces[k] = pieces <= 1 ? 1 : pieces + pieces / 2;
        }
    }

    /**
     * The player's cut of the outline as seen now, per face (ScreenOutline.before): what the objects holding the shape
     * need room for. Null when the player's silhouette does not come near the shape.
     */
    private int[] cutCosts()
    {
        PlayerCut around = player == null ? null : player.estimate(camera);
        if (around == null || around.maxX < outline.minX || around.minX > outline.maxX || around.maxY < outline.minY
            || around.minY > outline.maxY) { return null; }
        sizer.load(outline.x, outline.y, outline.depth, outline.vertices, outline.a, outline.b, outline.c, outline.faces);
        return sizer.cutOut(around, Integer.MAX_VALUE, Integer.MAX_VALUE) ? sizer.before : null;
    }

    /**
     * Walls and decorations with a second model: RuneLite's clickbox covers both, the float clickbox only the first,
     * so RuneLite's own is drawn for them.
     */
    private static boolean twoModels(TileObject object)
    {
        return object instanceof WallObject && ((WallObject) object).getRenderable2() != null
            || object instanceof DecorativeObject && ((DecorativeObject) object).getRenderable2() != null;
    }

    /**
     * The clickbox polygons from the projection: the bounding box hull for bounding-box models, else RuneLite's union of
     * face rectangles clipped to it (FloatClickbox). Null without face topology.
     */
    private List<float[]> clickbox(ModelTarget t, Projection p)
    {
        if (p.clickbox != null) { return p.clickbox; }
        if (p.boundingBox) { return p.boundsHull == null ? null : (p.clickbox = Collections.singletonList(p.boundsHull)); }
        if (!faces(t, p)) { return null; }
        int vx = client.getViewportXOffset();
        // As calculate2DBounds, which takes the viewport's x offset for its top edge too.
        return p.clickbox = FloatClickbox.of(p.x, p.y, p.a, p.b, p.c, p.faces, p.hidden, p.boundsHull,
            vx, vx, vx + client.getViewportWidth(), vx + client.getViewportHeight());
    }

    /**
     * The convex hull {x0, y0, ...} of the model's bounding box on the canvas (Perspective.calculateAABB), or null:
     * Model.getAABB as RuneLite takes it, or for unlit ModelData (no getAABB) the box of its vertices.
     */
    private float[] boundsHull(Mesh<?> mesh, int n, int localX, int localY, int height, int orientation)
    {
        if (n <= 0) { return null; }
        float x1, x2, y1, y2, z1, z2;
        AABB box = mesh instanceof Model ? ((Model) mesh).getAABB(orientation) : null;
        if (box != null)
        {
            // RuneLite's own box (Perspective.calculateAABB): it can be larger than the vertices, and a bounding-box
            // model's clickbox is exactly this box.
            x1 = box.getCenterX() - box.getExtremeX(); x2 = box.getCenterX() + box.getExtremeX();
            y1 = box.getCenterY() - box.getExtremeY(); y2 = box.getCenterY() + box.getExtremeY();
            z1 = box.getCenterZ() - box.getExtremeZ(); z2 = box.getCenterZ() + box.getExtremeZ();
        }
        else
        {
            // Unlit ModelData has no getAABB: the box of its vertices turned as ModelShapes.projectModel turns them.
            float[] vx = mesh.getVerticesX(), vy = mesh.getVerticesY(), vz = mesh.getVerticesZ();
            double angle = (orientation & 2047) * Math.PI / 1024;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            x1 = Float.MAX_VALUE; x2 = -Float.MAX_VALUE; y1 = x1; y2 = x2; z1 = x1; z2 = x2;
            for (int i = 0; i < n; i++)
            {
                float rx = vz[i] * sin + vx[i] * cos, rz = vz[i] * cos - vx[i] * sin;
                x1 = Math.min(x1, rx); x2 = Math.max(x2, rx);
                y1 = Math.min(y1, vy[i]); y2 = Math.max(y2, vy[i]);
                z1 = Math.min(z1, rz); z2 = Math.max(z2, rz);
            }
        }
        float[] bx = {x1, x2, x1, x2, x1, x2, x1, x2}, by = {y1, y1, y1, y1, y2, y2, y2, y2}, bz = {z1, z1, z2, z2, z1, z1, z2, z2};
        float[] cx = new float[8], cy = new float[8];
        // Corners at or behind the camera are left out, as the model's own vertices.
        if (Float.isNaN(ModelShapes.projectModel(camera, bx, by, bz, 8, localX, localY, height, 0, cx, cy)))
        { return null; }
        return ModelShapes.convexHull(cx, cy, 8);
    }

    /** Points a drawn polygon keeps at most: its border and fill then always fit one scene object (see fit). */
    static final int MAX_POINTS = 256;

    /**
     * The polygon with at most MAX_POINTS points: simplified (Douglas-Peucker) with the smallest tolerance from half a
     * pixel up, doubling, that gets there. Close to the camera a clickbox or outline is large on screen and has
     * thousands of points; its border and fill then outgrew a scene object, the mark was left out and RuneLite's own
     * 2D shape was drawn instead. At that size a pixel or two is not visible. Unchanged when small enough.
     */
    static float[] fit(float[] p)
    {
        float[] out = p;
        for (double tolerance = 0.5; out.length / 2 > MAX_POINTS && tolerance <= 128; tolerance *= 2) { out = Silhouette.simplify(p, tolerance); }
        return out;
    }

    private static float[] largest(List<float[]> polygons)
    {
        float[] best = polygons.get(0);
        for (float[] p : polygons) { if (Math.abs(FloatClickbox.area(p)) > Math.abs(FloatClickbox.area(best))) { best = p; } }
        return best;
    }

    /** The closed polygons of a canvas shape, as {x0, y0, x1, y1, ...}, without a repeated closing point. */
    static java.util.List<float[]> polygons(java.awt.Shape shape)
    {
        java.util.List<float[]> result = new ArrayList<>();
        float[] coords = new float[6];
        float[] current = new float[64];
        int size = 0;
        for (java.awt.geom.PathIterator it = shape.getPathIterator(null, 0.5); !it.isDone(); it.next())
        {
            int type = it.currentSegment(coords);
            if (type == java.awt.geom.PathIterator.SEG_MOVETO)
            {
                if (size >= 6) { result.add(Arrays.copyOf(current, size)); }
                size = 0;
            }
            if (type == java.awt.geom.PathIterator.SEG_MOVETO || type == java.awt.geom.PathIterator.SEG_LINETO)
            {
                if (size >= 2 && current[size - 2] == coords[0] && current[size - 1] == coords[1]) { continue; }
                if (size + 2 > current.length) { current = Arrays.copyOf(current, current.length * 2); }
                current[size++] = coords[0];
                current[size++] = coords[1];
            }
            if (type == java.awt.geom.PathIterator.SEG_CLOSE)
            {
                if (size >= 4 && current[0] == current[size - 2] && current[1] == current[size - 1]) { size -= 2; }
                if (size >= 6) { result.add(Arrays.copyOf(current, size)); }
                size = 0;
            }
        }
        if (size >= 6) { result.add(Arrays.copyOf(current, size)); }
        result.replaceAll(FloatClickbox::despur);
        result.removeIf(Objects::isNull);
        return result;
    }

    /** Whether the first n points in px and py lie this far (canvas units) or farther outside the viewport. */
    private boolean beside(int n, float margin)
    {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) { minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]); minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]); }
        int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
        return maxX + margin < vx || maxY + margin < vy || minX - margin > vx + client.getViewportWidth() || minY - margin > vy + client.getViewportHeight();
    }

    private boolean offscreen()
    {
        int vx = client.getViewportXOffset(), vy = client.getViewportYOffset();
        return outline.maxX < vx || outline.maxY < vy
            || outline.minX > vx + client.getViewportWidth() || outline.minY > vy + client.getViewportHeight();
    }

    /**
     * A tile, path or line shape larger than three viewports: none is, unless a point lies right at the camera (a
     * screen-wide square flashed at login). Such a shape is not drawn. Hulls and clickboxes of a building close by can
     * be that large, so they are not checked.
     */
    private boolean oversized()
    {
        return outline.maxX - outline.minX > 3 * client.getViewportWidth() || outline.maxY - outline.minY > 3 * client.getViewportHeight();
    }

    private void ensure(int n)
    {
        if (px.length < n) { px = new float[n * 2]; py = new float[n * 2]; pd = new float[n * 2]; }
    }

    private boolean culled(String key)
    {
        appended.add(key);
        return false;
    }

    /** Ends a frame: writes one scene object per tile. Returns false if no carrier model could be made. */
    boolean end()
    {
        for (Map.Entry<Long, Stage> e : stages.entrySet()) { split(e.getKey(), e.getValue()); }
        stages.values().removeIf(st -> st.count == 0);
        Iterator<Map.Entry<Long, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext())
        {
            Bucket b = it.next().getValue();
            if (unavailable || b.faces == 0)
            {
                if (unavailable) { b.hide(); } else { retire(b); }
                it.remove();
            }
        }
        // Projections of models not drawn this frame are dropped.
        projections.replaceAll((owner, p) -> usedThisFrame(p));
        projections.values().removeIf(Objects::isNull);
        // Reclaim vacated tiles before allocating models for newly occupied tiles. An object that cannot be made leaves only
        // its own marks to their 2D fallback.
        int written = 0, lost = 0;
        for (Iterator<Bucket> made = buckets.values().iterator(); made.hasNext(); )
        {
            Bucket b = made.next();
            if (write(b)) { written++; continue; }
            appended.removeAll(b.keys);
            retire(b);
            made.remove();
            lost++;
        }
        // None at all (the models are not loaded yet after logging in): the scene is unavailable this frame.
        if (lost > 0 && written == 0) { unavailable = true; }
        if (unavailable) { clear(); }
        return !unavailable;
    }

    /**
     * Moves a stage's shapes into its buckets, lowest rank first and in drawn order within a rank, so the faces of
     * every object, and the objects of the key one after another, run from the bottom rank to the top one. A full
     * bucket continues in the next object on the same tile, so no model exceeds the renderer's limits.
     */
    private void split(long base, Stage st)
    {
        if (st.count == 0) { return; }
        int[] order = st.byRank();
        int part = 0;
        Bucket b = part(base, 0, st);
        for (int k = 0; k < st.count; k++)
        {
            int s = order[k] * Stage.FIELDS;
            int firstVertex = st.shapes[s + 1], nv = st.shapes[s + 2], firstFace = st.shapes[s + 3], nf = st.shapes[s + 4];
            // Filled to the largest carrier: a second object at the same place is ordered by its anchor alone. Through
            // walls with room for the player's cut: what it makes of the shape as seen now, and more as the camera moves.
            int room = st.xray && player != null ? CUT_ROOM : 0, needVertices = nv, needFaces = 0;
            for (int f = firstFace; f < firstFace + nf; f++) { needVertices += st.cutVertices[f]; needFaces += st.cutFaces[f]; }
            if (b.faces > 0 && !carriers.fits(b.needVertices + needVertices + room, b.needFaces + needFaces + room, 1)) { b = part(base, ++part, st); }
            String key = st.keys[order[k]];
            b.keys.add(key);
            if (!carriers.fits(needVertices + room, needFaces + room, 1))
            {
                // Larger than any carrier: face by face, with own vertices.
                for (int f = firstFace; f < firstFace + nf; f++)
                {
                    int fv = 3 + st.cutVertices[f], ff = st.cutFaces[f];
                    if (b.faces > 0 && !carriers.fits(b.needVertices + fv + room, b.needFaces + ff + room, 1)) { b = part(base, ++part, st); b.keys.add(key); }
                    b.ensure(b.vertices + 3, b.faces + 1);
                    int to = b.faces++;
                    b.fa[to] = copy(st, st.fa[f], b); b.fb[to] = copy(st, st.fb[f], b); b.fc[to] = copy(st, st.fc[f], b);
                    b.color[to] = st.color[f]; b.alpha[to] = st.alpha[f];
                    b.needVertices += fv;
                    b.needFaces += ff;
                }
                continue;
            }
            b.needVertices += needVertices;
            b.needFaces += needFaces;
            b.ensure(b.vertices + nv, b.faces + nf);
            int shift = b.vertices - firstVertex;
            System.arraycopy(st.wx, firstVertex, b.wx, b.vertices, nv); System.arraycopy(st.wy, firstVertex, b.wy, b.vertices, nv);
            System.arraycopy(st.wz, firstVertex, b.wz, b.vertices, nv); System.arraycopy(st.layer, firstVertex, b.layer, b.vertices, nv);
            for (int f = firstFace; f < firstFace + nf; f++)
            {
                int to = b.faces++;
                b.fa[to] = st.fa[f] + shift; b.fb[to] = st.fb[f] + shift; b.fc[to] = st.fc[f] + shift;
                b.color[to] = st.color[f]; b.alpha[to] = st.alpha[f];
            }
            b.vertices += nv;
        }
        for (int p = 0; p <= part; p++) { buckets.get(base | ((long) p << 41)).partsAfter = part - p; }
    }

    /** Copies vertex v of the stage to the end of bucket b (which has room); returns its index there. */
    private static int copy(Stage st, int v, Bucket b)
    {
        int i = b.vertices++;
        b.wx[i] = st.wx[v]; b.wy[i] = st.wy[v]; b.wz[i] = st.wz[v]; b.layer[i] = st.layer[v];
        return i;
    }

    private Bucket part(long base, int part, Stage st)
    {
        long id = base | ((long) part << 41);
        Bucket b = buckets.get(id);
        if (b == null) { b = new Bucket(st.tileX, st.tileY, st.level, st.worldView); buckets.put(id, b); }
        b.xray = st.xray;
        b.shared = st.shared;
        return b;
    }

    private boolean write(Bucket b)
    {
        int anchorX = b.tileX * 128 + 64, anchorY = b.tileY * 128 + 64, anchorZ;
        if (b.shared)
        {
            anchorX = Math.max(b.tileX * 128, Math.min(b.tileX * 128 + 127, xrayAnchor.getX()));
            anchorY = Math.max(b.tileY * 128, Math.min(b.tileY * 128 + 127, xrayAnchor.getY()));
            anchorZ = xrayHeight;
        }
        else
        {
            float z = 0;
            for (int i = 0; i < b.vertices; i++) { z += b.wz[i]; }
            anchorZ = Math.round(z / b.vertices);
        }
        // Below the objects with higher ranks at the same place (the camera is above): farther, so drawn before them.
        anchorZ += b.partsAfter * PART_STEP;
        // Through walls: the geometry is pulled towards the camera, at most XRAY_REACH from the anchor.
        float extent = b.xray ? XRAY_REACH + 16 : 0;
        for (int i = 0; i < b.vertices && !b.xray; i++)
        {
            float dx = b.wx[i] - anchorX, dy = b.wy[i] - anchorY, dz = b.wz[i] - anchorZ;
            extent = Math.max(extent, Math.max((float) Math.sqrt((double) dx * dx + (double) dy * dy), Math.abs(dz)));
        }
        // Through walls, room for the player's silhouette cut out (see Bucket.pullToCamera) as split measured it; never more
        // than a carrier holds (one face larger than that stays whole), or making the object fails and with it the frame.
        int vertices = b.vertices, faces = b.faces;
        if (b.xray && player != null && carriers.fits(b.needVertices + CUT_ROOM, b.needFaces + CUT_ROOM, 1))
        {
            vertices = b.needVertices + CUT_ROOM;
            faces = b.needFaces + CUT_ROOM;
        }
        if (b.model == null || b.model.getVerticesCount() < vertices || b.model.getFaceCount() < faces || extent > b.radius)
        {
            // The model it had goes back to the spares for smaller objects.
            retire(b);
            // The smallest spare with room to grow, so the next frame's slightly larger shape still fits and a small
            // object does not take a large model, whose unused capacity is walked every frame.
            float wantRadius = b.xray ? extent : Math.min(MAX_RADIUS, extent * 1.25f);
            int wantVertices = (int) Math.ceil(vertices * 1.25f), wantFaces = (int) Math.ceil(faces * 1.25f);
            Spare spare = spare(wantRadius, wantVertices, wantFaces);
            // Filled close to the largest carrier (see split), no model has room to grow: one that fits will do.
            if (spare == null) { spare = spare(wantRadius, vertices, faces); }
            if (spare != null)
            {
                spareCarriers.remove(spare);
                b.adopt(spare);
            }
            else
            {
                int radius = b.xray ? MAX_RADIUS : Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (int) Math.ceil(extent * 1.5f)));
                // Twice the room where that fits a carrier; an object filled past that (see split) gets what it holds.
                boolean roomy = carriers.fits(vertices, faces, 2);
                Model created = carriers.create(Math.max(MIN_VERTICES, roomy ? vertices * 2 : vertices),
                    Math.max(MIN_FACES, roomy ? faces * 2 : faces), radius);
                if (created == null) { return false; }
                // Normals point straight up (model y is down): 117 HD, which uses them because faces are not
                // marked flat (see FlatModel.paint), then lights every mark like flat ground seen from above.
                FlatModel.normals(created);
                // Carriers start with six extreme vertices from fixing their bounds.
                b.adopt(new Spare(created, radius, 0, 6));
            }
        }
        Model m = b.model;
        if (b.xray)
        {
            // Through walls, getModel writes the vertices and faces with the camera the client draws with (pullToCamera):
            // every vertex (unused ones onto a used one, so none passes through the origin, which the renderer may read
            // meanwhile; all go back to the origin on the next write). Pulling here as well was thrown away whenever the
            // camera moved.
            b.freeze(anchorX, anchorY, anchorZ, player);
            b.usedVertices = m.getVerticesCount();
        }
        else
        {
            // Unused vertices at the origin, inside the fixed bounds.
            float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
            for (int i = b.vertices; i < b.usedVertices; i++) { vx[i] = 0; vy[i] = 0; vz[i] = 0; }
            for (int i = 0; i < b.vertices; i++)
            {
                vx[i] = b.wx[i] - anchorX;
                vy[i] = b.wz[i] - anchorZ;
                vz[i] = b.wy[i] - anchorY;
            }
            b.usedVertices = b.vertices;
            b.writeFaces(m, b.faces, b.fa, b.fb, b.fc, null, b.color, b.alpha);
            FlatModel.bounds(m);
        }
        b.object.setLocation(new LocalPoint(anchorX, anchorY, b.worldView), b.level);
        b.object.setZ(anchorZ);
        // Orientation 0 and the default radius 60 (tile centre +- 60) keep the footprint on its own tile; the model's
        // own fixed bounds cover the geometry.
        if (!client.isRuneLiteObjectRegistered(b.object)) { client.registerRuneLiteObject(b.object); }
        return true;
    }

    /** The spare with the fewest vertices that has this radius and room, or null. */
    private Spare spare(float radius, int vertices, int faces)
    {
        Spare best = null;
        for (Spare s : spareCarriers)
        {
            if (s.radius >= radius && s.model.getVerticesCount() >= vertices && s.model.getFaceCount() >= faces
                && (best == null || s.model.getVerticesCount() < best.model.getVerticesCount())) { best = s; }
        }
        return best;
    }

    /** The local player this frame, whose silhouette marks through walls leave out (see player); none for null. */
    void cutAround(Player p)
    {
        player = null;
        if (p == null || !xray || unavailable) { return; }
        // On a boat the player's location is in the boat's world view, not the one projected here.
        if (p.getWorldView() != client.getTopLevelWorldView()) { return; }
        LocalPoint at = p.getLocalLocation();
        if (at == null) { return; }
        // As the client places an actor: on its footprint's height, raised by its animation.
        int height = Perspective.getFootprintTileHeight(client, at, p.getWorldView().getPlane(), p.getFootprintSize()) - p.getAnimationHeightOffset();
        // Where a wall or object nearer the camera covers the player, it is not seen there and the marks are not cut.
        // Taken before the player's model, which can share its arrays with the objects' models.
        playerShape.clearOccluders();
        occluders(p, at);
        Model model = p.getModel();
        if (model != null && playerShape.take(model, at.getX(), at.getY(), height, p.getCurrentOrientation())) { player = playerShape; }
    }

    /**
     * The walls and objects that may stand between the camera and the player (and the actors the client keeps as objects
     * on their tiles): those on the tiles along the way from the camera to the player and beside them.
     */
    private void occluders(Player p, LocalPoint at)
    {
        int plane = p.getWorldView().getPlane();
        Scene scene = client.getTopLevelWorldView().getScene();
        Tile[][][] levels = scene == null ? null : scene.getTiles();
        if (levels == null || plane < 0 || plane >= levels.length) { return; }
        Tile[][] tiles = levels[plane];
        float dx = at.getX() - camera.x, dy = at.getY() - camera.y;
        int steps = (int) (Math.hypot(dx, dy) / 64) + 1;
        Set<Object> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> visited = new HashSet<>();
        for (int i = 0; i <= steps; i++)
        {
            int cx = (int) (camera.x + dx * i / steps) >> 7, cy = (int) (camera.y + dy * i / steps) >> 7;
            for (int tx = cx - 1; tx <= cx + 1; tx++)
            {
                for (int ty = cy - 1; ty <= cy + 1; ty++)
                {
                    if (tx < 0 || ty < 0 || tx >= tiles.length || ty >= tiles[tx].length || tiles[tx][ty] == null || !visited.add((long) tx << 32 | ty)) { continue; }
                    Tile t = tiles[tx][ty];
                    WallObject w = t.getWallObject();
                    if (w != null && taken.add(w)) { occluder(w.getRenderable1(), 0, w, p); occluder(w.getRenderable2(), 0, w, p); }
                    for (GameObject o : t.getGameObjects()) { if (o != null && taken.add(o)) { occluder(o.getRenderable(), o.getModelOrientation(), o, p); } }
                }
            }
        }
    }

    private void occluder(Renderable r, int orientation, TileObject o, Player p)
    {
        if (r == null || r == p) { return; }
        Model m = r instanceof Model ? (Model) r : r.getModel();
        LocalPoint at = o.getLocalLocation();
        if (m != null && at != null) { playerShape.occluder(m, at.getX(), at.getY(), o.getZ(), orientation); }
    }

    /** Whether the shape with this key is in the scene this frame. */
    boolean drawn(String key) { return !unavailable && appended.contains(key); }

    /** Takes every shape out of the scene; their models stay as spares for the next frames. */
    void clear()
    {
        for (Bucket b : buckets.values()) { retire(b); }
        buckets.clear();
        stages.clear();
        silhouetteScratch.bits = new long[0];
        projections.clear();
        appended.clear();
    }

    /** As clear, and the models are dropped too. */
    void reset() { clear(); spareCarriers.clear(); carriers.reset(); }

    /** Shapes of one tile and level in world points with their layer, and faces with colour and alpha. */
    private static class Geometry
    {
        final int tileX, tileY, level, worldView;
        float[] wx = new float[64], wy = new float[64], wz = new float[64], layer = new float[64];
        int[] fa = new int[96], fb = new int[96], fc = new int[96], color = new int[96], alpha = new int[96];
        int vertices, faces;
        boolean xray, shared;

        Geometry(int tileX, int tileY, int level, int worldView)
        { this.tileX = tileX; this.tileY = tileY; this.level = level; this.worldView = worldView; }

        void ensure(int vertexCount, int faceCount)
        {
            if (wx.length < vertexCount)
            {
                int n = vertexCount * 2;
                wx = Arrays.copyOf(wx, n); wy = Arrays.copyOf(wy, n); wz = Arrays.copyOf(wz, n);
                layer = Arrays.copyOf(layer, n);
            }
            if (fa.length < faceCount)
            {
                int n = faceCount * 2;
                fa = Arrays.copyOf(fa, n); fb = Arrays.copyOf(fb, n); fc = Arrays.copyOf(fc, n);
                color = Arrays.copyOf(color, n); alpha = Arrays.copyOf(alpha, n);
            }
        }
    }

    /** One frame's shapes of a bucket key as they are drawn, each shape a block of vertices and faces with its rank. */
    private static final class Stage extends Geometry
    {
        /** Per shape: rank, first vertex, vertices, first face, faces. */
        static final int FIELDS = 5;
        /** Ranks are layers, counted into this many slots; anything outside goes to the nearest one. */
        private static final int RANKS = 64;
        int count;
        int[] shapes = new int[FIELDS * 16];
        private int[] order = new int[16];
        private final int[] slots = new int[RANKS + 1];
        /** Per face, the points it adds and the faces it becomes when the player's cut is made, with room to grow (append). */
        int[] cutVertices = new int[96], cutFaces = new int[96];
        /** Per shape, the key of the mark it draws. */
        String[] keys = new String[16];

        Stage(int tileX, int tileY, int level, int worldView) { super(tileX, tileY, level, worldView); }

        @Override void ensure(int vertexCount, int faceCount)
        {
            super.ensure(vertexCount, faceCount);
            if (cutVertices.length < fa.length) { cutVertices = Arrays.copyOf(cutVertices, fa.length); cutFaces = Arrays.copyOf(cutFaces, fa.length); }
        }

        /** Starts a shape of the given layer: what is appended until end() belongs to it. */
        void begin(int rank, String key)
        {
            if (shapes.length < (count + 1) * FIELDS) { shapes = Arrays.copyOf(shapes, shapes.length * 2); }
            if (keys.length <= count) { keys = Arrays.copyOf(keys, keys.length * 2); }
            keys[count] = key;
            int s = count * FIELDS;
            shapes[s] = Math.max(0, Math.min(RANKS - 1, rank));
            shapes[s + 1] = vertices;
            shapes[s + 3] = faces;
        }

        void end()
        {
            int s = count * FIELDS;
            shapes[s + 2] = vertices - shapes[s + 1];
            shapes[s + 4] = faces - shapes[s + 3];
            count++;
        }

        /** Shape indices by rank, in drawn order within a rank (a counting sort: stable, no garbage). */
        int[] byRank()
        {
            if (order.length < count) { order = new int[count * 2]; }
            Arrays.fill(slots, 0);
            for (int k = 0; k < count; k++) { slots[shapes[k * FIELDS] + 1]++; }
            for (int r = 0; r < RANKS; r++) { slots[r + 1] += slots[r]; }
            for (int k = 0; k < count; k++) { order[slots[shapes[k * FIELDS]]++] = k; }
            return order;
        }
    }

    /** All shapes of one tile and level, merged into one scene object. */
    private final class Bucket extends Geometry
    {
        final RuneLiteObjectController object = new RuneLiteObjectController()
        {
            @Override public Model getModel() { return Bucket.this.getModel(); }
        };
        Model model;
        int radius, usedFaces, usedVertices;
        /** Objects after this one with the same bucket key, holding higher ranks (see split). */
        int partsAfter;
        /** The points and faces its shapes take with the player's cut made, and room to grow (see split). */
        int needVertices, needFaces;
        /** The keys of the marks it holds (part of), which fall back to 2D should it not be made. */
        final List<String> keys = new ArrayList<>();

        /**
         * What the renderer may read while HD World Markers writes the next frame: getModel() can run
         * on another thread, so it works on this copy, taken when the frame is written.
         */
        private Model frozenModel;
        private float[] fx = new float[0], fy = fx, fz = fx;
        private float[] fl = new float[0];
        private int frozenVertices, fAnchorX, fAnchorY, fAnchorZ;
        /** The faces written with them (vertex indices, colour, alpha), and the player whose silhouette they leave out. */
        private int[] ffa = new int[0], ffb = ffa, ffc = ffa, fColor = ffa, fAlpha = ffa;
        private int frozenFaces;
        private PlayerSilhouette frozenPlayer;
        /** Scratch for cut: the geometry as seen from a camera, cut around the player; points on the canvas, layers. */
        private final ScreenOutline cutter = new ScreenOutline();
        private float[] sx = new float[0], sy = sx, sd = sx, sl = sx;
        /** The camera of the last pull in getModel; null after freeze (a new frame), so the next call pulls. */
        private ModelShapes.Camera pulled;

        /** Whether the last pull was with this camera; remembers it. */
        private synchronized boolean pulledWith(ModelShapes.Camera cam)
        {
            if (cam.same(pulled)) { return true; }
            pulled = cam;
            return false;
        }

        /** Scratch for pullToCamera, which is synchronized. */
        private final float[] pullPoint = new float[3], pullGround = new float[3], pullReach = new float[2];

        synchronized void freeze(int anchorX, int anchorY, int anchorZ, PlayerSilhouette player)
        {
            if (fx.length < vertices) { fx = new float[wx.length]; fy = new float[wx.length]; fz = new float[wx.length]; fl = new float[wx.length]; }
            System.arraycopy(wx, 0, fx, 0, vertices); System.arraycopy(wy, 0, fy, 0, vertices);
            System.arraycopy(wz, 0, fz, 0, vertices); System.arraycopy(layer, 0, fl, 0, vertices);
            if (ffa.length < faces) { ffa = new int[fa.length]; ffb = new int[fa.length]; ffc = new int[fa.length]; fColor = new int[fa.length]; fAlpha = new int[fa.length]; }
            System.arraycopy(fa, 0, ffa, 0, faces); System.arraycopy(fb, 0, ffb, 0, faces); System.arraycopy(fc, 0, ffc, 0, faces);
            System.arraycopy(color, 0, fColor, 0, faces); System.arraycopy(alpha, 0, fAlpha, 0, faces);
            frozenModel = model; frozenVertices = vertices; frozenFaces = faces; frozenPlayer = player;
            pulled = null;
            fAnchorX = anchorX; fAnchorY = anchorY; fAnchorZ = anchorZ;
        }

        /**
         * Moves every vertex along its view ray to just in front of the camera, keeping its screen position: one depth
         * per layer, higher layers in front (see XRAY_DEPTH). The player's silhouette as seen from this camera is cut out
         * of the faces first, so the player stands in front of the marks.
         */
        synchronized void pullToCamera(ModelShapes.Camera cam)
        {
            Model m = frozenModel;
            if (m == null || frozenVertices > m.getVerticesCount()) { return; }
            // Within what the model holds: should the camera have moved far since the frame was made, a face past that
            // stays whole this frame.
            int n = frozenVertices;
            if (sx.length < n) { sx = new float[n * 2]; sy = new float[n * 2]; sd = new float[n * 2]; }
            float[] q = pullPoint;
            for (int i = 0; i < n; i++) { cam.project(fx[i], fy[i], fz[i], q); sx[i] = q[0]; sy[i] = q[1]; sd[i] = q[2]; }
            ScreenOutline o = cutter;
            o.load(sx, sy, sd, n, ffa, ffb, ffc, frozenFaces);
            PlayerCut around = frozenPlayer == null ? null : frozenPlayer.cut(cam);
            if (around != null) { o.cutOut(around, m.getVerticesCount(), m.getFaceCount()); }
            // The layer of every point: that of the face it was cut from.
            if (sl.length < o.vertices) { sl = new float[o.vertices * 2]; }
            System.arraycopy(fl, 0, sl, 0, frozenVertices);
            for (int f = 0; f < o.faces; f++) { float l = fl[ffa[o.source[f]]]; sl[o.a[f]] = l; sl[o.b[f]] = l; sl[o.c[f]] = l; }
            float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
            float[] p = pullPoint, g = pullGround;
            // One depth per layer for every point, higher layers nearer (see XRAY_DEPTH): the ranking, not the ground,
            // decides. With the anchor far from the camera (no tile near it to hang on, as in a building) the points
            // cannot come that near: then the layers lie farther, where every point's ray is within reach, still one depth
            // each. Stopping each point at its own reach left one layer at many depths, and the renderer's depth sort
            // drew an NPC's marks over and under each other in turn as they moved.
            reach(cam, o, g, pullReach);
            float lo = pullReach[0], hi = pullReach[1];
            float top = lo <= XRAY_DEPTH ? XRAY_DEPTH : (float) Math.floor(lo) + 1.5f, step = XRAY_LAYER_DEPTH;
            // An even whole step keeps every layer, and a white layer half a step up, in the middle of a whole unit.
            if (top + XRAY_TOP_LAYER * step > hi) { step = 2 * (float) Math.floor((hi - top) / XRAY_TOP_LAYER / 2); }
            boolean planes = lo <= hi && step >= 2;
            for (int i = 0; i < o.vertices; i++)
            {
                float bias = sl[i] * 0.25f;
                // The floor keeps the layer order (and the white layer in front) where the depth is clamped.
                float floor = XRAY_MIN_DEPTH + XRAY_LAYER_SPAN - bias;
                float rank = Math.max(0, Math.min(XRAY_TOP_LAYER, sl[i]));
                float at;
                if (planes) { at = Math.max(floor, top + (XRAY_TOP_LAYER - rank) * step); }
                else
                {
                    // No depth within reach of every point (a shape spread wide around a far anchor): each point as near
                    // as its own reach lets it.
                    at = Math.max(floor, withinReach(cam, o.x[i], o.y[i], o.depth[i], g, XRAY_DEPTH + (XRAY_TOP_LAYER - rank) * XRAY_LAYER_DEPTH, bias));
                }
                cam.unproject(o.x[i], o.y[i], at, p);
                vx[i] = p[0] - fAnchorX;
                vy[i] = p[2] - fAnchorZ;
                vz[i] = p[1] - fAnchorY;
            }
            // The GPU plugin skips a see-through model whole if any vertex, used or not, is nearer the camera
            // than 50 (ModelUploader.uploadSortedModel). The anchor, where unused vertices would sit, can be at
            // the camera itself: they sit on the first vertex, which is surely in front of it.
            if (o.vertices > 0)
            {
                for (int i = o.vertices; i < m.getVerticesCount(); i++) { vx[i] = vx[0]; vy[i] = vy[0]; vz[i] = vz[0]; }
            }
            writeFaces(m, o.faces, o.a, o.b, o.c, o.source, fColor, fAlpha);
        }

        /** Writes nf faces (vertex indices, with the colour and alpha of their source face or their own) and hides the rest. */
        void writeFaces(Model m, int nf, int[] a, int[] b, int[] c, int[] source, int[] colors, int[] alphas)
        {
            int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
            for (int f = 0; f < nf; f++)
            {
                int s = source == null ? f : source[f];
                i1[f] = a[f]; i2[f] = b[f]; i3[f] = c[f];
                FlatModel.paint(m, f, colors[s], alphas[s]);
            }
            for (int f = nf; f < usedFaces; f++) { FlatModel.hide(m, f); }
            usedFaces = nf;
        }

        /**
         * The depths at which the rays from the camera through all points of o are within XRAY_REACH of the anchor: out
         * {nearest, farthest}, nearest beyond farthest when no one depth is within reach on every ray.
         */
        private void reach(ModelShapes.Camera cam, ScreenOutline o, float[] g, float[] out)
        {
            float lo = 0, hi = Float.MAX_VALUE;
            double[] span = pullSpan;
            for (int i = 0; i < o.vertices && lo <= hi; i++)
            {
                float dg = o.depth[i];
                if (!(dg > 0) || !ray(cam, o.x[i], o.y[i], dg, g, span)) { lo = Float.MAX_VALUE; break; }
                lo = Math.max(lo, (float) (span[0] * dg));
                hi = Math.min(hi, (float) (span[1] * dg));
            }
            out[0] = lo; out[1] = hi;
        }

        /** Scratch for ray. */
        private final double[] pullSpan = new double[2];

        /**
         * Where the ray from the camera through canvas point (sx, sy), to its ground point at depth dg (g: scratch), is
         * within XRAY_REACH of the anchor: out {enters, leaves} as multiples of dg. False when it never is.
         */
        private boolean ray(ModelShapes.Camera cam, float sx, float sy, float dg, float[] g, double[] out)
        {
            cam.unproject(sx, sy, dg, g);
            // Points on the ray: camera + (g - camera) * s, where s = depth / dg.
            float ux = cam.x - fAnchorX, uy = cam.y - fAnchorY, uz = cam.z - fAnchorZ;
            float vx = g[0] - cam.x, vy = g[1] - cam.y, vz = g[2] - cam.z;
            double a = vx * vx + vy * vy + vz * vz, b = 2.0 * (ux * vx + uy * vy + uz * vz);
            double c = ux * ux + uy * uy + uz * uz - (double) XRAY_REACH * XRAY_REACH, disc = b * b - 4 * a * c;
            if (a <= 0 || disc < 0) { return false; }
            double root = Math.sqrt(disc);
            out[0] = (-b - root) / (2 * a);
            out[1] = (-b + root) / (2 * a);
            return true;
        }

        /**
         * The depth to use on the ray from the camera through canvas point (sx, sy), whose ground point lies at depth dg:
         * the wanted depth, or, when that lies farther than XRAY_REACH from the anchor, the nearest depth within it.
         */
        private float withinReach(ModelShapes.Camera cam, float sx, float sy, float dg, float[] g, float wanted, float bias)
        {
            if (!(dg > 0)) { return wanted; }
            double[] span = pullSpan;
            if (!ray(cam, sx, sy, dg, g, span)) { return dg; }
            double enter = span[0], exit = span[1], s = wanted / dg;
            // A camera inside the reach: far points could be wanted beyond it.
            if (s > exit) { return (float) Math.max(XRAY_MIN_DEPTH, exit * dg - bias); }
            if (s >= enter) { return wanted; }
            // Clamped: keep the layer order among shapes at the same place.
            return (float) Math.min(dg, enter * dg) - bias;
        }

        Bucket(int tileX, int tileY, int level, int worldView) { super(tileX, tileY, level, worldView); }

        void hide() { if (client.isRuneLiteObjectRegistered(object)) { client.removeRuneLiteObject(object); } }

        /**
         * Gives up the model (null without one): a renderer thread still holding this object then finds no model to
         * pull, so it never writes into a model another object took over.
         */
        synchronized Spare release()
        {
            Spare spare = model == null ? null : new Spare(model, radius, usedFaces, usedVertices);
            model = null;
            frozenModel = null;
            return spare;
        }

        synchronized void adopt(Spare s)
        {
            model = s.model; radius = s.radius; usedFaces = s.usedFaces; usedVertices = s.usedVertices;
        }

        Model getModel()
        {
            Model model = this.model;
            if (model == null) { return null; }
            ModelShapes.Camera now = drawCamera();
            // Borders are as wide as wanted for the camera the frame was built with. When the client draws with one
            // that jumped (at login the frame's camera was not set yet, or a teleport), they became a screen-wide
            // flash of colour for a frame: nothing is drawn until the next frame is built with the new camera.
            ModelShapes.Camera built = camera;
            if (built != null && built.jumpedTo(now)) { return null; }
            // Near the camera, a camera that moved since the frame began is off by many pixels:
            // follow the camera the renderer is drawing with right now.
            // The renderer may ask several times per frame (117 HD: shadows, scene): pull again only when the camera
            // moved or the frame was rewritten since the last pull.
            if (xray && !pulledWith(now))
            {
                pullToCamera(now);
                FlatModel.bounds(model);
            }
            return model;
        }
    }
}

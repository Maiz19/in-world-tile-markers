package com.hdworldmarkers;

import java.awt.*;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.*;

/**
 * Labels, and the 2D fallback for every shape that is not in the scene this frame (no GPU, rendering error, other
 * world views, or limits).
 */
final class IndicatorOverlay extends Overlay
{
    private final Client client;
    private final HdWorldMarkersPlugin plugin;
    private final ModelOutlineRenderer outlines;
    private final MarkerSources sources;
    private final HdWorldMarkersConfig config;

    @Inject IndicatorOverlay(Client client, HdWorldMarkersPlugin plugin, ModelOutlineRenderer outlines, MarkerSources sources,
        HdWorldMarkersConfig config)
    {
        this.client = client; this.plugin = plugin; this.outlines = outlines; this.sources = sources; this.config = config;
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(PRIORITY_LOW);
    }

    @Override public Dimension render(Graphics2D graphics)
    {
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            // Once per frame: the scene route works or not (each mark asked it again).
            boolean scene = plugin.sceneActive();
            // Shapes not in the scene in 2D; outlines with RuneLite's outline renderer, once per object (a wall's two
            // parts are one outline), and those in other world views (a boat) always.
            java.util.Set<net.runelite.api.TileObject> outlined = null;
            for (java.util.List<ModelTarget> targets : java.util.Arrays.asList(plugin.modelTargets(), sources.outlinesElsewhere()))
            {
                for (ModelTarget t : targets)
                {
                    if (scene && plugin.drawn(t.key)) { continue; }
                    if (t.outline)
                    {
                        if (t.npc != null) { outlines.drawOutline(t.npc, (int) t.borderWidth, t.color, 0); }
                        else if (t.object != null)
                        {
                            if (outlined == null) { outlined = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); }
                            if (outlined.add(t.object)) { outlines.drawOutline(t.object, (int) t.borderWidth, t.color, 0); }
                        }
                        continue;
                    }
                    Shape shape = t.shape();
                    if (shape != null) { draw(g, shape, t.color, t.fill, t.borderWidth); }
                }
            }
            Marker hover = plugin.hover();
            if (hover != null && !(scene && plugin.drawn(hover.key)))
            {
                Polygon shape = polygon(hover);
                if (shape != null) { draw(g, shape, hover); }
            }
            // Higher layers last, so your own tile and destination end up on top in 2D too.
            // Only 2D tiles need the layer order; in the scene only labels are drawn here.
            java.util.List<Marker> ordered = plugin.tilesIn2d() ? plugin.markersByLayer() : plugin.markers();
            for (Marker m : ordered)
            {
                if (!(scene && plugin.drawn(m.key)))
                {
                    Shape shape = m.dot || m.offX != null ? facing(m) : polygon(m);
                    if (shape != null) { draw(g, shape, m); }
                }
                if (m.label != null && !m.label.isEmpty())
                {
                    // Text is slow to measure and draw: labels of tiles off screen (Tile Packs have many) are left out.
                    Point at = Perspective.localToCanvas(client, m.point, m.plane);
                    Point p = at == null || !onScreen(at) ? null : Perspective.getCanvasTextLocation(client, g, m.point, m.label, 0);
                    if (p != null) { OverlayUtil.renderTextLocation(g, p, m.label, m.color); }
                }
            }
        }
        finally { g.dispose(); }
        return null;
    }

    private static void draw(Graphics2D g, Shape shape, Marker m)
    {
        if (m.cornerDivisor <= 0 || !(shape instanceof Polygon)) { draw(g, shape, m.color, m.fill, m.borderWidth); return; }
        // Corners only, as Corner Tile Indicators' renderPolygonCorners.
        Polygon p = (Polygon) shape;
        if (m.fill.getAlpha() > 0) { g.setColor(m.fill); g.fill(p); }
        if (m.borderWidth <= 0 || m.color.getAlpha() == 0) { return; }
        g.setColor(m.color);
        g.setStroke(new BasicStroke(m.borderWidth));
        for (int i = 0; i < p.npoints; i++)
        {
            int prev = (i + p.npoints - 1) % p.npoints, next = (i + 1) % p.npoints;
            int x = p.xpoints[i], y = p.ypoints[i];
            g.drawLine(x, y, x + (p.xpoints[next] - x) / m.cornerDivisor, y + (p.ypoints[next] - y) / m.cornerDivisor);
            g.drawLine(x, y, x + (p.xpoints[prev] - x) / m.cornerDivisor, y + (p.ypoints[prev] - y) / m.cornerDivisor);
        }
    }

    private static void draw(Graphics2D g, Shape shape, Color color, Color fill, float width)
    {
        if (fill.getAlpha() > 0) { g.setColor(fill); g.fill(shape); }
        if (width > 0 && color.getAlpha() > 0)
        {
            g.setColor(color);
            g.setStroke(new BasicStroke(width));
            g.draw(shape);
        }
    }

    /**
     * A shape around where the tile's centre projects: Path Marker's dot, a small circle, or a timer pie (Marker.offX)
     * in local units, scaled by the camera's distance now as in the scene.
     */
    private Shape facing(Marker m)
    {
        Point p = Perspective.localToCanvas(client, m.point, m.plane, m.lift);
        if (p == null) { return null; }
        float[] ox = m.offX != null ? m.offX : SceneShapeRenderer.DOT_X, oy = m.offX != null ? m.offY : SceneShapeRenderer.DOT_Y;
        float scale = 1;
        if (m.offX != null)
        {
            float[] q = new float[3];
            ModelShapes.Camera camera = ModelShapes.Camera.of(client);
            camera.project(m.point.getX(), m.point.getY(), Perspective.getTileHeight(client, m.point, m.plane) - m.lift, q);
            if (!(q[2] > 0)) { return null; }
            scale = camera.scale / q[2];
        }
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        for (int i = 0; i < ox.length; i++)
        {
            float x = p.getX() + ox[i] * scale, y = p.getY() + oy[i] * scale;
            if (i == 0) { path.moveTo(x, y); } else { path.lineTo(x, y); }
        }
        path.closePath();
        return path;
    }

    /** Whether a label centred on this point can show: within the viewport, or half a label's width or a line from it. */
    private boolean onScreen(Point p)
    {
        int x = client.getViewportXOffset(), y = client.getViewportYOffset();
        return p.getX() > x - 100 && p.getX() < x + client.getViewportWidth() + 100 && p.getY() > y - 20 && p.getY() < y + client.getViewportHeight() + 40;
    }

    /** The footprint's corners on the canvas, or null. */
    private Polygon polygon(Marker m) { return Perspective.getCanvasTileAreaPoly(client, m.point, m.width, m.height, m.plane, 0); }
}

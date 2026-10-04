package com.inworldtilemarkers;

import java.awt.*;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.*;

/**
 * Labels, and the 2D fallback for every shape that is not in the scene this frame (no GPU, rendering error, other
 * world views, or limits).
 */
final class IndicatorOverlay extends Overlay
{
    private final Client client;
    private final InWorldTileMarkersPlugin plugin;
    private final ModelOutlineRenderer outlines;
    private final MarkerSources sources;
    private final InWorldTileMarkersConfig config;

    @Inject IndicatorOverlay(Client client, InWorldTileMarkersPlugin plugin, ModelOutlineRenderer outlines, MarkerSources sources,
        InWorldTileMarkersConfig config)
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
            // Without the scene route, RuneLite's outline renderer draws outlines in 2D.
            java.util.Set<net.runelite.api.TileObject> sceneObjectOutlines = plugin.objectOutlinesInScene();
            for (ObjectMarkerSource.Resolved o : sources.objectOutlines())
            {
                if (!sceneObjectOutlines.contains(o.object))
                { outlines.drawOutline(o.object, (int) o.borderWidth, o.border, 0); }
            }
            for (NPC npc : sources.npcOutlines())
            {
                if (!plugin.markerInScene("npc:" + npc.getIndex() + ":outline"))
                { outlines.drawOutline(npc, (int) config.npcBorderWidth(), sources.npcOutlineColor(npc), 0); }
            }
            for (ModelTarget t : plugin.modelTargets())
            {
                if (plugin.markerInScene(t.key)) { continue; }
                if (t.outline)
                {
                    // Tagged NPCs' outlines are drawn above; other plugins' NPC outlines (ExternalMarks) here.
                    if (t.npc != null && !t.key.startsWith("npc:"))
                    { outlines.drawOutline(t.npc, (int) t.borderWidth, t.color, 0); }
                    continue;
                }
                Shape shape = t.shape();
                if (shape != null) { draw(g, shape, t.color, t.fill, t.borderWidth); }
            }
            Marker hover = plugin.hover();
            if (hover != null && (plugin.hoverIn2d() || !plugin.markerInScene(hover.key)))
            {
                Polygon shape = polygon(hover);
                if (shape != null) { draw(g, shape, hover); }
            }
            // Higher layers last, so your own tile and destination end up on top in 2D too.
            // Only 2D tiles need the layer order; in the scene only labels are drawn here.
            java.util.List<Marker> ordered = plugin.markers();
            if (plugin.tilesIn2d())
            {
                ordered = new java.util.ArrayList<>(ordered);
                ordered.sort(java.util.Comparator.comparingInt(m -> m.layer));
            }
            for (Marker m : ordered)
            {
                if (!plugin.markerInScene(m.key))
                {
                    Shape shape = m.dot ? dot(m) : polygon(m);
                    if (shape != null) { draw(g, shape, m); }
                }
                if (m.label != null && !m.label.isEmpty())
                {
                    Point p = Perspective.getCanvasTextLocation(client, g, m.point, m.label, 0);
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

    /** Path Marker's dot style: a small circle around where the tile's centre projects. */
    private Shape dot(Marker m)
    {
        Point p = Perspective.localToCanvas(client, m.point, m.plane);
        if (p == null) { return null; }
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        for (int i = 0; i < SceneShapeRenderer.DOT_X.length; i++)
        {
            float x = p.getX() + SceneShapeRenderer.DOT_X[i], y = p.getY() + SceneShapeRenderer.DOT_Y[i];
            if (i == 0) { path.moveTo(x, y); } else { path.lineTo(x, y); }
        }
        path.closePath();
        return path;
    }

    /** The footprint's corners on the canvas, or null. */
    private Polygon polygon(Marker m) { return Perspective.getCanvasTileAreaPoly(client, m.point, m.width, m.height, m.plane, 0); }
}

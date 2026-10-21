package com.hdworldmarkers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;

/**
 * Labels as triangles, for Render text in HD: drawn in the scene like the marks, so the GPU rasterizes them at the
 * scene's full resolution rather than RuneLite's 2D text being stretched.
 */
final class SceneText
{
    /** The Text section's options, for the 2D text and the scene alike; made again when they change. */
    static final class Style
    {
        final Font font;
        final HdWorldMarkersConfig.TextEdge edge;
        final Color edgeColor;
        /** The shadow's offset and the outline's width, in canvas pixels: one at RuneLite's size 16. */
        final int edgeWidth;
        /** RuneScape's pixel fonts get square outline corners, as RuneLite's outlined text. */
        final boolean pixel;

        Style(HdWorldMarkersConfig.TextFont font, int size, HdWorldMarkersConfig.TextEdge edge, Color edgeColor)
        {
            this.font = font(font, size);
            this.edge = edge;
            this.edgeColor = edgeColor;
            edgeWidth = Math.max(1, Math.round(size / 16f));
            pixel = font != HdWorldMarkersConfig.TextFont.SMOOTH && font != HdWorldMarkersConfig.TextFont.SMOOTH_BOLD;
        }

        static Style of(HdWorldMarkersConfig config)
        {
            return new Style(config.textFont(), config.textSize(), config.textEdge(), config.textEdgeColor());
        }
    }

    /** The font at a size; the smooth ones a little smaller, as tall and wide as RuneScape's at the same size. */
    static Font font(HdWorldMarkersConfig.TextFont font, int size)
    {
        switch (font)
        {
            case RUNESCAPE_BOLD: return FontManager.getRunescapeBoldFont().deriveFont((float) size);
            case RUNESCAPE_SMALL: return FontManager.getRunescapeSmallFont().deriveFont((float) size);
            case SMOOTH: return new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size * 13f / 16);
            case SMOOTH_BOLD: return new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size * 13f / 16);
            default: return FontManager.getRunescapeFont().deriveFont((float) size);
        }
    }

    /**
     * A glyph's triangles in canvas pixels, and those of its edge (shadow or outline; null for none or where nothing of
     * it shows). The edge leaves out the text and the edges before it: while the camera moved, the renderer's depth
     * sort drew a shadow over the text it lay a few units behind for a moment, so they no longer overlap at all.
     */
    static final class Glyph
    {
        final OutlinePaint.Mesh fill, edge;

        Glyph(OutlinePaint.Mesh fill, OutlinePaint.Mesh edge) { this.fill = fill; this.edge = edge; }
    }

    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    /** How far a curve may lie from its straight pieces, in canvas pixels: a fraction of a screen pixel when stretched. */
    private static final double FLATNESS = 0.05;
    private static final int CACHED = 256;

    private final Map<String, List<Glyph>> glyphs = new LinkedHashMap<String, List<Glyph>>(64, 0.75f, true)
    {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Glyph>> eldest) { return size() > CACHED; }
    };
    private Style made;

    /**
     * The text's glyphs in this style, centred on x = 0 with the baseline at y = 0, as RuneLite places a label; one
     * per glyph, so a long label still fits the scene objects. Made once per text and style.
     */
    List<Glyph> glyphs(String text, Style style)
    {
        if (style != made) { glyphs.clear(); made = style; }
        List<Glyph> known = glyphs.get(text);
        if (known != null) { return known; }
        GlyphVector vector = style.font.createGlyphVector(CONTEXT, text);
        float left = (float) -vector.getLogicalBounds().getWidth() / 2;
        List<Area> shapes = new ArrayList<>(vector.getNumGlyphs());
        Area taken = new Area();
        for (int i = 0; i < vector.getNumGlyphs(); i++)
        {
            Shape shape = vector.getGlyphOutline(i, left, 0);
            if (shape.getBounds2D().isEmpty()) { continue; }
            Area glyph = new Area(flat(shape));
            shapes.add(glyph);
            taken.add(glyph);
        }
        List<Glyph> list = new ArrayList<>(shapes.size());
        for (Area glyph : shapes)
        {
            OutlinePaint.Mesh fill = OutlinePaint.fill(glyph), edge = null;
            if (fill == null) { continue; }
            Area around = null;
            if (style.edge == HdWorldMarkersConfig.TextEdge.SHADOW)
            {
                around = glyph.createTransformedArea(java.awt.geom.AffineTransform.getTranslateInstance(style.edgeWidth, style.edgeWidth));
            }
            else if (style.edge == HdWorldMarkersConfig.TextEdge.OUTLINE)
            {
                // Within the edge's width of the glyph: its outline stroked both ways.
                around = new Area(flat(new BasicStroke(2 * style.edgeWidth, BasicStroke.CAP_ROUND,
                    style.pixel ? BasicStroke.JOIN_MITER : BasicStroke.JOIN_ROUND).createStrokedShape(glyph)));
            }
            if (around != null)
            {
                Area shown = new Area(around);
                shown.subtract(taken);
                taken.add(around);
                edge = OutlinePaint.fill(shown);
            }
            list.add(new Glyph(fill, edge));
        }
        list = Collections.unmodifiableList(list);
        glyphs.put(text, list);
        return list;
    }

    private static Path2D flat(Shape shape)
    {
        Path2D.Double flat = new Path2D.Double(Path2D.WIND_NON_ZERO);
        flat.append(shape.getPathIterator(null, FLATNESS), false);
        return flat;
    }
}

package com.inworldtilemarkers;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * Graphics that hands shape fills and draws to a sink first, with the current colour and stroke; what the
 * sink does not take, and everything else (text, images, other int-based drawing), goes to the delegate; int-based
 * polygons count as shapes. Lets
 * In-World Tile Markers run another plugin's overlay to learn which tiles it draws.
 */
final class CapturingGraphics extends Graphics2D
{
    interface Sink
    {
        /** Whether the fill was taken; otherwise the delegate draws it. */
        boolean fill(Shape shape, Color color);

        /** Whether the outline was taken; otherwise the delegate draws it. */
        boolean draw(Shape shape, Color color, Stroke stroke);

        /** Whether a corners-only border of p was taken (each corner line 1/divisor of its side). */
        default boolean corners(Polygon p, Color color, float width, int divisor) { return false; }
    }

    private final Graphics2D g;
    private final Sink sink;

    CapturingGraphics(Graphics2D delegate, Sink sink) { this.g = delegate; this.sink = sink; }

    // Only plain painting is a highlight: Better NPC Highlight's "draw beneath" clears its own pixels with fills.
    @Override public void fill(Shape s) { flush(s); if (g.getComposite() != AlphaComposite.SrcOver || !sink.fill(s, g.getColor())) { g.fill(s); } }
    @Override public void draw(Shape s) { flush(s); if (!sink.draw(s, g.getColor(), g.getStroke())) { g.draw(s); } }
    @Override public Graphics create() { flush(); return new CapturingGraphics((Graphics2D) g.create(), sink); }
    @Override public void dispose() { flush(); g.dispose(); }

    /**
     * Lines drawn one after another with one plain stroke are held until the next shape: 8 lines from the corners of
     * the 4-point polygon filled or drawn next, along its sides, are that tile's corners-only border. Anything else is
     * drawn as it came.
     */
    private final int[] lines = new int[32];
    private int pending;
    private Color lineColor;
    private Stroke lineStroke;

    @Override public void drawLine(int x1, int y1, int x2, int y2)
    {
        Stroke s = g.getStroke();
        boolean plain = s instanceof BasicStroke && ((BasicStroke) s).getDashArray() == null;
        if (pending == 8 || pending > 0 && (!plain || s != lineStroke || !g.getColor().equals(lineColor))) { flush(); }
        if (!plain) { g.drawLine(x1, y1, x2, y2); return; }
        lineColor = g.getColor();
        lineStroke = s;
        int i = 4 * pending++;
        lines[i] = x1; lines[i + 1] = y1; lines[i + 2] = x2; lines[i + 3] = y2;
    }

    private void flush(Shape next)
    {
        int divisor = pending == 8 && next instanceof Polygon && ((Polygon) next).npoints == 4 ? corners((Polygon) next) : 0;
        if (divisor > 0 && sink.corners((Polygon) next, lineColor, ((BasicStroke) lineStroke).getLineWidth(), divisor)) { pending = 0; }
        else { flush(); }
    }

    private void flush()
    {
        if (pending == 0) { return; }
        Color c = g.getColor();
        Stroke s = g.getStroke();
        g.setColor(lineColor);
        g.setStroke(lineStroke);
        for (int i = 0; i < 4 * pending; i += 4) { g.drawLine(lines[i], lines[i + 1], lines[i + 2], lines[i + 3]); }
        g.setColor(c);
        g.setStroke(s);
        pending = 0;
    }

    /** The divisor when the 8 held lines run from p's corners along its sides (next side, then previous), else 0. */
    private int corners(Polygon p)
    {
        double ratio = 0;
        for (int i = 0; i < 8; i++)
        {
            int c = i / 2, o = i % 2 == 0 ? (c + 1) % 4 : (c + 3) % 4, k = 4 * i;
            int ax = p.xpoints[c], ay = p.ypoints[c], bx = p.xpoints[o] - ax, by = p.ypoints[o] - ay;
            int lx = lines[k + 2] - ax, ly = lines[k + 3] - ay;
            double side = Math.hypot(bx, by);
            if (lines[k] != ax || lines[k + 1] != ay || Math.abs(lx * by - ly * bx) > 1.5 * side || lx * bx + ly * by <= 0) { return 0; }
            ratio += side / Math.max(1, Math.hypot(lx, ly)) / 8;
        }
        return (int) Math.max(2, Math.min(20, Math.round(ratio)));
    }

    @Override public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) { return g.drawImage(img, xform, obs); }
    @Override public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) { g.drawImage(img, op, x, y); }
    @Override public void drawRenderedImage(RenderedImage img, AffineTransform xform) { g.drawRenderedImage(img, xform); }
    @Override public void drawRenderableImage(RenderableImage img, AffineTransform xform) { g.drawRenderableImage(img, xform); }
    @Override public void drawString(String str, int x, int y) { flush(); g.drawString(str, x, y); }
    @Override public void drawString(String str, float x, float y) { flush(); g.drawString(str, x, y); }
    @Override public void drawString(AttributedCharacterIterator it, int x, int y) { g.drawString(it, x, y); }
    @Override public void drawString(AttributedCharacterIterator it, float x, float y) { g.drawString(it, x, y); }
    @Override public void drawGlyphVector(GlyphVector gv, float x, float y) { flush(); g.drawGlyphVector(gv, x, y); }
    @Override public boolean hit(Rectangle rect, Shape s, boolean onStroke) { return g.hit(rect, s, onStroke); }
    @Override public GraphicsConfiguration getDeviceConfiguration() { return g.getDeviceConfiguration(); }
    @Override public void setComposite(Composite comp) { g.setComposite(comp); }
    @Override public void setPaint(Paint paint) { g.setPaint(paint); }
    @Override public void setStroke(Stroke s) { g.setStroke(s); }
    @Override public void setRenderingHint(RenderingHints.Key key, Object value) { g.setRenderingHint(key, value); }
    @Override public Object getRenderingHint(RenderingHints.Key key) { return g.getRenderingHint(key); }
    @Override public void setRenderingHints(Map<?, ?> hints) { g.setRenderingHints(hints); }
    @Override public void addRenderingHints(Map<?, ?> hints) { g.addRenderingHints(hints); }
    @Override public RenderingHints getRenderingHints() { return g.getRenderingHints(); }
    @Override public void translate(int x, int y) { g.translate(x, y); }
    @Override public void translate(double tx, double ty) { g.translate(tx, ty); }
    @Override public void rotate(double theta) { g.rotate(theta); }
    @Override public void rotate(double theta, double x, double y) { g.rotate(theta, x, y); }
    @Override public void scale(double sx, double sy) { g.scale(sx, sy); }
    @Override public void shear(double shx, double shy) { g.shear(shx, shy); }
    @Override public void transform(AffineTransform tx) { g.transform(tx); }
    @Override public void setTransform(AffineTransform tx) { g.setTransform(tx); }
    @Override public AffineTransform getTransform() { return g.getTransform(); }
    @Override public Paint getPaint() { return g.getPaint(); }
    @Override public Composite getComposite() { return g.getComposite(); }
    @Override public void setBackground(Color color) { g.setBackground(color); }
    @Override public Color getBackground() { return g.getBackground(); }
    @Override public Stroke getStroke() { return g.getStroke(); }
    @Override public void clip(Shape s) { g.clip(s); }
    @Override public FontRenderContext getFontRenderContext() { return g.getFontRenderContext(); }
    @Override public Color getColor() { return g.getColor(); }
    @Override public void setColor(Color c) { g.setColor(c); }
    @Override public void setPaintMode() { g.setPaintMode(); }
    @Override public void setXORMode(Color c) { g.setXORMode(c); }
    @Override public Font getFont() { return g.getFont(); }
    @Override public void setFont(Font font) { g.setFont(font); }
    @Override public FontMetrics getFontMetrics(Font f) { return g.getFontMetrics(f); }
    @Override public Rectangle getClipBounds() { return g.getClipBounds(); }
    @Override public void clipRect(int x, int y, int width, int height) { g.clipRect(x, y, width, height); }
    @Override public void setClip(int x, int y, int width, int height) { g.setClip(x, y, width, height); }
    @Override public Shape getClip() { return g.getClip(); }
    @Override public void setClip(Shape clip) { g.setClip(clip); }
    @Override public void copyArea(int x, int y, int width, int height, int dx, int dy) { g.copyArea(x, y, width, height, dx, dy); }
    @Override public void fillRect(int x, int y, int width, int height) { g.fillRect(x, y, width, height); }
    @Override public void clearRect(int x, int y, int width, int height) { g.clearRect(x, y, width, height); }
    @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { g.drawRoundRect(x, y, w, h, aw, ah); }
    @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { g.fillRoundRect(x, y, w, h, aw, ah); }
    // Ovals (timer pies' rims, dots) go to the sink as shapes.
    @Override public void drawOval(int x, int y, int width, int height)
    { flush(); if (!sink.draw(new java.awt.geom.Ellipse2D.Float(x, y, width, height), g.getColor(), g.getStroke())) { g.drawOval(x, y, width, height); } }
    @Override public void fillOval(int x, int y, int width, int height)
    { flush(); if (!sink.fill(new java.awt.geom.Ellipse2D.Float(x, y, width, height), g.getColor())) { g.fillOval(x, y, width, height); } }
    @Override public void drawArc(int x, int y, int w, int h, int start, int arc) { g.drawArc(x, y, w, h, start, arc); }
    @Override public void fillArc(int x, int y, int w, int h, int start, int arc) { g.fillArc(x, y, w, h, start, arc); }
    @Override public void drawPolyline(int[] xs, int[] ys, int n) { g.drawPolyline(xs, ys, n); }
    // Int-based polygons (Graphics.drawPolygon(Polygon) ends here too) go to the sink like shapes.
    @Override public void drawPolygon(int[] xs, int[] ys, int n)
    { Polygon p = new Polygon(xs, ys, n); flush(p); if (!sink.draw(p, g.getColor(), g.getStroke())) { g.drawPolygon(xs, ys, n); } }
    @Override public void fillPolygon(int[] xs, int[] ys, int n)
    { Polygon p = new Polygon(xs, ys, n); flush(p); if (!sink.fill(p, g.getColor())) { g.fillPolygon(xs, ys, n); } }
    @Override public boolean drawImage(Image img, int x, int y, ImageObserver o) { flush(); return g.drawImage(img, x, y, o); }
    @Override public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) { return g.drawImage(img, x, y, w, h, o); }
    @Override public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) { return g.drawImage(img, x, y, bg, o); }
    @Override public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) { return g.drawImage(img, x, y, w, h, bg, o); }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver o)
    { return g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, o); }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver o)
    { return g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bg, o); }
}

package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;

/**
 * Shared draw-kit for the "terminal" (Bloomberg-style) Grand Exchange overlays:
 * an amber-on-black palette, a monospace font, and panel / cell / chip / sparkline
 * / right-align primitives. Promoted from the approved BloombergCardPreview and
 * BloombergDeskPreview mocks so every terminal panel reads as one instrument.
 *
 * IMPORTANT: the terminal uses a Monospaced TrueType font, so any panel that draws
 * with it MUST enable text antialiasing via hints(g). The plugin's other overlays
 * deliberately set TEXT_ANTIALIASING = OFF to keep the RuneScape pixel bitmap font
 * crisp; inheriting that convention makes the mono terminal text look jagged.
 */
final class TerminalKit
{
	static final Color BG      = new Color(0x08, 0x08, 0x0a);
	static final Color PANEL   = new Color(0x0d, 0x0d, 0x10);
	static final Color TITLEBG = new Color(0x16, 0x13, 0x0a);
	static final Color BORDER  = new Color(0x3a, 0x33, 0x1c);
	static final Color AMBER   = new Color(0xe8, 0xb8, 0x4b);
	static final Color AMBERHI = new Color(0xf6, 0xc9, 0x52);
	static final Color LABEL   = new Color(0x93, 0x8b, 0x68);
	static final Color DIM     = new Color(0x59, 0x54, 0x3f);
	static final Color GREEN   = new Color(0x55, 0xc4, 0x6a);
	static final Color RED     = new Color(0xf0, 0x56, 0x3f);
	static final Color GRID    = new Color(0x1c, 0x1b, 0x13);
	static final Color FLASH   = new Color(0x1e, 0x3a, 0x22);

	private TerminalKit() { }

	static Font mono(int s)  { return new Font(Font.MONOSPACED, Font.PLAIN, s); }
	static Font monoB(int s) { return new Font(Font.MONOSPACED, Font.BOLD, s); }

	/** Enable shape + TEXT antialiasing - required for the mono font to read clean. */
	static void hints(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	/** Draw s so its right edge ends at xr (tabular right-align). */
	static void rt(Graphics2D g, String s, int xr, int y)
	{
		g.drawString(s, xr - g.getFontMetrics().stringWidth(s), y);
	}

	/** Panel frame + amber title strip; returns the content-start y. */
	static int panel(Graphics2D g, int x, int y, int w, int h, String title)
	{
		g.setColor(PANEL); g.fillRect(x, y, w, h);
		g.setColor(BORDER); g.setStroke(new BasicStroke(1f)); g.drawRect(x, y, w, h);
		g.setColor(TITLEBG); g.fillRect(x + 1, y + 1, w - 2, 17);
		g.setFont(monoB(10)); g.setColor(AMBERHI); g.drawString(title, x + 8, y + 13);
		g.setColor(GRID); g.drawLine(x + 1, y + 18, x + w - 1, y + 18);
		return y + 32;
	}

	/** Dim uppercase label with a right-aligned value below it. */
	static void cell(Graphics2D g, int x, int w, int y, String label, String value, Color vc)
	{
		g.setFont(mono(9)); g.setColor(LABEL); g.drawString(label, x, y);
		g.setFont(monoB(13)); g.setColor(vc); rt(g, value, x + w, y + 15);
	}

	static void chip(Graphics2D g, int x, int y, String s, Color fg)
	{
		g.setFont(monoB(10));
		final int w = g.getFontMetrics().stringWidth(s) + 10;
		g.setColor(new Color(0x20, 0x1a, 0x10)); g.fillRect(x, y - 10, w, 14);
		g.setColor(fg); g.drawString(s, x + 5, y);
	}

	static void spark(Graphics2D g, int x, int y, int w, int h, int seed, Color c)
	{
		final GeneralPath p = new GeneralPath();
		for (int i = 0; i <= 16; i++)
		{
			final double v = Math.sin((i + seed) / 2.3) * 0.5 + Math.sin((i + seed) / 5.7) * 0.5;
			final int px = x + i * w / 16, py = y + (int) ((0.5 - v / 2) * h);
			if (i == 0) { p.moveTo(px, py); } else { p.lineTo(px, py); }
		}
		g.setStroke(new BasicStroke(1.2f)); g.setColor(c); g.draw(p);
	}

	/** 4,266,644,991 - grouped, for the full cash readout. */
	static String commas(long v) { return String.format("%,d", v); }

	/** 4.27b / 805m / 41.0k - compact gp for tight columns. */
	static String gp(long v)
	{
		final long a = Math.abs(v);
		final String sign = v < 0 ? "-" : "";
		if (a >= 1_000_000_000L) { return sign + trim(a / 1e9) + "b"; }
		if (a >= 1_000_000L)     { return sign + trim(a / 1e6) + "m"; }
		if (a >= 1_000L)         { return sign + trim(a / 1e3) + "k"; }
		return Long.toString(v);
	}

	private static String trim(double d)
	{
		final String s = String.format("%.1f", d);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}
}

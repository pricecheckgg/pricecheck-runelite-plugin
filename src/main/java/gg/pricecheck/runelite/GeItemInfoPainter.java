package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * The GE-anchored item intelligence card: painted to the LEFT of the open
 * Grand Exchange offer interface while an item is selected. A tight window of
 * the traded corridor with YOUR offer drawn on it, the live prints the plugin
 * has observed arriving (each poll where the insta price moved is a print),
 * and the measured read: fill odds at your price, after-tax outcome. Pure
 * painter over plain data so the headless harness renders it; the overlay
 * class feeds it live state and anchors it to the GE widget.
 */
final class GeItemInfoPainter
{
	/** One observed print: an insta price moving between polls. */
	static final class Print
	{
		long ts;          // epoch seconds
		long price;
		boolean buySide;  // true = insta-buy (high) moved, false = insta-sell (low)

		Print(long ts, long price, boolean buySide)
		{
			this.ts = ts;
			this.price = price;
			this.buySide = buySide;
		}
	}

	/** Everything the card needs, as plain data. */
	static final class Context
	{
		String itemName;
		String side;          // "SELL" or "BUY"
		long yourPrice;
		String stateText;     // e.g. "OK +26.2k if it sells"
		Color stateColor;
		ItemChart.Series series;   // tight window, e.g. last 6h
		List<Print> prints;        // newest last
		int fillPct = -1;          // measured odds at your price, -1 unknown
		long netIfFills;           // post-tax outcome of this offer
		long nowTs;
	}

	private static final Color FRAME = new Color(0xe6, 0xc6, 0x67, 62);
	private static final Color RULE = new Color(0xe6, 0xc6, 0x67, 38);
	private static final Color SHADOW = new Color(0, 0, 0, 180);
	private static final Color NAME = new Color(0xde, 0xd8, 0xc8);
	private static final Color CORRIDOR = new Color(255, 255, 255, 22);
	private static final Color CORRIDOR_PAID = new Color(0xe6, 0xc6, 0x67, 58);
	private static final Color LINE_HIGH = new Color(0x5d, 0xf2, 0x9a, 170);
	private static final Color LINE_LOW = new Color(0xf2, 0x6b, 0x6d, 170);
	private static final Color YOURS = new Color(255, 255, 255, 220);

	private static final int W = 284;
	private static final int PAD = 10;
	private static final int CHART_H = 108;
	private static final int PRICE_GUTTER = 56;

	private GeItemInfoPainter()
	{
	}

	static Dimension paint(Graphics2D g, Context c)
	{
		final Font small = net.runelite.client.ui.FontManager.getRunescapeSmallFont();
		g.setFont(small);
		final FontMetrics fm = g.getFontMetrics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		final int lineH = fm.getHeight();
		final int tapeRows = Math.min(c.prints != null ? c.prints.size() : 0, 4);
		final int h = PAD + lineH + 6 + CHART_H + 6 + (tapeRows > 0 ? tapeRows * 13 + 14 : 0) + 2 * lineH + PAD + 2;

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, W - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, W - 1, h - 1, 8, 8);

		// Header: item + live verdict from the advisor.
		int y = PAD + fm.getAscent() - 2;
		shadowed(g, c.itemName, PAD, y, NAME);
		if (c.stateText != null)
		{
			final int tw = fm.stringWidth(c.stateText);
			shadowed(g, c.stateText, W - PAD - tw, y, c.stateColor != null ? c.stateColor : Palette.SUBTLE);
		}
		y += 6;
		g.setColor(RULE);
		g.drawLine(PAD - 2, y, W - PAD + 2, y);
		y += 4;

		// Chart: corridor + your offer line.
		paintChart(g, c, PAD, y, W - 2 * PAD, CHART_H, fm);
		y += CHART_H + 4;

		// Tape: the prints this client has watched arrive, newest first.
		if (tapeRows > 0)
		{
			shadowed(g, "Last trades seen", PAD, y + fm.getAscent(), Palette.SUBTLE);
			y += lineH + 1;
			for (int i = 0; i < tapeRows; i++)
			{
				final Print p = c.prints.get(c.prints.size() - 1 - i);
				final int ty = y + i * 13 + 9;
				// Painted direction triangle: up = someone insta-bought at the
				// high, down = someone insta-sold into the low.
				final Path2D tri = new Path2D.Float();
				if (p.buySide)
				{
					tri.moveTo(PAD + 1, ty);
					tri.lineTo(PAD + 8, ty);
					tri.lineTo(PAD + 4.5, ty - 6);
				}
				else
				{
					tri.moveTo(PAD + 1, ty - 6);
					tri.lineTo(PAD + 8, ty - 6);
					tri.lineTo(PAD + 4.5, ty);
				}
				tri.closePath();
				g.setColor(SHADOW);
				g.translate(1, 1);
				g.fill(tri);
				g.translate(-1, -1);
				g.setColor(p.buySide ? Palette.GREEN : Palette.RED);
				g.fill(tri);
				shadowed(g, Fmt.full(p.price), PAD + 14, ty, NAME);
				final long ago = Math.max(0, c.nowTs - p.ts);
				final String age = ago < 60 ? ago + "s ago" : (ago / 60) + "m ago";
				final String delta = c.yourPrice > 0
					? (p.price >= c.yourPrice ? "+" : "-") + Fmt.compact(Math.abs(p.price - c.yourPrice)) + " vs you"
					: "";
				shadowed(g, delta, W / 2 - 10, ty, Palette.SUBTLE);
				final int aw = fm.stringWidth(age);
				shadowed(g, age, W - PAD - aw, ty, Palette.SUBTLE);
			}
			y += tapeRows * 13 + 2;
		}

		// The measured read, in plain words.
		if (c.fillPct >= 0)
		{
			shadowed(g, "Offers at yours filled " + c.fillPct + "% of recent 4h windows", PAD, y + fm.getAscent(), Palette.SUBTLE);
		}
		y += lineH;
		final String outcome = ("SELL".equals(c.side) ? "If it sells: " : "If it fills: ")
			+ (c.netIfFills >= 0 ? "+" : "") + Fmt.compact(c.netIfFills) + " after tax";
		shadowed(g, outcome, PAD, y + fm.getAscent(), c.netIfFills >= 0 ? Palette.GREEN : Palette.RED);

		return new Dimension(W, h);
	}

	private static void paintChart(Graphics2D g, Context c, int x0, int y0, int cw, int ch, FontMetrics fm)
	{
		final ItemChart.Series d = c.series;
		if (d == null || d.ts == null || d.ts.length < 2)
		{
			shadowed(g, "No trade history yet", x0 + 4, y0 + ch / 2, Palette.SUBTLE);
			return;
		}
		final int n = d.ts.length;
		final int plotW = cw - PRICE_GUTTER;

		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (int i = 0; i < n; i++)
		{
			if (d.low[i] > 0) { min = Math.min(min, d.low[i]); }
			if (d.high[i] > 0) { max = Math.max(max, d.high[i]); }
		}
		if (c.yourPrice > 0) { min = Math.min(min, c.yourPrice); max = Math.max(max, c.yourPrice); }
		if (min >= max) { min = max - 1; }
		final long span = max - min;
		min -= span / 10;
		max += span / 10;

		final float dx = plotW / (float) (n - 1);

		for (int i = 0; i < n - 1; i++)
		{
			if (d.high[i] <= 0 || d.low[i] <= 0 || d.high[i + 1] <= 0 || d.low[i + 1] <= 0)
			{
				continue;
			}
			final Path2D p = new Path2D.Float();
			p.moveTo(x0 + i * dx, y(d.high[i], min, max, y0, ch));
			p.lineTo(x0 + (i + 1) * dx, y(d.high[i + 1], min, max, y0, ch));
			p.lineTo(x0 + (i + 1) * dx, y(d.low[i + 1], min, max, y0, ch));
			p.lineTo(x0 + i * dx, y(d.low[i], min, max, y0, ch));
			p.closePath();
			g.setColor(GeTax.net(d.low[i] + 1, d.high[i] - 1) > 0 ? CORRIDOR_PAID : CORRIDOR);
			g.fill(p);
		}
		g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		line(g, d.high, LINE_HIGH, x0, y0, ch, dx, min, max);
		line(g, d.low, LINE_LOW, x0, y0, ch, dx, min, max);

		// Your offer: the one line that matters, labeled with the exact number.
		if (c.yourPrice > 0)
		{
			final int yy = Math.round(y(c.yourPrice, min, max, y0, ch));
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
			g.setColor(YOURS);
			g.drawLine(x0, yy, x0 + plotW, yy);
			g.setStroke(new BasicStroke(1f));
			shadowed(g, "you " + Fmt.compact(c.yourPrice), x0 + plotW + 3, yy + 4, Color.WHITE);
		}
	}

	private static void line(Graphics2D g, long[] v, Color col, int x0, int y0, int ch, float dx, long min, long max)
	{
		g.setColor(col);
		Path2D p = null;
		for (int i = 0; i < v.length; i++)
		{
			if (v[i] <= 0)
			{
				if (p != null) { g.draw(p); p = null; }
				continue;
			}
			final float x = x0 + i * dx;
			final float yy = y(v[i], min, max, y0, ch);
			if (p == null) { p = new Path2D.Float(); p.moveTo(x, yy); }
			else { p.lineTo(x, yy); }
		}
		if (p != null) { g.draw(p); }
	}

	private static float y(long v, long min, long max, int y0, int ch)
	{
		return y0 + ch - ((v - min) / (float) (max - min)) * ch;
	}

	private static void shadowed(Graphics2D g, String s, int x, int yy, Color c)
	{
		g.setColor(SHADOW);
		g.drawString(s, x + 1, yy + 1);
		g.setColor(c);
		g.drawString(s, x, yy);
	}
}

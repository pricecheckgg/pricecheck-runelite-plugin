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
		long[] youBuys = {};   // your active buy offer prices
		long[] youSells = {};  // your active sell offer prices
		String stateText;      // e.g. "B OK +26.2k"
		Color stateColor;
		String stateText2;     // second verdict when both sides are live
		Color stateColor2;
		ItemChart.Series series;
		List<Print> prints;        // newest last
		int fillPct = -1;          // measured odds at the board's prices, -1 unknown
		String outcomeText;        // bottom line, prebuilt by the caller
		Color outcomeColor;
		long nowTs;

		long refSell()
		{
			return youSells.length > 0 ? youSells[0] : 0;
		}

		long refBuy()
		{
			return youBuys.length > 0 ? youBuys[0] : 0;
		}
	}

	private static final Color FRAME = new Color(0xe6, 0xc6, 0x67, 62);
	private static final Color RULE = new Color(0xe6, 0xc6, 0x67, 38);
	private static final Color SHADOW = new Color(0, 0, 0, 180);
	private static final Color NAME = new Color(0xde, 0xd8, 0xc8);
	private static final Color YOURS = new Color(255, 255, 255, 220);

	private static final int W = 284;
	private static final int PAD = 10;
	private static final int CHART_H = 108;
	private static final int PRICE_GUTTER = 66;

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

		// Header: item + live verdicts from the advisor.
		int y = PAD + fm.getAscent() - 2;
		shadowed(g, c.itemName, PAD, y, NAME);
		paintVerdicts(g, c, fm, W, y);
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
				// Deltas read against the side the print competes with: buys
				// arriving compare to your sell, sells to your buy.
				final long ref = p.buySide ? (c.refSell() > 0 ? c.refSell() : c.refBuy())
					: (c.refBuy() > 0 ? c.refBuy() : c.refSell());
				final String delta = ref > 0
					? (p.price >= ref ? "+" : "-") + Fmt.compact(Math.abs(p.price - ref)) + " vs you"
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
			shadowed(g, "Board prices filled " + c.fillPct + "% of recent 4h windows", PAD, y + fm.getAscent(), Palette.SUBTLE);
		}
		y += lineH;
		if (c.outcomeText != null)
		{
			shadowed(g, c.outcomeText, PAD, y + fm.getAscent(), c.outcomeColor != null ? c.outcomeColor : Palette.SUBTLE);
		}

		return new Dimension(W, h);
	}

	private static void paintVerdicts(Graphics2D g, Context c, FontMetrics fm, int width, int y)
	{
		int right = width - PAD;
		if (c.stateText2 != null)
		{
			right -= fm.stringWidth(c.stateText2);
			shadowed(g, c.stateText2, right, y, c.stateColor2 != null ? c.stateColor2 : Palette.SUBTLE);
			right -= fm.stringWidth(" · ");
			shadowed(g, " · ", right, y, Palette.SUBTLE);
		}
		if (c.stateText != null)
		{
			right -= fm.stringWidth(c.stateText);
			shadowed(g, c.stateText, right, y, c.stateColor != null ? c.stateColor : Palette.SUBTLE);
		}
	}

	/** The compact stack card for the GE grid view: header + chart, nothing
	 * else. Both of your offer sides draw on the one chart. */
	static Dimension paintCompact(Graphics2D g, Context c)
	{
		final Font small = net.runelite.client.ui.FontManager.getRunescapeSmallFont();
		g.setFont(small);
		final FontMetrics fm = g.getFontMetrics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		final int lineH = fm.getHeight();
		final boolean chartless = c.series == null;
		final int chartH = chartless ? 0 : 68;
		final int h = 7 + lineH + (chartless ? 5 : 4 + chartH + 8);

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, W - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, W - 1, h - 1, 8, 8);

		final int y = 7 + fm.getAscent() - 2;
		shadowed(g, c.itemName, PAD, y, NAME);
		paintVerdicts(g, c, fm, W, y);
		if (!chartless)
		{
			paintChart(g, c, PAD, 7 + lineH + 2, W - 2 * PAD, chartH + 6, fm);
		}
		return new Dimension(W, h);
	}

	private static void paintChart(Graphics2D g, Context c, int x0, int y0, int cw, int ch, FontMetrics fm)
	{
		final int plotW = cw - PRICE_GUTTER;
		final int plotH = ch - 8;   // room for the volume strip below
		final long[] scalePrices = new long[c.youBuys.length + c.youSells.length];
		System.arraycopy(c.youBuys, 0, scalePrices, 0, c.youBuys.length);
		System.arraycopy(c.youSells, 0, scalePrices, c.youBuys.length, c.youSells.length);
		final ChartKit.Display d = ChartKit.build(c.series, plotW, scalePrices);
		if (d == null)
		{
			shadowed(g, "No trade history yet", x0 + 4, y0 + ch / 2, Palette.SUBTLE);
			return;
		}

		ChartKit.paintCorridor(g, d, x0, y0, plotW, plotH);
		ChartKit.paintFillStrip(g, d, x0, y0 + plotH + 2, plotW, 5);

		// Your offers: labeled with the exact numbers. When both sides ride
		// the same chart, the label says which is which.
		// Every one of your offers gets a tag. Accent colors follow the
		// corridor edge each side competes on: sells against the green
		// insta-buy edge, buys against the red insta-sell edge, and the
		// chip's side letter says it in words. Tags sweep apart when they
		// would overlap.
		final FontMetrics fm2 = g.getFontMetrics();
		final int chipH = fm2.getHeight() + 2;
		final java.util.List<int[]> tags = new java.util.ArrayList<>();   // {lineY, tagY, isSell, priceIdx}
		final java.util.List<Long> prices = new java.util.ArrayList<>();
		for (final long p : c.youSells)
		{
			if (p > 0)
			{
				tags.add(new int[]{Math.round(ChartKit.y(d, p, y0, plotH)), 0, 1, prices.size()});
				prices.add(p);
			}
		}
		for (final long p : c.youBuys)
		{
			if (p > 0)
			{
				tags.add(new int[]{Math.round(ChartKit.y(d, p, y0, plotH)), 0, 0, prices.size()});
				prices.add(p);
			}
		}
		// Sweep top-down: each tag sits at its line unless the one above
		// pushes it.
		tags.sort((a, b) -> Integer.compare(a[0], b[0]));
		int prevBottom = Integer.MIN_VALUE;
		for (final int[] t : tags)
		{
			t[1] = Math.max(t[0], prevBottom + chipH / 2 + 1);
			prevBottom = t[1] + chipH / 2 + 1;
		}

		// The last print on each side: a dot on the series end and its price
		// in the gutter, the live edges you adjust an offer against. They
		// shift out of the way of your chips; grid labels yield to both.
		final java.util.List<int[]> occupied = new java.util.ArrayList<>();
		for (final int[] t : tags)
		{
			occupied.add(new int[]{t[1] - chipH / 2 - 1, t[1] + chipH / 2 + 1});
		}
		paintLastPrint(g, d, true, x0, y0, plotW, plotH, fm2, occupied);
		paintLastPrint(g, d, false, x0, y0, plotW, plotH, fm2, occupied);

		// Price gridlines, labels only where the gutter is free.
		for (int i = 0; i < 3; i++)
		{
			final long p = d.pMin + (d.pMax - d.pMin) * i / 2;
			final int yy = Math.round(ChartKit.y(d, p, y0, plotH));
			g.setColor(ChartKit.GRID);
			g.drawLine(x0, yy, x0 + plotW, yy);
			if (isFree(occupied, yy - 5, yy + 5))
			{
				g.setColor(Palette.SUBTLE_CANVAS);
				g.drawString(Fmt.compact(p), x0 + plotW + 6,
					Math.max(y0 + fm2.getAscent() - 2, yy + 4));
			}
		}

		for (final int[] t : tags)
		{
			yourLine(g, prices.get(t[3]), t[0], t[1], t[2] == 1, x0, plotW, fm2, chipH);
		}
	}

	private static void paintLastPrint(Graphics2D g, ChartKit.Display d, boolean high, int x0, int y0, int plotW, int plotH, FontMetrics fm, java.util.List<int[]> occupied)
	{
		final long price = high ? d.lastHigh : d.lastLow;
		final long ts = high ? d.lastHighTs : d.lastLowTs;
		if (price <= 0)
		{
			return;
		}
		final Color col = high ? Palette.GREEN : Palette.RED;
		final int px = Math.round(ChartKit.x(d, ts, x0, plotW));
		final int py = Math.round(ChartKit.y(d, price, y0, plotH));
		g.setColor(SHADOW);
		g.fillOval(px - 2, py - 2, 6, 6);
		g.setColor(col);
		g.fillOval(px - 3, py - 3, 6, 6);

		// Gutter label: nudge downward until clear of the chips, flipping
		// upward when that would leave the plot, and never past its edges.
		final int lh = fm.getHeight();
		final int maxY = y0 + plotH + 2;
		final int minY = y0 + fm.getAscent() - 2;
		int ly = py + 4;
		int guard = 0;
		while (!isFree(occupied, ly - fm.getAscent(), ly + 2) && guard++ < 8)
		{
			ly += lh / 2 + 2;
			if (ly > maxY)
			{
				ly = py - lh / 2;
				break;
			}
		}
		guard = 0;
		while (!isFree(occupied, ly - fm.getAscent(), ly + 2) && guard++ < 8)
		{
			ly -= lh / 2 + 2;
		}
		ly = Math.max(minY, Math.min(ly, maxY));
		occupied.add(new int[]{ly - fm.getAscent(), ly + 2});
		shadowed(g, Fmt.compact(price), x0 + plotW + 6, ly, col);
	}

	private static boolean isFree(java.util.List<int[]> occupied, int top, int bottom)
	{
		for (final int[] o : occupied)
		{
			if (top <= o[1] && bottom >= o[0])
			{
				return false;
			}
		}
		return true;
	}

	/** Your price as a terminal-style axis tag: soft glow under a dashed
	 * line, and an ink chip with a side letter and a caret at the level. */
	private static void yourLine(Graphics2D g, long price, int lineY, int tagY, boolean sell, int x0, int plotW, FontMetrics fm, int chipH)
	{
		final Color accent = sell ? Palette.GREEN : Palette.RED;
		g.setStroke(new BasicStroke(3f));
		g.setColor(new Color(255, 255, 255, 34));
		g.drawLine(x0, lineY, x0 + plotW, lineY);
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
		g.setColor(YOURS);
		g.drawLine(x0, lineY, x0 + plotW, lineY);
		g.setStroke(new BasicStroke(1f));

		final String letter = sell ? "S" : "B";
		final String label = Fmt.compact(price);
		final int lw = fm.stringWidth(letter);
		final int tw = fm.stringWidth(label);
		final int chipX = x0 + plotW + 6;
		final int chipY = tagY - chipH / 2;
		final Path2D caret = new Path2D.Float();
		caret.moveTo(x0 + plotW + 1, lineY);
		caret.lineTo(chipX + 1, tagY - 4);
		caret.lineTo(chipX + 1, tagY + 4);
		caret.closePath();
		g.setColor(accent);
		g.fill(caret);
		g.setColor(Palette.INK);
		g.fillRoundRect(chipX, chipY, lw + tw + 12, chipH, 6, 6);
		g.setColor(accent);
		g.drawRoundRect(chipX, chipY, lw + tw + 12, chipH, 6, 6);
		g.drawString(letter, chipX + 5, chipY + fm.getAscent());
		g.setColor(Color.WHITE);
		g.drawString(label, chipX + 5 + lw + 3, chipY + fm.getAscent());
	}

	private static void shadowed(Graphics2D g, String s, int x, int yy, Color c)
	{
		g.setColor(SHADOW);
		g.drawString(s, x + 1, yy + 1);
		g.setColor(c);
		g.drawString(s, x, yy);
	}
}

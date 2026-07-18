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
		boolean yours;    // matched to one of your own logged fills
		boolean yoursBuy; // the matched fill's side (your sell offer being taken
		                  // surfaces as an insta-buy, so this is not buySide)

		Print(long ts, long price, boolean buySide)
		{
			this(ts, price, buySide, false, false);
		}

		Print(long ts, long price, boolean buySide, boolean yours, boolean yoursBuy)
		{
			this.ts = ts;
			this.price = price;
			this.buySide = buySide;
			this.yours = yours;
			this.yoursBuy = yoursBuy;
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
		// Your open position from the flip log: what you hold and paid.
		long lotQty;
		long lotCost;              // total gp paid
		long lotOpenedAtMs;

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
		final boolean holding = c.lotQty > 0;
		final int h = PAD + lineH + 6 + CHART_H + 6 + (tapeRows > 0 ? tapeRows * 13 + 14 : 0)
			+ (holding ? lineH : 0) + 2 * lineH + PAD + 2;

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, W - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, W - 1, h - 1, 8, 8);

		// Header: verdicts right-aligned first, then the name clipped to the
		// space that remains so the two can never overprint.
		int y = PAD + fm.getAscent() - 2;
		final int nameEnd = paintVerdicts(g, c, fm, W, y);
		shadowed(g, clip(c.itemName, fm, nameEnd - 6 - PAD), PAD, y, NAME);
		y += 6;
		g.setColor(RULE);
		g.drawLine(PAD - 2, y, W - PAD + 2, y);
		y += 4;

		// Chart: corridor + your offer line.
		paintChart(g, c, PAD, y, W - 2 * PAD, CHART_H, fm, false);
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
				shadowed(g, Fmt.full(p.price), PAD + 14, ty, p.yours ? Palette.GOLD : NAME);
				final long ago = Math.max(0, c.nowTs - p.ts);
				final String age = ago < 60 ? ago + "s ago"
					: ago < 5400 ? (ago / 60) + "m ago" : (ago / 3600) + "h ago";
				// Your own fill gets named as such; everything else reads against
				// the side it competes with: buys arriving compare to your sell,
				// sells to your buy.
				String delta;
				Color deltaCol = Palette.SUBTLE;
				if (p.yours)
				{
					delta = p.yoursBuy ? "your buy" : "your sell";
					deltaCol = Palette.GOLD;
				}
				else
				{
					final long ref = p.buySide ? (c.refSell() > 0 ? c.refSell() : c.refBuy())
						: (c.refBuy() > 0 ? c.refBuy() : c.refSell());
					delta = ref > 0
						? (p.price >= ref ? "+" : "-") + Fmt.compact(Math.abs(p.price - ref)) + " vs you"
						: "";
				}
				shadowed(g, delta, W / 2 - 10, ty, deltaCol);
				final int aw = fm.stringWidth(age);
				shadowed(g, age, W - PAD - aw, ty, Palette.SUBTLE);
			}
			y += tapeRows * 13 + 2;
		}

		// Your position, priced at the live edge.
		if (holding)
		{
			final long unit = c.lotCost / c.lotQty;
			String hold = "Holding " + Fmt.full(c.lotQty) + " @ " + Fmt.compact(unit);
			Color holdCol = Palette.SUBTLE;
			final long edge = lastHighOf(c.series);
			if (edge > 0)
			{
				final long pnl = c.lotQty * GeTax.net(unit, edge);
				hold += " · " + (pnl >= 0 ? "+" : "") + Fmt.compact(pnl) + " after tax at the bid";
				holdCol = pnl >= 0 ? Palette.GREEN : Palette.RED;
			}
			shadowed(g, hold, PAD, y + fm.getAscent(), holdCol);
			y += lineH;
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

	/** Paints the verdicts right-aligned and returns the leftmost x they
	 *  reached, so the caller can clip the item name against it. */
	private static int paintVerdicts(Graphics2D g, Context c, FontMetrics fm, int width, int y)
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
		return right;
	}

	/** Trim to fit, with a plain two-dot tail (the client font has no ellipsis glyph). */
	private static String clip(String s, FontMetrics fm, int maxW)
	{
		if (s == null)
		{
			return "";
		}
		if (fm.stringWidth(s) <= maxW)
		{
			return s;
		}
		String t = s;
		while (t.length() > 1 && fm.stringWidth(t + "..") > maxW)
		{
			t = t.substring(0, t.length() - 1);
		}
		return t + "..";
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
		final int nameEnd = paintVerdicts(g, c, fm, W, y);
		shadowed(g, clip(c.itemName, fm, nameEnd - 6 - PAD), PAD, y, NAME);
		if (!chartless)
		{
			paintChart(g, c, PAD, 7 + lineH + 2, W - 2 * PAD, chartH + 6, fm, true);
		}
		return new Dimension(W, h);
	}

	private static void paintChart(Graphics2D g, Context c, int x0, int y0, int cw, int ch, FontMetrics fm, boolean compact)
	{
		final int plotW = cw - PRICE_GUTTER;
		final int plotH = ch - 8;   // room for the volume strip below
		final long lotUnit = c.lotQty > 0 ? c.lotCost / c.lotQty : 0;
		final long[] scalePrices = new long[c.youBuys.length + c.youSells.length + 1];
		System.arraycopy(c.youBuys, 0, scalePrices, 0, c.youBuys.length);
		System.arraycopy(c.youSells, 0, scalePrices, c.youBuys.length, c.youSells.length);
		scalePrices[scalePrices.length - 1] = lotUnit;
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
		// Your open position: the cost basis as a quiet dotted line on the
		// plot with a diamond at the entry, warm gold when the live edge
		// covers your breakeven after tax, dim red when underwater. No
		// gutter label; position and colour carry it.
		if (lotUnit > 0)
		{
			final boolean covered = d.lastHigh > 0 && d.lastHigh >= GeTax.breakevenSell(lotUnit);
			final Color basis = covered
				? new Color(0xe6, 0xc6, 0x67, 130)
				: new Color(0xf2, 0x6b, 0x6d, 100);
			final int by = Math.round(ChartKit.y(d, lotUnit, y0, plotH));
			final long entryTs = c.lotOpenedAtMs / 1000L;
			final int bx = entryTs <= d.tMin ? x0
				: Math.round(ChartKit.x(d, Math.min(entryTs, d.tMax), x0, plotW));
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 4f}, 0f));
			g.setColor(basis);
			g.drawLine(bx, by, x0 + plotW, by);
			g.setStroke(new BasicStroke(1f));
			final Path2D diamond = new Path2D.Float();
			diamond.moveTo(bx, by - 4);
			diamond.lineTo(bx + 4, by);
			diamond.lineTo(bx, by + 4);
			diamond.lineTo(bx - 4, by);
			diamond.closePath();
			g.setColor(SHADOW);
			g.translate(1, 1);
			g.fill(diamond);
			g.translate(-1, -1);
			g.setColor(basis);
			g.fill(diamond);
		}

		// The last trades from the tape, placed on the chart where they
		// happened: up = someone insta-bought, down = someone insta-sold,
		// gold = your own fill. The tape says what and when; these show WHERE
		// each trade landed against your lines.
		if (!compact && c.prints != null && !c.prints.isEmpty())
		{
			final int from = Math.max(0, c.prints.size() - 10);
			for (int i = from; i < c.prints.size(); i++)
			{
				final Print p = c.prints.get(i);
				if (p.price <= 0)
				{
					continue;
				}
				final int py = Math.round(ChartKit.y(d, p.price, y0, plotH));
				if (py < y0 + 2 || py > y0 + plotH - 2)
				{
					continue;   // outside the robust scale: an outlier print
				}
				final long ts = Math.max(d.tMin, Math.min(p.ts, d.tMax));
				final int px = Math.max(x0 + 3, Math.min(Math.round(ChartKit.x(d, ts, x0, plotW)), x0 + plotW - 3));
				final int r = p.yours ? 4 : 3;
				final Path2D tri = new Path2D.Float();
				if (p.buySide)
				{
					tri.moveTo(px - r, py + r - 1);
					tri.lineTo(px + r, py + r - 1);
					tri.lineTo(px, py - r);
				}
				else
				{
					tri.moveTo(px - r, py - r + 1);
					tri.lineTo(px + r, py - r + 1);
					tri.lineTo(px, py + r);
				}
				tri.closePath();
				g.setColor(SHADOW);
				g.translate(1, 1);
				g.fill(tri);
				g.translate(-1, -1);
				g.setColor(p.yours ? Palette.GOLD : (p.buySide ? Palette.GREEN : Palette.RED));
				g.fill(tri);
			}
		}

		paintLastPrint(g, d, true, x0, y0, plotW, plotH, fm2, occupied);
		paintLastPrint(g, d, false, x0, y0, plotW, plotH, fm2, occupied);

		// Price gridlines. Their labels are the lowest tier: full cards show
		// them where the gutter is free, minis skip them entirely so the
		// numbers that matter keep their air.
		for (int i = 0; i < 3; i++)
		{
			final long p = d.pMin + (d.pMax - d.pMin) * i / 2;
			final int yy = Math.round(ChartKit.y(d, p, y0, plotH));
			g.setColor(ChartKit.GRID);
			g.drawLine(x0, yy, x0 + plotW, yy);
			if (!compact && isFree(occupied, yy - 5, yy + 5))
			{
				g.setColor(Palette.SUBTLE_CANVAS);
				g.drawString(Fmt.compact(p), x0 + plotW + 6,
					Math.max(y0 + fm2.getAscent() - 2, yy + 4));
			}
		}

		for (final int[] t : tags)
		{
			final long price = prices.get(t[3]);
			final boolean sellSide = t[2] == 1;
			// Seated = your price already competes at the live edge: a sell
			// at or under the last insta-buy, a buy at or over the last
			// insta-sell. Anything else is off-market and worth adjusting.
			final boolean seated = sellSide
				? d.lastHigh > 0 && price <= d.lastHigh
				: d.lastLow > 0 && price >= d.lastLow;
			yourLine(g, price, t[0], t[1], sellSide, seated, x0, plotW, fm2, chipH);
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
		// upward when that would leave the plot. A label that cannot find a
		// free spot is DROPPED, never drawn over a neighbour; the dot on the
		// series still marks the level.
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
			if (ly < minY)
			{
				return;
			}
		}
		if (ly < minY || ly > maxY || !isFree(occupied, ly - fm.getAscent(), ly + 2))
		{
			return;
		}
		occupied.add(new int[]{ly - fm.getAscent(), ly + 2});
		shadowed(g, Fmt.compact(price), x0 + plotW + 6, ly, col);
	}

	private static long lastHighOf(ItemChart.Series s)
	{
		if (s == null || s.high == null)
		{
			return 0;
		}
		for (int i = s.high.length - 1; i >= 0; i--)
		{
			if (s.high[i] > 0)
			{
				return s.high[i];
			}
		}
		return 0;
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
	 * line, and an ink chip with a side letter and a caret at the level.
	 * Seated offers (already competing at the live edge) wear a solid border
	 * and a side-coloured caret; off-market ones go dashed with an amber
	 * caret. */
	private static void yourLine(Graphics2D g, long price, int lineY, int tagY, boolean sell, boolean seated, int x0, int plotW, FontMetrics fm, int chipH)
	{
		final Color accent = sell ? Palette.GREEN : Palette.RED;
		final Color caretCol = seated ? accent : Palette.AMBER;
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
		g.setColor(caretCol);
		g.fill(caret);
		g.setColor(Palette.INK);
		g.fillRoundRect(chipX, chipY, lw + tw + 12, chipH, 6, 6);
		g.setColor(accent);
		if (!seated)
		{
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f));
		}
		g.drawRoundRect(chipX, chipY, lw + tw + 12, chipH, 6, 6);
		g.setStroke(new BasicStroke(1f));
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

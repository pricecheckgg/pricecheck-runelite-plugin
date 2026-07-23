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
		String chartLabel;         // active timeframe tag drawn on the chart, e.g. "24h"
		List<Print> prints;        // newest last
		int tradeDepth = 10;       // how many recent prints the tape shows
		int tradesChartN = 0;      // >0: draw the last-N-trades tick chart instead of the corridor
		// Low/high (with timestamps) for the selected chart view, shown as the range
		// read: {highPrice, highTsSec, lowPrice, lowTsSec}. Null = not shown. The
		// view label reuses chartLabel (+ tradesChartN>0 for a trades view).
		long[] rangeRow;
		int fillPct = -1;          // measured odds at the board's prices, -1 unknown
		String outcomeText;        // bottom line, prebuilt by the caller
		Color outcomeColor;
		String outcomeText2;       // whole-offer line under it (qty math)
		Color outcomeColor2;
		long nowTs;
		// Your open position from the flip log: what you hold and paid.
		long lotQty;
		long lotCost;              // total gp paid
		long lotOpenedAtMs;
		// The individual lots behind those totals, oldest first: {qty, unit
		// cost, openedAt ms}. When they differ in price the holding line
		// itemises them instead of pretending one blended entry.
		long[][] lotEntries;

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

	static final int W = 284;          // compact cards and the grid column module
	static final int W_FULL = 344;     // the expanded evidence card
	private static final int PAD = 10;
	private static final int CHART_H = 148;
	private static final int PRICE_GUTTER = 66;

	private GeItemInfoPainter()
	{
	}

	// ── Terminal (Bloomberg) card ──────────────────────────────────
	// The approved amber-on-black DES card, fed by the same Context. Classic
	// paint() below is untouched; the overlay routes here when config.terminalCard()
	// is on. Grid values are derived from the Context; LIMIT/RESET show "-" until
	// the buy-limit tracker (Phase 2) feeds them.
	static final int TERM_W = 430;

	private static long seriesHi(ItemChart.Series s)
	{
		if (s == null || s.high == null) { return 0; }
		long m = 0;
		for (final long v : s.high) { if (v > m) { m = v; } }
		return m;
	}

	private static long seriesLo(ItemChart.Series s)
	{
		if (s == null || s.low == null) { return 0; }
		long m = Long.MAX_VALUE;
		for (final long v : s.low) { if (v > 0 && v < m) { m = v; } }
		return m == Long.MAX_VALUE ? 0 : m;
	}

	private static long seriesVol(ItemChart.Series s)
	{
		if (s == null) { return 0; }
		long v = 0;
		if (s.hvol != null) { for (final int x : s.hvol) { v += x; } }
		if (s.lvol != null) { for (final int x : s.lvol) { v += x; } }
		return v;
	}

	private static long lastLowOf(ItemChart.Series s)
	{
		if (s == null || s.low == null) { return 0; }
		for (int i = s.low.length - 1; i >= 0; i--) { if (s.low[i] > 0) { return s.low[i]; } }
		return 0;
	}

	/** mid-price % change from `back` windows ago to the latest window. */
	private static double seriesDelta(ItemChart.Series s, int back)
	{
		if (s == null || s.high == null || s.low == null || s.high.length == 0) { return 0; }
		final int n = s.high.length;
		final int i0 = Math.max(0, n - 1 - Math.max(1, back));
		final double now = (s.high[n - 1] + s.low[n - 1]) / 2.0;
		final double then = (s.high[i0] + s.low[i0]) / 2.0;
		return then > 0 ? (now - then) / then * 100.0 : 0;
	}

	/** 4h order-flow imbalance from volumes: + = sell pressure. Returns {pctSell, hasData}. */
	private static double ofiOf(ItemChart.Series s)
	{
		if (s == null || s.hvol == null || s.lvol == null) { return 0; }
		final int n = s.hvol.length;
		long hv = 0, lv = 0;
		for (int i = Math.max(0, n - 48); i < n; i++) { hv += s.hvol[i]; lv += s.lvol[i]; }
		final long t = hv + lv;
		return t > 0 ? (lv - hv) * 100.0 / t : 0;   // lv = insta-sell prints = sell pressure
	}

	private static String signPct(double v)
	{
		return (v >= 0 ? "+" : "-") + String.format("%.1f%%", Math.abs(v));
	}

	static Dimension paintTerminal(Graphics2D g, Context c, int w)
	{
		TerminalKit.hints(g);
		final int L = 12, R = w - 12, colW = (R - L - 16) / 3;
		final int c0 = L, c1 = L + colW + 8, c2 = L + 2 * colW + 16;

		// ── derive grid values from the Context ──
		final ItemChart.Series s = c.series;
		final long ask = c.refSell() > 0 ? c.refSell() : seriesHi(s);
		final long bid = c.refBuy() > 0 ? c.refBuy() : lastLowOf(s);
		final long spread = (ask > 0 && bid > 0) ? ask - bid : 0;
		final long net = (ask > 0 && bid > 0) ? GeTax.net(bid, ask) : 0;
		final long tax = ask > 0 ? Math.min(ask / 50, 5_000_000L) : 0;
		final double roi = bid > 0 ? net * 100.0 / bid : 0;
		final long vol24 = seriesVol(s);
		final long hi = c.rangeRow != null && c.rangeRow[0] > 0 ? c.rangeRow[0] : seriesHi(s);
		final long lo = c.rangeRow != null && c.rangeRow[2] > 0 ? c.rangeRow[2] : seriesLo(s);
		final double d1h = seriesDelta(s, 12);
		final double d24 = seriesDelta(s, 288);
		final double ofi = ofiOf(s);
		final int tapeRows = Math.min(c.prints != null ? c.prints.size() : 0, Math.max(1, c.tradeDepth));
		final boolean holding = c.lotQty > 0;

		// ── height (matches the section y-progression below) ──
		final int gridH = 5 * 26;
		final int chartH = 96;
		int h = 46 + gridH + 30 + (chartH + 8) + (40 + tapeRows * 17) + (holding ? 20 : 0) + 16;

		// ── panel ──
		g.setColor(TerminalKit.PANEL); g.fillRect(0, 0, w, h);
		g.setColor(TerminalKit.BORDER); g.setStroke(new BasicStroke(1f)); g.drawRect(0, 0, w, h);

		int y = 22;
		// 1. header
		g.setFont(TerminalKit.monoB(13)); g.setColor(TerminalKit.AMBERHI);
		g.drawString(clip(c.itemName == null ? "" : c.itemName.toUpperCase(), g.getFontMetrics(), R - 96 - L), L, y);
		g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
		TerminalKit.rt(g, "LIVE", R, y);
		y += 6;
		g.setColor(TerminalKit.GRID); g.drawLine(L, y, R, y);
		y += 18;

		// 2. quote grid
		TerminalKit.cell(g, c0, colW, y, "BID", bid > 0 ? TerminalKit.commas(bid) : "-", TerminalKit.AMBER);
		TerminalKit.cell(g, c1, colW, y, "ASK", ask > 0 ? TerminalKit.commas(ask) : "-", TerminalKit.AMBER);
		TerminalKit.cell(g, c2, colW, y, "SPRD", spread > 0 ? TerminalKit.commas(spread) : "-", TerminalKit.AMBER);
		y += 26;
		TerminalKit.cell(g, c0, colW, y, "NET/EA", (net >= 0 ? "+" : "") + TerminalKit.commas(net), net >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		TerminalKit.cell(g, c1, colW, y, "ROI", signPct(roi), roi >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		TerminalKit.cell(g, c2, colW, y, "TAX", TerminalKit.commas(tax), TerminalKit.LABEL);
		y += 26;
		TerminalKit.cell(g, c0, colW, y, "Δ1H", signPct(d1h), d1h >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		TerminalKit.cell(g, c1, colW, y, "Δ24H", signPct(d24), d24 >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		TerminalKit.cell(g, c2, colW, y, "VOL 24H", vol24 > 0 ? TerminalKit.gp(vol24) : "-", TerminalKit.AMBER);
		y += 26;
		TerminalKit.cell(g, c0, colW, y, "HI", hi > 0 ? TerminalKit.commas(hi) : "-", TerminalKit.AMBER);
		TerminalKit.cell(g, c1, colW, y, "LO", lo > 0 ? TerminalKit.commas(lo) : "-", TerminalKit.AMBER);
		TerminalKit.cell(g, c2, colW, y, "OFI",
			Math.abs(ofi) < 1 ? "balanced" : signPct(ofi).replace("+", "+").replace("%", "%") + (ofi >= 0 ? " SELL" : " BUY"),
			Math.abs(ofi) < 1 ? TerminalKit.LABEL : ofi >= 0 ? TerminalKit.RED : TerminalKit.GREEN);
		y += 26;
		TerminalKit.cell(g, c0, colW, y, "LIMIT", "-", TerminalKit.DIM);
		TerminalKit.cell(g, c1, colW, y, "RESET", "-", TerminalKit.DIM);
		TerminalKit.cell(g, c2, colW, y, "FILL", c.fillPct >= 0 ? c.fillPct + "%" : "-", TerminalKit.AMBER);
		y += 26;

		// verdict cells
		final int halfW = (R - L) / 2 - 4;
		final boolean two = c.stateText2 != null;
		final int box1W = two ? halfW : R - L;   // one verdict spans the full width
		g.setColor(new Color(0x2a, 0x1e, 0x14));
		if (c.stateText != null) { g.fillRect(L, y, box1W, 20); }
		if (two) { g.fillRect(L + halfW + 8, y, halfW, 20); }
		g.setFont(TerminalKit.monoB(11));
		if (c.stateText != null) { g.setColor(c.stateColor != null ? c.stateColor : TerminalKit.AMBER); g.drawString(clip(c.stateText, g.getFontMetrics(), box1W - 12), L + 8, y + 14); }
		if (two) { g.setColor(c.stateColor2 != null ? c.stateColor2 : TerminalKit.AMBER); g.drawString(clip(c.stateText2, g.getFontMetrics(), halfW - 12), L + halfW + 16, y + 14); }
		y += 30;

		// 3. band chart from series
		y = paintTermChart(g, s, L, y, R - L, chartH, c.refSell());
		y += 8;

		// 4. tape
		y = paintTermTape(g, c, L, R, y, tapeRows);

		// 5. position
		if (holding)
		{
			final long unit = c.lotCost / c.lotQty;
			final long edge = lastHighOf(s);
			final long pnl = edge > 0 ? c.lotQty * GeTax.net(unit, edge) : 0;
			g.setColor(TerminalKit.GRID); g.drawLine(L, y - 6, R, y - 6);
			g.setFont(TerminalKit.mono(10)); g.setColor(TerminalKit.LABEL); g.drawString("POS", L, y + 6);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(c.lotQty + " @ " + Fmt.compact(unit), L + 30, y + 6);
			if (edge > 0)
			{
				g.setColor(TerminalKit.LABEL); g.setFont(TerminalKit.mono(10)); g.drawString("uP&L", L + 150, y + 6);
				g.setFont(TerminalKit.monoB(11)); g.setColor(pnl >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
				g.drawString((pnl >= 0 ? "+" : "") + Fmt.compact(pnl), L + 188, y + 6);
			}
			y += 20;
		}

		// 6. footer
		g.setColor(TerminalKit.GRID); g.drawLine(L, y - 4, R, y - 4);
		g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.DIM);
		if (c.outcomeText != null) { g.drawString(clip(c.outcomeText, g.getFontMetrics(), R - L), L, y + 8); }

		return new Dimension(w, h);
	}

	private static int paintTermChart(Graphics2D g, ItemChart.Series s, int x, int y, int w, int hh, long yourSell)
	{
		final long hi = seriesHi(s), lo = seriesLo(s);
		if (s == null || s.high == null || s.high.length < 2 || hi <= lo)
		{
			g.setColor(TerminalKit.DIM); g.setFont(TerminalKit.mono(9));
			g.drawString("building the corridor...", x, y + hh / 2);
			return y + hh;
		}
		final double pad = (hi - lo) * 0.08 + 1;
		final double top = hi + pad, bot = lo - pad, span = top - bot;
		final int cw = w - 46, n = s.high.length;
		g.setColor(TerminalKit.GRID);
		for (int i = 0; i <= 3; i++) { final int gy = y + i * hh / 3; g.drawLine(x, gy, x + cw, gy); }
		g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
		for (int i = 0; i < 4; i++) { g.drawString(TerminalKit.gp((long) (top - i * span / 3)), x + cw + 4, y + i * hh / 3 + 3); }
		// Gap-aware: real series have quiet windows with 0 high/low. Skip them and
		// start a new segment across the gap, so a missing point never streaks a
		// line off to a garbage coordinate.
		final Path2D hiP = new Path2D.Float(), loP = new Path2D.Float();
		boolean startHi = true, startLo = true;
		for (int i = 0; i < n; i++)
		{
			final int px = x + (int) (i * (cw - 1.0) / (n - 1));
			if (s.high[i] > 0)
			{
				final int hy = y + (int) ((top - s.high[i]) / span * hh);
				if (startHi) { hiP.moveTo(px, hy); startHi = false; } else { hiP.lineTo(px, hy); }
			}
			else { startHi = true; }
			if (s.low[i] > 0)
			{
				final int ly = y + (int) ((top - s.low[i]) / span * hh);
				if (startLo) { loP.moveTo(px, ly); startLo = false; } else { loP.lineTo(px, ly); }
			}
			else { startLo = true; }
		}
		// Clip to the chart box so nothing can ever draw outside the card.
		final java.awt.Shape oldClip = g.getClip();
		g.clipRect(x, y - 2, cw + 2, hh + 4);
		g.setStroke(new BasicStroke(1.3f));
		g.setColor(TerminalKit.GREEN); g.draw(hiP);
		g.setColor(TerminalKit.RED); g.draw(loP);
		g.setClip(oldClip);
		if (yourSell > 0 && yourSell <= top && yourSell >= bot)
		{
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
			g.setColor(TerminalKit.AMBER);
			final int sy = y + (int) ((top - yourSell) / span * hh);
			g.drawLine(x, sy, x + cw, sy);
			g.setStroke(new BasicStroke(1f));
		}
		return y + hh;
	}

	private static int paintTermTape(Graphics2D g, Context c, int L, int R, int y, int tapeRows)
	{
		g.setColor(TerminalKit.GRID); g.drawLine(L, y - 4, R, y - 4);
		g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
		g.drawString("TIME & SALES", L, y + 7);
		int nBuy = 0;
		for (int i = 0; i < tapeRows; i++) { if (c.prints.get(c.prints.size() - 1 - i).buySide) { nBuy++; } }
		g.setColor(TerminalKit.GREEN); TerminalKit.rt(g, "▲" + nBuy, R - 26, y + 7);
		g.setColor(TerminalKit.RED); TerminalKit.rt(g, "▼" + (tapeRows - nBuy), R, y + 7);
		y += 13;
		g.setFont(TerminalKit.mono(8)); g.setColor(TerminalKit.DIM);
		TerminalKit.rt(g, "PRICE", L + 128, y); g.drawString("Δ VS YOU", L + 144, y); TerminalKit.rt(g, "AGE", R, y);
		y += 19;
		for (int i = 0; i < tapeRows; i++)
		{
			final Print p = c.prints.get(c.prints.size() - 1 - i);
			final int ry = y + i * 17;
			final boolean you = p.yours;
			final boolean up = p.buySide;
			final Color side = you ? TerminalKit.AMBERHI : up ? TerminalKit.GREEN : TerminalKit.RED;
			g.setFont(TerminalKit.monoB(11)); g.setColor(side);
			g.drawString(you ? "◆" : up ? "▲" : "▼", L, ry);
			g.setFont(TerminalKit.monoB(12)); g.setColor(side);
			TerminalKit.rt(g, Fmt.full(p.price), L + 128, ry);
			g.setFont(TerminalKit.mono(11));
			String tag; Color tc;
			if (you) { tag = p.yoursBuy ? "YOU BOUGHT" : "YOU SOLD"; tc = TerminalKit.AMBER; }
			else
			{
				final long ref = p.buySide ? (c.refSell() > 0 ? c.refSell() : c.refBuy()) : (c.refBuy() > 0 ? c.refBuy() : c.refSell());
				tag = ref > 0 ? (p.price >= ref ? "+" : "-") + Fmt.compact(Math.abs(p.price - ref)) : "";
				tc = TerminalKit.LABEL;
			}
			g.setColor(tc); g.drawString(tag, L + 144, ry);
			final long ago = Math.max(0, c.nowTs - p.ts);
			final String age = ago < 60 ? ago + "s" : ago < 5400 ? (ago / 60) + "m" : (ago / 3600) + "h";
			g.setColor(TerminalKit.DIM); TerminalKit.rt(g, age, R, ry);
		}
		return y + tapeRows * 17 + 8;
	}

	static Dimension paint(Graphics2D g, Context c)
	{
		return paint(g, c, W_FULL);
	}

	static Dimension paint(Graphics2D g, Context c, int w)
	{
		final Font small = net.runelite.client.ui.FontManager.getRunescapeSmallFont();
		g.setFont(small);
		final FontMetrics fm = g.getFontMetrics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		final int lineH = fm.getHeight();
		final int tapeRows = Math.min(c.prints != null ? c.prints.size() : 0, Math.max(1, c.tradeDepth));
		final boolean holding = c.lotQty > 0;
		final long[] pressure = pressureOf(c.series, c.nowTs);
		final int rangeH = c.rangeRow != null ? 3 * (lineH - 1) + 6 : 0;
		final int h = PAD + lineH + 6 + CHART_H + 6 + (pressure != null ? lineH + 3 : 0)
			+ rangeH
			+ (tapeRows > 0 ? tapeRows * 13 + 14 : 0)
			+ (holding ? lineH : 0) + 2 * lineH
			+ (c.outcomeText2 != null ? lineH : 0) + PAD + 2;

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

		// Header: verdicts right-aligned first, then the name clipped to the
		// space that remains so the two can never overprint.
		int y = PAD + fm.getAscent() - 2;
		final int nameEnd = paintVerdicts(g, c, fm, w, y);
		shadowed(g, clip(c.itemName, fm, nameEnd - 6 - PAD), PAD, y, NAME);
		y += 6;
		g.setColor(RULE);
		g.drawLine(PAD - 2, y, w - PAD + 2, y);
		y += 4;

		// Chart: the traded corridor over a window, OR the last-N-trades tick chart,
		// tagged with its active view so what it covers is never ambiguous.
		if (c.tradesChartN > 0)
		{
			paintTradesChart(g, c, PAD, y, w - 2 * PAD, CHART_H, fm);
		}
		else
		{
			paintChart(g, c, PAD, y, w - 2 * PAD, CHART_H, fm, false);
		}
		if (c.chartLabel != null && !c.chartLabel.isEmpty())
		{
			final String tag = c.chartLabel + (c.tradesChartN > 0 ? " trades" : " price");
			final int tw = fm.stringWidth(tag) + 8;
			g.setColor(SHADOW);
			g.fillRoundRect(PAD + 2, y + 2, tw, lineH - 1, 5, 5);
			shadowed(g, tag, PAD + 6, y + fm.getAscent() + 1, Palette.GOLD);
		}
		y += CHART_H + 4;

		// Pressure: its own labeled row. A split meter with a centre notch so
		// the lean reads before the words do, then the words anyway.
		if (pressure != null)
		{
			final int ry = y + fm.getAscent();
			shadowed(g, "Pressure", PAD, ry, Palette.SUBTLE);
			final int sellPct = (int) pressure[0];
			final String reading;
			final Color col;
			if (sellPct >= 58)
			{
				reading = sellPct + "% sell side · " + pressure[1] + "h";
				col = Palette.RED;
			}
			else if (sellPct <= 42)
			{
				reading = (100 - sellPct) + "% buy side · " + pressure[1] + "h";
				col = Palette.GREEN;
			}
			else
			{
				reading = "balanced · " + pressure[1] + "h";
				col = Palette.SUBTLE;
			}
			final int tx = w - PAD - fm.stringWidth(reading);
			shadowed(g, reading, tx, ry, col);
			final int bx = PAD + fm.stringWidth("Pressure") + 10;
			final int bw = tx - bx - 10;
			if (bw >= 40)
			{
				final int by = ry - 7;
				final int green = Math.round(bw * (100 - sellPct) / 100f);
				g.setColor(SHADOW);
				g.fillRect(bx + 1, by + 1, bw, 7);
				g.setColor(Palette.GREEN);
				g.fillRect(bx, by, green, 7);
				g.setColor(Palette.RED);
				g.fillRect(bx + green, by, bw - green, 7);
				// Centre notch: the 50/50 line the split is judged against.
				g.setColor(YOURS);
				g.fillRect(bx + bw / 2, by - 1, 1, 9);
			}
			y += lineH + 3;
		}

		// Range: the low/high of whatever view the chart is showing (a time window
		// or the last N trades), each with its clock time and how long ago, so a
		// set-up price reads against where the item has actually been in that view.
		if (c.rangeRow != null)
		{
			final int vx = PAD + fm.stringWidth("Range ") + 6;
			final int px = PAD + 34;
			final long[] row = c.rangeRow;
			int ry = y + fm.getAscent();
			shadowed(g, "Range", PAD, ry, Palette.SUBTLE);
			if (c.chartLabel != null)
			{
				shadowed(g, c.chartLabel + (c.tradesChartN > 0 ? " trades" : ""), vx, ry, Palette.SUBTLE_CANVAS);
			}
			y += lineH - 1;
			ry = y + fm.getAscent();
			shadowed(g, "high", PAD, ry, new Color(0x49, 0xc9, 0x7f, 210));
			if (row[0] > 0)
			{
				shadowed(g, Fmt.full(row[0]) + "  " + clockShort(row[1], c.nowTs), px, ry, Palette.GREEN);
			}
			y += lineH - 1;
			ry = y + fm.getAscent();
			shadowed(g, "low", PAD, ry, new Color(0xd9, 0x5c, 0x5e, 210));
			if (row[2] > 0)
			{
				shadowed(g, Fmt.full(row[2]) + "  " + clockShort(row[3], c.nowTs), px, ry, Palette.RED);
			}
			y += lineH - 1 + 5;
		}

		// Tape: the prints this client has watched arrive, newest first. The
		// header carries the side split of exactly these rows, so the counts
		// always reconcile with what is listed below them.
		if (tapeRows > 0)
		{
			shadowed(g, "Last trades seen", PAD, y + fm.getAscent(), Palette.SUBTLE);
			int nBuy = 0;
			for (int i = 0; i < tapeRows; i++)
			{
				if (c.prints.get(c.prints.size() - 1 - i).buySide)
				{
					nBuy++;
				}
			}
			int hx = PAD + fm.stringWidth("Last trades seen") + 10;
			final int hy = y + fm.getAscent();
			hx = headerSideCount(g, fm, hx, hy, true, nBuy);
			headerSideCount(g, fm, hx + 8, hy, false, tapeRows - nBuy);
			y += lineH + 1;
			// The extremes of exactly these rows, badged so the traded range
			// reads at a glance. Colours follow the corridor's edges: green is
			// the high edge, red the low.
			int hiIdx = -1;
			int loIdx = -1;
			for (int i = 0; i < tapeRows; i++)
			{
				final Print p = c.prints.get(c.prints.size() - 1 - i);
				if (p.price <= 0)
				{
					continue;
				}
				if (hiIdx < 0 || p.price > c.prints.get(c.prints.size() - 1 - hiIdx).price)
				{
					hiIdx = i;
				}
				if (loIdx < 0 || p.price < c.prints.get(c.prints.size() - 1 - loIdx).price)
				{
					loIdx = i;
				}
			}
			if (hiIdx == loIdx)
			{
				hiIdx = -1;
				loIdx = -1;
			}
			// One badge column for the whole tape: pills stack instead of
			// trailing each price at its own ragged end.
			int badgeX = 0;
			for (int i = 0; i < tapeRows; i++)
			{
				badgeX = Math.max(badgeX, fm.stringWidth(Fmt.full(c.prints.get(c.prints.size() - 1 - i).price)));
			}
			badgeX += PAD + 14 + 8;
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
				// Ownership gold outranks the extreme tint on the price text;
				// the badge still marks the extreme either way.
				final Color priceCol = p.yours ? Palette.GOLD
					: i == hiIdx ? Palette.GREEN : i == loIdx ? Palette.RED : NAME;
				shadowed(g, Fmt.full(p.price), PAD + 14, ty, priceCol);
				if (i == hiIdx || i == loIdx)
				{
					badge(g, fm, badgeX, ty, w / 2 - 14, i == hiIdx);
				}
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
				shadowed(g, delta, w / 2 - 10, ty, deltaCol);
				final int aw = fm.stringWidth(age);
				shadowed(g, age, w - PAD - aw, ty, Palette.SUBTLE);
			}
			y += tapeRows * 13 + 2;
		}

		// Your position, priced at the live edge. Lots bought at different
		// prices itemise ("1 @ 816.1m + 3 @ 817m") when that fits on the
		// line; otherwise the range. Lots that read identically at display
		// precision merge first, and a single price renders as before.
		if (holding)
		{
			final long unit = c.lotCost / c.lotQty;
			Color holdCol = Palette.SUBTLE;
			String tailFull = "";
			String tailShort = "";
			final long edge = lastHighOf(c.series);
			if (edge > 0)
			{
				final long pnl = c.lotQty * GeTax.net(unit, edge);
				final String p = (pnl >= 0 ? "+" : "") + Fmt.compact(pnl);
				tailFull = " · " + p + " after tax at the bid";
				tailShort = " · " + p + " at bid";
				holdCol = pnl >= 0 ? Palette.GREEN : Palette.RED;
			}
			// Fitting ladder: the real lots are the point, so the itemised form
			// wins even at the cost of the longer profit tail. The blended
			// average is the last resort, and says it is one.
			final int maxW = w - 2 * PAD;
			String hold = null;
			String tail = tailFull;
			final java.util.List<long[]> merged = mergedLots(c.lotEntries);
			if (merged.size() > 1 && merged.size() <= 4)
			{
				final StringBuilder sb = new StringBuilder("Holding ");
				for (int i = 0; i < merged.size(); i++)
				{
					if (i > 0)
					{
						sb.append(" + ");
					}
					sb.append(Fmt.full(merged.get(i)[0])).append(" @ ").append(Fmt.compact(merged.get(i)[1]));
				}
				if (fm.stringWidth(sb + tailFull) <= maxW)
				{
					hold = sb.toString();
				}
				else if (fm.stringWidth(sb + tailShort) <= maxW)
				{
					hold = sb.toString();
					tail = tailShort;
				}
			}
			if (hold == null && merged.size() > 1)
			{
				final String range = "Holding " + Fmt.full(c.lotQty) + " avg " + Fmt.compact(unit)
					+ " (" + Fmt.compact(merged.get(0)[1]) + " to " + Fmt.compact(merged.get(merged.size() - 1)[1]) + ")";
				if (fm.stringWidth(range + tailShort) <= maxW)
				{
					hold = range;
					tail = tailShort;
				}
				else
				{
					hold = "Holding " + Fmt.full(c.lotQty) + " avg " + Fmt.compact(unit);
					tail = fm.stringWidth(hold + tailFull) <= maxW ? tailFull : tailShort;
				}
			}
			if (hold == null)
			{
				hold = "Holding " + Fmt.full(c.lotQty) + " @ " + Fmt.compact(unit);
			}
			shadowed(g, clip(hold + tail, fm, maxW), PAD, y + fm.getAscent(), holdCol);
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
			shadowed(g, clip(c.outcomeText, fm, w - 2 * PAD), PAD, y + fm.getAscent(), c.outcomeColor != null ? c.outcomeColor : Palette.SUBTLE);
		}
		y += lineH;
		if (c.outcomeText2 != null)
		{
			shadowed(g, clip(c.outcomeText2, fm, w - 2 * PAD), PAD, y + fm.getAscent(), c.outcomeColor2 != null ? c.outcomeColor2 : Palette.SUBTLE);
			y += lineH;
		}

		// (Your trades moved to the docked panel below the GE window.)

		return new Dimension(w, h);
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

	/**
	 * Order-flow pressure over an adaptive recent window: what share of traded
	 * units had a SELLER as the aggressor (hitting bids) versus a buyer
	 * (lifting asks). Raw counts are incomparable across items, so the read is
	 * a share, and the window widens (4h, then 8h, then 24h) until at least a
	 * dozen units back it: a high-volume item reads its recent hours, a
	 * Tumeken-class item reads its day, and the label says which. Returns
	 * {sellPct, windowHours} or null when even the day is too quiet to call.
	 */
	private static long[] pressureOf(ItemChart.Series s, long nowTs)
	{
		if (s == null || s.ts == null || s.ts.length == 0)
		{
			return null;
		}
		for (final int hrs : new int[]{4, 8, 24})
		{
			final long cut = nowTs - hrs * 3600L;
			long hv = 0;
			long lv = 0;
			for (int i = s.ts.length - 1; i >= 0 && s.ts[i] >= cut; i--)
			{
				hv += s.hvol != null && s.hvol[i] > 0 ? s.hvol[i] : 0;
				lv += s.lvol != null && s.lvol[i] > 0 ? s.lvol[i] : 0;
			}
			final long total = hv + lv;
			if (total >= 12 || (hrs == 24 && total >= 6))
			{
				return new long[]{Math.round(100.0 * lv / total), hrs};
			}
		}
		return null;
	}

	private static final Color BADGE_HIGH = new Color(0x49, 0xc9, 0x7f);
	private static final Color BADGE_LOW = new Color(0xd9, 0x5c, 0x5e);
	private static final Color BADGE_INK = new Color(0x12, 0x0e, 0x08);

	/** A solid pill after a tape price marking the extreme of the listed rows,
	 *  in the same register as the chart's S/B tags: filled colour, dark ink
	 *  text. Drops to a short label, then nothing, as space runs out. */
	private static void badge(Graphics2D g, FontMetrics fm, int x, int ty, int limitX, boolean high)
	{
		String text = high ? "high" : "low";
		int bw = fm.stringWidth(text) + 10;
		if (x + bw > limitX)
		{
			text = high ? "hi" : "lo";
			bw = fm.stringWidth(text) + 10;
			if (x + bw > limitX)
			{
				return;
			}
		}
		final Color col = high ? BADGE_HIGH : BADGE_LOW;
		// Box brackets the row's text: ascent above the shared baseline plus a
		// whisker each side, text ON the baseline like its row.
		final int by = ty - 10;
		g.setColor(SHADOW);
		g.fillRoundRect(x + 1, by + 1, bw, 13, 6, 6);
		g.setColor(col);
		g.fillRoundRect(x, by, bw, 13, 6, 6);
		g.setColor(BADGE_INK);
		g.drawString(text, x + 5, ty);
	}

	/** One "triangle + count" chip in the tape header; returns the x after it. */
	private static int headerSideCount(Graphics2D g, FontMetrics fm, int x, int y, boolean up, int n)
	{
		final Path2D tri = new Path2D.Float();
		if (up)
		{
			tri.moveTo(x, y);
			tri.lineTo(x + 7, y);
			tri.lineTo(x + 3.5, y - 6);
		}
		else
		{
			tri.moveTo(x, y - 6);
			tri.lineTo(x + 7, y - 6);
			tri.lineTo(x + 3.5, y);
		}
		tri.closePath();
		g.setColor(SHADOW);
		g.translate(1, 1);
		g.fill(tri);
		g.translate(-1, -1);
		g.setColor(up ? Palette.GREEN : Palette.RED);
		g.fill(tri);
		final String s = String.valueOf(n);
		shadowed(g, s, x + 10, y, up ? Palette.GREEN : Palette.RED);
		return x + 10 + fm.stringWidth(s);
	}

	/** Lots sorted by unit price, with lots that read identically at compact
	 *  display precision merged into one {qty, unit} row. */
	private static java.util.List<long[]> mergedLots(long[][] lots)
	{
		final java.util.List<long[]> out = new java.util.ArrayList<>();
		if (lots == null)
		{
			return out;
		}
		final long[][] sorted = lots.clone();
		java.util.Arrays.sort(sorted, (a, b) -> Long.compare(a[1], b[1]));
		for (final long[] l : sorted)
		{
			if (!out.isEmpty() && Fmt.compact(out.get(out.size() - 1)[1]).equals(Fmt.compact(l[1])))
			{
				out.get(out.size() - 1)[0] += l[0];
			}
			else
			{
				out.add(new long[]{l[0], l[1]});
			}
		}
		return out;
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

	/** Where the full card draws its timeframe tag, in card-local coordinates,
	 *  so the overlay can make it the clickable timeframe control. Mirrors the
	 *  tag geometry in {@link #paint}; null when there is no label. */
	static java.awt.Rectangle chartTagBounds(FontMetrics fm, String label, boolean trades)
	{
		if (label == null || label.isEmpty())
		{
			return null;
		}
		final int lineH = fm.getHeight();
		final int chartTop = PAD + fm.getAscent() + 8;   // header baseline + rule gap
		final int tw = fm.stringWidth(label + (trades ? " trades" : " price")) + 8;
		return new java.awt.Rectangle(PAD + 2, chartTop + 2, tw, lineH - 1);
	}

	private static int tyf(long price, long pMin, long pMax, int y0, int plotH)
	{
		final double f = (price - pMin) / (double) Math.max(1, pMax - pMin);
		return y0 + plotH - (int) Math.round(Math.max(0, Math.min(1, f)) * plotH);
	}

	/** The last-N-trades tick chart: each recent print is a point (up = someone
	 *  insta-bought, down = insta-sold, gold = your own fill), connected in order
	 *  and scaled to the trades plus your offer lines. Always has data, so it
	 *  never starves the way a short time window can on a big-ticket item. */
	private static void paintTradesChart(Graphics2D g, Context c, int x0, int y0, int cw, int ch, FontMetrics fm)
	{
		final int have = c.prints != null ? c.prints.size() : 0;
		if (have < 2)
		{
			shadowed(g, "Not enough trades yet", x0 + 4, y0 + ch / 2, Palette.SUBTLE);
			return;
		}
		final int n = Math.min(Math.max(2, c.tradesChartN), have);
		final int plotW = cw - PRICE_GUTTER;
		final int plotH = ch - 4;
		final List<Print> pts = c.prints.subList(have - n, have);

		long lo = Long.MAX_VALUE;
		long hi = Long.MIN_VALUE;
		for (final Print p : pts)
		{
			if (p.price > 0)
			{
				lo = Math.min(lo, p.price);
				hi = Math.max(hi, p.price);
			}
		}
		if (lo == Long.MAX_VALUE)
		{
			shadowed(g, "No priced trades yet", x0 + 4, y0 + ch / 2, Palette.SUBTLE);
			return;
		}
		for (final long p : c.youSells)
		{
			if (p > 0)
			{
				lo = Math.min(lo, p);
				hi = Math.max(hi, p);
			}
		}
		for (final long p : c.youBuys)
		{
			if (p > 0)
			{
				lo = Math.min(lo, p);
				hi = Math.max(hi, p);
			}
		}
		if (lo >= hi)
		{
			hi = lo + 1;
		}
		final long pad = Math.max(1, (hi - lo) / 10);
		final long pMin = lo - pad;
		final long pMax = hi + pad;

		// Price gridlines + gutter labels.
		for (int i = 0; i < 4; i++)
		{
			final long p = pMin + (pMax - pMin) * i / 3;
			final int yy = tyf(p, pMin, pMax, y0, plotH);
			g.setColor(ChartKit.GRID);
			g.drawLine(x0, yy, x0 + plotW, yy);
			g.setColor(Palette.SUBTLE_CANVAS);
			g.drawString(Fmt.compact(p), x0 + plotW + 6, Math.max(y0 + fm.getAscent() - 2, yy + 4));
		}

		// The trades: evenly spaced left (oldest) to right (newest), joined.
		final double dx = n > 1 ? plotW / (double) (n - 1) : 0;
		final int[] xs = new int[n];
		final int[] ys = new int[n];
		final Path2D line = new Path2D.Float();
		boolean started = false;
		for (int i = 0; i < n; i++)
		{
			final Print p = pts.get(i);
			xs[i] = (int) Math.round(x0 + i * dx);
			ys[i] = p.price > 0 ? tyf(p.price, pMin, pMax, y0, plotH) : -1;
			if (p.price > 0)
			{
				if (!started)
				{
					line.moveTo(xs[i], ys[i]);
					started = true;
				}
				else
				{
					line.lineTo(xs[i], ys[i]);
				}
			}
		}
		g.setColor(new Color(255, 255, 255, 90));
		g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(line);
		g.setStroke(new BasicStroke(1f));

		for (int i = 0; i < n; i++)
		{
			final Print p = pts.get(i);
			if (p.price <= 0)
			{
				continue;
			}
			final int r = p.yours ? 4 : 3;
			final Path2D tri = new Path2D.Float();
			if (p.buySide)
			{
				tri.moveTo(xs[i] - r, ys[i] + r - 1);
				tri.lineTo(xs[i] + r, ys[i] + r - 1);
				tri.lineTo(xs[i], ys[i] - r);
			}
			else
			{
				tri.moveTo(xs[i] - r, ys[i] - r + 1);
				tri.lineTo(xs[i] + r, ys[i] - r + 1);
				tri.lineTo(xs[i], ys[i] + r);
			}
			tri.closePath();
			g.setColor(SHADOW);
			g.translate(1, 1);
			g.fill(tri);
			g.translate(-1, -1);
			g.setColor(p.yours ? Palette.GOLD : (p.buySide ? Palette.GREEN : Palette.RED));
			g.fill(tri);
		}

		// Your offers, drawn on top with the same labelled chip the corridor uses,
		// so where your price sits among these trades is unmistakable. Seated =
		// your price already meets the freshest print on the side that fills you.
		long lastHigh = 0;
		long lastLow = 0;
		for (int i = n - 1; i >= 0; i--)
		{
			final Print p = pts.get(i);
			if (p.price <= 0)
			{
				continue;
			}
			if (p.buySide && lastHigh == 0)
			{
				lastHigh = p.price;
			}
			if (!p.buySide && lastLow == 0)
			{
				lastLow = p.price;
			}
			if (lastHigh != 0 && lastLow != 0)
			{
				break;
			}
		}
		final int chipH = fm.getHeight() + 2;
		for (final long p : c.youSells)
		{
			if (p >= pMin && p <= pMax)
			{
				final int yy = tyf(p, pMin, pMax, y0, plotH);
				yourLine(g, p, yy, yy, true, lastHigh > 0 && p <= lastHigh, x0, plotW, fm, chipH);
			}
		}
		for (final long p : c.youBuys)
		{
			if (p >= pMin && p <= pMax)
			{
				final int yy = tyf(p, pMin, pMax, y0, plotH);
				yourLine(g, p, yy, yy, false, lastLow > 0 && p >= lastLow, x0, plotW, fm, chipH);
			}
		}
	}

	private static void paintChart(Graphics2D g, Context c, int x0, int y0, int cw, int ch, FontMetrics fm, boolean compact)
	{
		final int plotW = cw - PRICE_GUTTER;
		// Full cards carry a real volume pane below the plot; minis keep the
		// slim strip so their charts keep the vertical room.
		final int volH = compact ? 5 : 24;
		final int plotH = ch - volH - 3;
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

		ChartKit.paintTimeGrid(g, d, x0, y0, plotW, plotH);
		ChartKit.paintCorridor(g, d, x0, y0, plotW, plotH);
		ChartKit.paintLevelGuides(g, d, x0, y0, plotW, plotH);
		if (compact)
		{
			ChartKit.paintFillStrip(g, d, x0, y0 + plotH + 2, plotW, volH);
		}
		else
		{
			ChartKit.paintVolumeBars(g, d, x0, y0 + plotH + 3, plotW, volH);
			// Name the pane in the gutter, same quiet register as the tape's
			// "Last trades seen" header.
			shadowed(g, "24h volume", x0 + plotW + 5,
				y0 + plotH + 3 + volH / 2 + fm.getAscent() / 2 - 2, Palette.SUBTLE);
		}

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
			// One diamond per lot at its own entry price and time, so a
			// position built from several buys shows each of them where they
			// actually happened. Single-lot positions look exactly as before.
			final long[][] lots = c.lotEntries != null && c.lotEntries.length > 0
				? c.lotEntries : new long[][]{{c.lotQty, lotUnit, c.lotOpenedAtMs}};
			for (final long[] l : lots)
			{
				final long lts = l[2] / 1000L;
				final int lx = lts <= d.tMin ? x0
					: Math.round(ChartKit.x(d, Math.min(lts, d.tMax), x0, plotW));
				final int ly = Math.round(ChartKit.y(d, l[1], y0, plotH));
				if (ly < y0 || ly > y0 + plotH)
				{
					continue;
				}
				final Path2D diamond = new Path2D.Float();
				diamond.moveTo(lx, ly - 4);
				diamond.lineTo(lx + 4, ly);
				diamond.lineTo(lx, ly + 4);
				diamond.lineTo(lx - 4, ly);
				diamond.closePath();
				g.setColor(SHADOW);
				g.translate(1, 1);
				g.fill(diamond);
				g.translate(-1, -1);
				g.setColor(basis);
				g.fill(diamond);
			}
		}

		// The last trades from the tape, placed on the chart where they
		// happened: up = someone insta-bought, down = someone insta-sold,
		// gold = your own fill. The tape says what and when; these show WHERE
		// each trade landed against your lines.
		if (!compact && c.prints != null && !c.prints.isEmpty())
		{
			final int from = Math.max(0, c.prints.size() - Math.max(1, c.tradeDepth));
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
		for (int i = 0; i < 4; i++)
		{
			final long p = d.pMin + (d.pMax - d.pMin) * i / 3;
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

	/** "HH:mm 12m": the print's clock time in game (UTC) time, matching the GE's
	 *  own 24h-high/low timestamps, plus how long ago it happened. */
	private static String clockShort(long tsSec, long nowSec)
	{
		if (tsSec <= 0)
		{
			return "";
		}
		final long hh = (tsSec % 86400L) / 3600L;
		final long mm = (tsSec % 3600L) / 60L;
		final String hm = (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
		final long ago = Math.max(0, nowSec - tsSec);
		final String rel = ago < 60 ? "now"
			: ago < 3600 ? (ago / 60) + "m"
			: ago < 86400 ? (ago / 3600) + "h" : (ago / 86400) + "d";
		return hm + " " + rel;
	}

	private static void shadowed(Graphics2D g, String s, int x, int yy, Color c)
	{
		g.setColor(SHADOW);
		g.drawString(s, x + 1, yy + 1);
		g.setColor(c);
		g.drawString(s, x, yy);
	}
}

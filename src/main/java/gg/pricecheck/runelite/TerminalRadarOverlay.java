package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's left column: stacked terminal panels docked to the left of the Grand
 * Exchange window - OPPORTUNITY RADAR (top flips by EV/hr), FRESH DIPS (live dump
 * movers), and TOP MOVERS gainers + losers with a 1H/24H/7D window toggle. All read
 * the live ranked board (Trader Pro), so the column shows only when data is live and
 * is room to the left. Opt-in via config.terminalDesk(); look from TerminalKit.
 */
class TerminalRadarOverlay extends Overlay
{
	static final int W = 300;
	private static final int GAP = 6;
	private static final int TOP_Y = 8;

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalRadarOverlay(Client client, PriceCheckPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// Overview-only: when a slot/setup screen is up, the left dock belongs to the
		// single terminal card, so the radar column stands down.
		if (!plugin.terminalDesk() || !plugin.marketDataOk() || !plugin.geOverviewOpen())
		{
			return null;
		}
		final Rectangle ge = plugin.geGridBounds();
		if (ge == null)
		{
			return null;
		}
		final int x = ge.x - W - GAP;
		if (x < 4)
		{
			return null;   // no room to the left of the GE on this layout
		}
		final List<FlipData> flips = plugin.boardFlips();
		final List<CatchData> catches = plugin.boardCatches();
		if (flips.isEmpty() && catches.isEmpty())
		{
			return null;
		}
		final int availH = client.getCanvasHeight() - TOP_Y - 8;

		final int tfIdx = plugin.moversTf().ordinal();
		final List<Rectangle> chipRects = new ArrayList<>();

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, TOP_Y);
		try
		{
			paintColumn(g, W, availH, flips, catches, tfIdx, chipRects);
		}
		finally
		{
			g.translate(-x, -TOP_Y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		// Publish the timeframe chips in canvas space so the plugin mouse hook can
		// route a click to the matching window. chipRects are column-local (order
		// 1H, 24H, 7D); shift them by the column origin.
		final List<Object[]> hits = new ArrayList<>();
		final PriceCheckPlugin.MoversTf[] tfs = PriceCheckPlugin.MoversTf.values();
		for (int i = 0; i < chipRects.size() && i < tfs.length; i++)
		{
			final Rectangle r = chipRects.get(i);
			hits.add(new Object[]{ new Rectangle(r.x + x, r.y + TOP_Y, r.width, r.height), tfs[i] });
		}
		tfChipHits = hits;
		return new Dimension(W, availH);
	}

	// Canvas-space TOP MOVERS timeframe chips, {Rectangle, MoversTf}, refreshed
	// each render. The plugin's mouse listener routes a press here.
	private volatile List<Object[]> tfChipHits = java.util.Collections.emptyList();

	boolean handleClick(java.awt.Point p)
	{
		if (p == null)
		{
			return false;
		}
		for (final Object[] b : tfChipHits)
		{
			if (((Rectangle) b[0]).contains(p))
			{
				plugin.setMoversTf((PriceCheckPlugin.MoversTf) b[1]);
				return true;
			}
		}
		return false;
	}

	private static final int RADAR_ROW = 16;
	private static final int LIST_ROW = 15;
	private static final int SUBHEAD = 13;

	// Flash-on-change: per geId {lastTrend, flashUntilMs, dir}. A % that moves
	// between polls flashes its row (green up / red down) then fades - the live
	// tape feel. Static so it survives the per-frame repaint; reset on tf switch.
	private static final long FLASH_MS = 750;
	private static final Map<Integer, double[]> FLASH = new HashMap<>();
	private static int lastTfIdx = -1;

	/** Panel height for a section: title strip + optional sub-header + rows + pad. */
	private static int sectionH(int rows, boolean subhead)
	{
		return 32 + (subhead ? SUBHEAD : 0) + rows * (subhead ? RADAR_ROW : LIST_ROW) + 5;
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintColumn(Graphics2D g, int w, int availH, List<FlipData> flips, List<CatchData> catches, int tfIdx, List<Rectangle> chipOut)
	{
		TerminalKit.hints(g);
		// A timeframe switch changes every value at once - not a market move, so
		// forget the last-seen values to avoid flashing the whole board.
		if (tfIdx != lastTfIdx) { FLASH.clear(); lastTfIdx = tfIdx; }

		// Fresh dips: live dumps. Drop only the clearly-dead moves (ended / faded),
		// data-artifact spikes (a real dip almost never exceeds ~85%), and low-value
		// vendor junk (arrows, seeds, darts) by their entry price. Keep the rest
		// (catchable / knife / watch / settling / recovering are all informative).
		final List<CatchData> dips = new ArrayList<>();
		for (final CatchData c : catches)
		{
			if (c == null || c.getPctMove() >= 0 || Math.abs(c.getPctMove()) >= 85)
			{
				continue;
			}
			final String st = c.getState() == null ? "" : c.getState().toLowerCase();
			if (st.equals("ended") || st.equals("faded"))
			{
				continue;
			}
			if (c.getBid() > 0 && c.getBid() < 50_000)
			{
				continue;   // vendor-tier junk, not a flip
			}
			dips.add(c);
		}
		// Actionable first (the engine's catchable reads), then sharpest drop.
		dips.sort(Comparator.comparing((CatchData c) -> !c.isCatchable())
			.thenComparingDouble(CatchData::getPctMove));

		// Top movers over the selected window (tfIdx: 0=1h, 1=24h, 2=7d): board
		// rows rising the hardest (gainers) and falling the hardest (losers).
		final List<FlipData> gainers = new ArrayList<>();
		final List<FlipData> losers = new ArrayList<>();
		for (final FlipData f : flips)
		{
			if (f == null) { continue; }
			final double t = trendFor(f, tfIdx);
			if (t > 0) { gainers.add(f); }
			else if (t < 0) { losers.add(f); }
		}
		gainers.sort((a, b) -> Double.compare(trendFor(b, tfIdx), trendFor(a, tfIdx)));
		losers.sort((a, b) -> Double.compare(trendFor(a, tfIdx), trendFor(b, tfIdx)));   // sharpest drop first

		int radarRows = Math.min(flips.size(), 10);
		int dipRows = Math.min(dips.size(), 5);
		int gainRows = Math.min(gainers.size(), 8);
		int loseRows = Math.min(losers.size(), 8);

		// Fresh dips always keeps its panel (>=1 row: a dip or a "quiet" placeholder)
		// so the left column never loses a section. Trim the radar to fit first.
		while (radarRows > 3
			&& sectionH(radarRows, true) + GAP + sectionH(Math.max(1, dipRows), false)
				+ gap(gainRows) + sectionH(gainRows, false)
				+ gap(loseRows) + sectionH(loseRows, false) > availH)
		{
			radarRows--;
		}

		int y = 0;
		if (radarRows > 0)
		{
			y = paintRadar(g, w, y, flips, radarRows) + GAP;
		}
		y = paintDips(g, w, y, dips, dipRows) + GAP;
		if (gainRows > 0)
		{
			y = paintGainers(g, w, y, gainers, gainRows, tfIdx, chipOut) + GAP;
		}
		if (loseRows > 0)
		{
			paintLosers(g, w, y, losers, loseRows, tfIdx);
		}
	}

	private static int gap(int rows)
	{
		return rows > 0 ? GAP : 0;
	}

	private static int paintRadar(Graphics2D g, int w, int y, List<FlipData> flips, int rows)
	{
		final int h = sectionH(rows, true);
		int cy = TerminalKit.panel(g, 0, y, w, h, "OPPORTUNITY RADAR  ·  TOP EV/HR");
		// column sub-header
		g.setFont(TerminalKit.mono(8)); g.setColor(TerminalKit.DIM);
		g.drawString("ITEM", 8, cy);
		TerminalKit.rt(g, "MARGIN", w - 92, cy);
		TerminalKit.rt(g, "EV/HR", w - 26, cy);
		cy += SUBHEAD;
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		for (int i = 0; i < rows; i++)
		{
			final FlipData f = flips.get(i);
			final int ry = cy + i * RADAR_ROW;
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(name(f), fm, 118), 8, ry);
			g.setFont(TerminalKit.mono(10)); g.setColor(TerminalKit.LABEL);
			TerminalKit.rt(g, "+" + TerminalKit.gp(f.getMargin()), w - 92, ry);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.AMBERHI);
			TerminalKit.rt(g, TerminalKit.gp(f.getEvPerHr()), w - 26, ry);
			trendMark(g, w - 14, ry, f.getTrendPct());
		}
		return y + h;
	}

	private static int paintDips(Graphics2D g, int w, int y, List<CatchData> dips, int rows)
	{
		final int h = sectionH(Math.max(1, rows), false);
		final int cy = TerminalKit.panel(g, 0, y, w, h, "FRESH DIPS  ·  DUMP CATCHER");
		if (rows <= 0)
		{
			g.setFont(TerminalKit.mono(10)); g.setColor(TerminalKit.DIM);
			g.drawString("quiet  ·  no live dumps", 8, cy);
			return y + h;
		}
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		for (int i = 0; i < rows; i++)
		{
			final CatchData c = dips.get(i);
			final int ry = cy + i * LIST_ROW;
			// State drives the colour: green = the engine says catchable, red = still
			// falling (a knife, skip), dim = watch/recovering.
			final boolean catchable = c.isCatchable();
			final boolean knife = c.isKnife();
			final Color sc = catchable ? TerminalKit.GREEN : knife ? TerminalKit.RED : TerminalKit.DIM;
			g.setFont(TerminalKit.monoB(11)); g.setColor(sc);
			g.drawString("▼", 8, ry);
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(c.getName() == null ? "?" : c.getName(), fm, 118), 24, ry);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.RED);
			TerminalKit.rt(g, String.format("%.1f%%", c.getPctMove()), w - 66, ry);
			g.setFont(TerminalKit.monoB(9)); g.setColor(sc);
			TerminalKit.rt(g, stateLabel(c), w - 10, ry);
		}
		return y + h;
	}

	private static String stateLabel(CatchData c)
	{
		if (c.isCatchable()) { return "CATCH"; }
		if (c.isKnife()) { return "KNIFE"; }
		final String s = c.getState();
		return s == null || s.isEmpty() ? "WATCH" : s.toUpperCase();
	}

	private static int paintGainers(Graphics2D g, int w, int y, List<FlipData> gainers, int rows, int tfIdx, List<Rectangle> chipOut)
	{
		final int h = sectionH(rows, false);
		final int cy = TerminalKit.panel(g, 0, y, w, h, "TOP MOVERS  ·  GAINERS");
		paintTfChips(g, w, y, tfIdx, chipOut);
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		for (int i = 0; i < rows; i++)
		{
			final FlipData f = gainers.get(i);
			final int ry = cy + i * LIST_ROW;
			final Color fb = flashBg(f.getGeId(), trendFor(f, tfIdx));
			if (fb != null) { g.setColor(fb); g.fillRect(3, ry - 11, w - 6, 14); }
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.GREEN);
			g.drawString("▲", 8, ry);
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(name(f), fm, 190), 24, ry);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.GREEN);
			TerminalKit.rt(g, String.format("+%.1f%%", trendFor(f, tfIdx)), w - 10, ry);
		}
		return y + h;
	}

	private static int paintLosers(Graphics2D g, int w, int y, List<FlipData> losers, int rows, int tfIdx)
	{
		final int h = sectionH(rows, false);
		final int cy = TerminalKit.panel(g, 0, y, w, h, "TOP MOVERS  ·  LOSERS");
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		for (int i = 0; i < rows; i++)
		{
			final FlipData f = losers.get(i);
			final int ry = cy + i * LIST_ROW;
			final Color fb = flashBg(f.getGeId(), trendFor(f, tfIdx));
			if (fb != null) { g.setColor(fb); g.fillRect(3, ry - 11, w - 6, 14); }
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.RED);
			g.drawString("▼", 8, ry);
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(name(f), fm, 190), 24, ry);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.RED);
			TerminalKit.rt(g, String.format("%.1f%%", trendFor(f, tfIdx)), w - 10, ry);
		}
		return y + h;
	}

	/** The trend for the active TOP MOVERS window (0=1h, 1=24h, 2=7d). */
	static double trendFor(FlipData f, int tfIdx)
	{
		if (f == null) { return 0; }
		switch (tfIdx)
		{
			case 1: return f.getTrend24h();
			case 2: return f.getTrend7d();
			default: return f.getTrendPct();
		}
	}

	/** Flash background for a row whose % just moved since the last poll, fading
	 *  over FLASH_MS. Green for an up-tick, red for a down-tick; null when idle.
	 *  First sight of an item never flashes (no prior value to compare). */
	private static Color flashBg(int geId, double cur)
	{
		if (FLASH.size() > 400) { FLASH.clear(); }
		final long now = System.currentTimeMillis();
		final double[] st = FLASH.get(geId);
		if (st == null) { FLASH.put(geId, new double[]{ cur, 0, 0 }); return null; }
		if (Math.abs(cur - st[0]) > 0.049)
		{
			st[2] = cur > st[0] ? 1 : -1;
			st[1] = now + FLASH_MS;
			st[0] = cur;
		}
		if (now >= st[1]) { return null; }
		final double frac = (st[1] - now) / (double) FLASH_MS;   // 1 -> 0
		final int a = (int) Math.max(0, Math.min(160, 160 * frac));
		return st[2] > 0 ? new Color(0x2f, 0x86, 0x4a, a) : new Color(0xa8, 0x38, 0x2b, a);
	}

	/** 1H / 24H / 7D chips in the movers title strip, right-aligned, active one
	 *  boxed brighter. Records each chip's column-local bounds into chipOut (order
	 *  1H, 24H, 7D) so render() can map them to canvas space for clicks. */
	private static void paintTfChips(Graphics2D g, int w, int y, int tfIdx, List<Rectangle> chipOut)
	{
		final String[] labels = { "1H", "24H", "7D" };
		g.setFont(TerminalKit.monoB(9));
		final FontMetrics fm = g.getFontMetrics();
		int right = w - 6;
		for (int i = labels.length - 1; i >= 0; i--)
		{
			final int cw = fm.stringWidth(labels[i]) + 8;
			final int cx = right - cw;
			final int chy = y + 3;
			final boolean active = i == tfIdx;
			g.setColor(active ? new Color(0x2c, 0x24, 0x10) : new Color(0x17, 0x12, 0x07));
			g.fillRect(cx, chy, cw, 13);
			g.setColor(active ? new Color(0x5c, 0x4d, 0x22) : new Color(0x30, 0x2a, 0x16));
			g.drawRect(cx, chy, cw, 13);
			g.setColor(active ? TerminalKit.AMBERHI : TerminalKit.LABEL);
			g.drawString(labels[i], cx + 4, chy + 10);
			if (chipOut != null) { chipOut.add(0, new Rectangle(cx, chy, cw + 1, 14)); }
			right = cx - 3;
		}
	}

	private static void trendMark(Graphics2D g, int x, int y, double trend)
	{
		g.setFont(TerminalKit.monoB(11));
		if (trend > 0.3) { g.setColor(TerminalKit.GREEN); g.drawString("▲", x, y); }
		else if (trend < -0.3) { g.setColor(TerminalKit.RED); g.drawString("▼", x, y); }
		else { g.setColor(TerminalKit.DIM); g.drawString("·", x, y); }
	}

	private static String name(FlipData f)
	{
		return f.getName() == null ? ("#" + f.getGeId()) : f.getName();
	}

	private static String clip(String s, FontMetrics fm, int maxW)
	{
		if (s == null) { return ""; }
		if (fm.stringWidth(s) <= maxW) { return s; }
		final String ell = "…";
		int n = s.length();
		while (n > 0 && fm.stringWidth(s.substring(0, n) + ell) > maxW) { n--; }
		return s.substring(0, n) + ell;
	}
}

package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's LEFT column: three stacked terminal panels docked to the left of the
 * Grand Exchange window - OPPORTUNITY RADAR (top flips by EV/hr), FRESH DIPS (live
 * dump movers) and TOP MOVERS (biggest gainers). All three read the live ranked
 * board (Trader Pro), so the column only shows when market data is live and there
 * is room to the left. Opt-in via config.terminalDesk(); look from TerminalKit.
 */
class TerminalRadarOverlay extends Overlay
{
	static final int W = 300;
	private static final int GAP = 8;
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

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, TOP_Y);
		try
		{
			paintColumn(g, W, availH, flips, catches);
		}
		finally
		{
			g.translate(-x, -TOP_Y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(W, availH);
	}

	private static final int RADAR_ROW = 17;
	private static final int LIST_ROW = 16;
	private static final int SUBHEAD = 14;

	/** Panel height for a section: title strip + optional sub-header + rows + pad. */
	private static int sectionH(int rows, boolean subhead)
	{
		return 32 + (subhead ? SUBHEAD : 0) + rows * (subhead ? RADAR_ROW : LIST_ROW) + 6;
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintColumn(Graphics2D g, int w, int availH, List<FlipData> flips, List<CatchData> catches)
	{
		TerminalKit.hints(g);

		// Fresh dips: live, actionable dumps only. Drop stale moves (already ended /
		// faded / recovering) and data-artifact spikes (a "real" dip almost never
		// exceeds ~85%), so vendor-junk noise like arrows and lockpicks stays out.
		final List<CatchData> dips = new ArrayList<>();
		for (final CatchData c : catches)
		{
			if (c == null || c.getPctMove() >= 0 || Math.abs(c.getPctMove()) >= 85)
			{
				continue;
			}
			final String st = c.getState() == null ? "" : c.getState().toLowerCase();
			if (st.equals("ended") || st.equals("faded") || st.equals("recovering"))
			{
				continue;
			}
			dips.add(c);
		}
		// Actionable first (the engine's catchable reads), then sharpest drop.
		dips.sort(Comparator.comparing((CatchData c) -> !c.isCatchable())
			.thenComparingDouble(CatchData::getPctMove));

		// Top movers: board rows rising the hardest.
		final List<FlipData> gainers = new ArrayList<>();
		for (final FlipData f : flips)
		{
			if (f != null && f.getTrendPct() > 0)
			{
				gainers.add(f);
			}
		}
		gainers.sort(Comparator.comparingDouble(FlipData::getTrendPct).reversed());

		int radarRows = Math.min(flips.size(), 10);
		int dipRows = Math.min(dips.size(), 5);
		int gainRows = Math.min(gainers.size(), 8);

		// Fit to the available height: trim the radar first, then movers, then dips.
		while (radarRows + dipRows + gainRows > 0
			&& sectionH(radarRows, true) + gap(dipRows) + sectionH(dipRows, false)
				+ gap(gainRows) + sectionH(gainRows, false) > availH)
		{
			if (radarRows > 4) { radarRows--; }
			else if (gainRows > 0) { gainRows--; }
			else if (dipRows > 0) { dipRows--; }
			else { radarRows--; }
		}

		int y = 0;
		if (radarRows > 0)
		{
			y = paintRadar(g, w, y, flips, radarRows) + GAP;
		}
		if (dipRows > 0)
		{
			y = paintDips(g, w, y, dips, dipRows) + GAP;
		}
		if (gainRows > 0)
		{
			paintGainers(g, w, y, gainers, gainRows);
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
		final int h = sectionH(rows, false);
		final int cy = TerminalKit.panel(g, 0, y, w, h, "FRESH DIPS  ·  DUMP CATCHER");
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

	private static int paintGainers(Graphics2D g, int w, int y, List<FlipData> gainers, int rows)
	{
		final int h = sectionH(rows, false);
		final int cy = TerminalKit.panel(g, 0, y, w, h, "TOP MOVERS  ·  GAINERS");
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		for (int i = 0; i < rows; i++)
		{
			final FlipData f = gainers.get(i);
			final int ry = cy + i * LIST_ROW;
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.GREEN);
			g.drawString("▲", 8, ry);
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(name(f), fm, 190), 24, ry);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.GREEN);
			TerminalKit.rt(g, String.format("+%.1f%%", f.getTrendPct()), w - 10, ry);
		}
		return y + h;
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

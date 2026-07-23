package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's SESSION · FLOW strip, docked to the BOTTOM edge of the Grand Exchange
 * window: this session's realised profit, gp/hr, flips, win rate, tax paid and
 * average ROI - all from your own flip log (no server call, works for free keys).
 * Opt-in via config.terminalDesk(); look from TerminalKit.
 */
class TerminalSessionOverlay extends Overlay
{
	private static final int H = 56;
	private static final int GAP = 4;

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalSessionOverlay(Client client, PriceCheckPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.terminalDesk() || !plugin.isGrandExchangeOpen())
		{
			return null;
		}
		final Rectangle ge = plugin.geGridBounds();
		final FlipLogEngine.Summary s = plugin.flipSummary();
		if (ge == null || s == null)
		{
			return null;
		}
		final int x = ge.x;
		final int y = ge.y + ge.height + GAP;
		if (y + H > client.getCanvasHeight() - 4)
		{
			return null;   // no room below the GE
		}

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, y);
		try
		{
			paintStrip(g, ge.width, s);
		}
		finally
		{
			g.translate(-x, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(ge.width, H);
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintStrip(Graphics2D g, int w, FlipLogEngine.Summary s)
	{
		TerminalKit.hints(g);
		TerminalKit.panel(g, 0, 0, w, H, "SESSION  ·  FLOW");

		final String today = (s.todayProfit >= 0 ? "+" : "-") + TerminalKit.gp(Math.abs(s.todayProfit));
		final String gphr = s.sessionGpHr == Long.MIN_VALUE ? "-"
			: (s.sessionGpHr >= 0 ? "+" : "-") + TerminalKit.gp(Math.abs(s.sessionGpHr));
		final String win = s.winRatePct < 0 ? "-" : s.winRatePct + "%";
		final String roi = Double.isNaN(s.avgRoiPct) ? "-" : String.format("%+.1f%%", s.avgRoiPct);

		// Six evenly-spaced KPI cells across the strip width.
		final String[] labels = {"REALIZED TODAY", "GP / HR", "FLIPS", "WIN", "TAX PAID", "AVG ROI"};
		final String[] values = {today, gphr, Integer.toString(s.allFlips), win, TerminalKit.gp(s.allTax), roi};
		final Color[] colors = {
			s.todayProfit >= 0 ? TerminalKit.GREEN : TerminalKit.RED,
			s.sessionGpHr == Long.MIN_VALUE ? TerminalKit.DIM : s.sessionGpHr >= 0 ? TerminalKit.GREEN : TerminalKit.RED,
			TerminalKit.AMBER,
			s.winRatePct < 0 ? TerminalKit.DIM : s.winRatePct >= 50 ? TerminalKit.GREEN : TerminalKit.AMBER,
			TerminalKit.LABEL,
			Double.isNaN(s.avgRoiPct) ? TerminalKit.DIM : s.avgRoiPct >= 0 ? TerminalKit.GREEN : TerminalKit.RED,
		};
		final int n = labels.length;
		final int colW = (w - 16) / n;
		for (int i = 0; i < n; i++)
		{
			final int cx = 8 + i * colW;
			g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
			g.drawString(labels[i], cx, 36);
			g.setFont(TerminalKit.monoB(15)); g.setColor(colors[i]);
			g.drawString(values[i], cx, 50);
		}
	}
}

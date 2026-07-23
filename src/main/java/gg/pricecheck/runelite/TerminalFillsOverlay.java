package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's RECENT FLIPS panel, docked to the lower-right of the Grand Exchange
 * (opposite the top-right blotter): your most recently closed flips with their
 * realised profit and age, from your own flip log. Opt-in via config.terminalDesk();
 * look from TerminalKit.
 */
class TerminalFillsOverlay extends Overlay
{
	static final int W = 292;
	private static final int ROW = 16;
	private static final int MAX_ROWS = 8;

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalFillsOverlay(Client client, PriceCheckPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		plugin.noteFillsBottom(-1);
		if (!plugin.terminalDesk() || !plugin.isGrandExchangeOpen())
		{
			return null;
		}
		final Rectangle ge = plugin.geGridBounds();
		final FlipLogEngine.Summary s = plugin.flipSummary();
		if (ge == null || s == null || s.recent == null || s.recent.isEmpty())
		{
			return null;
		}
		final int x = ge.x + ge.width + 8;
		if (x + W > client.getCanvasWidth() - 4)
		{
			return null;   // no room to the right
		}
		// Stack directly under the blotter to form one tight right column; if the
		// blotter isn't docked right this frame (e.g. during set-up, where it yields
		// to the order ticket), top-align so recent-flips leads the column.
		final int bb = plugin.blotterBottomY();
		final int y = bb > 0 ? bb + 8 : 8;
		// Grow downward, but stop above the ticker and the chat box.
		int floorY = client.getCanvasHeight() - TerminalTickerOverlay.H - 8;
		final Rectangle cb = plugin.chatboxBounds();
		if (cb != null && cb.height > 0)
		{
			floorY = Math.min(floorY, cb.y - TerminalTickerOverlay.H - 8);
		}
		final int maxH = floorY - y;
		int rowN = Math.min(s.recent.size(), MAX_ROWS);
		while (rowN > 0 && 32 + rowN * ROW + 6 > maxH)
		{
			rowN--;
		}
		if (rowN <= 0)
		{
			return null;
		}
		final long now = System.currentTimeMillis();
		final int h = 32 + rowN * ROW + 6;

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, y);
		try
		{
			paintFills(g, W, s.recent, rowN, now);
		}
		finally
		{
			g.translate(-x, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		plugin.noteFillsBottom(y + h);
		return new Dimension(W, h);
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintFills(Graphics2D g, int w, List<FlipLogEngine.Flip> recent, int rowN, long nowMs)
	{
		TerminalKit.hints(g);
		final int h = 32 + rowN * ROW + 6;
		int cy = TerminalKit.panel(g, 0, 0, w, h, "RECENT FLIPS  ·  CLOSED");
		final FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < rowN; i++)
		{
			final FlipLogEngine.Flip f = recent.get(i);
			final boolean up = f.profit >= 0;
			final Color c = up ? TerminalKit.GREEN : TerminalKit.RED;
			g.setFont(TerminalKit.monoB(11)); g.setColor(c);
			g.drawString(up ? "▲" : "▼", 8, cy);
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(f.name == null ? ("#" + f.itemId) : f.name, fm, 150), 24, cy);
			g.setFont(TerminalKit.monoB(11)); g.setColor(c);
			TerminalKit.rt(g, (up ? "+" : "-") + TerminalKit.gp(Math.abs(f.profit)), w - 46, cy);
			g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.DIM);
			TerminalKit.rt(g, age(nowMs - f.closedAt), w - 10, cy);
			cy += ROW;
		}
	}

	private static String age(long ms)
	{
		final long s = Math.max(0, ms) / 1000;
		if (s < 60) { return s + "s"; }
		if (s < 3600) { return (s / 60) + "m"; }
		if (s < 86400) { return (s / 3600) + "h"; }
		return (s / 86400) + "d";
	}

	private static String clip(String s, FontMetrics fm, int maxW)
	{
		if (s == null) { return ""; }
		if (fm.stringWidth(s) <= maxW) { return s; }
		int n = s.length();
		while (n > 0 && fm.stringWidth(s.substring(0, n) + "…") > maxW) { n--; }
		return s.substring(0, n) + "…";
	}
}

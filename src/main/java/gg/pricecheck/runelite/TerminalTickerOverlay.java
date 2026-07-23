package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's price TICKER: a thin full-width strip along the bottom of the screen
 * that scrolls the live board - item, direction and price - like an exchange tape.
 * Reads the ranked board (Trader Pro); only shows when market data is live and the
 * GE is open. Opt-in via config.terminalDesk(); look from TerminalKit.
 */
class TerminalTickerOverlay extends Overlay
{
	static final int H = 24;
	private static final int SCROLL_MS_PER_PX = 22;   // ~45 px/sec

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalTickerOverlay(Client client, PriceCheckPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.terminalDesk() || !plugin.marketDataOk() || !plugin.isGrandExchangeOpen())
		{
			return null;
		}
		final List<FlipData> flips = plugin.boardFlips();
		if (flips.isEmpty())
		{
			return null;
		}
		final int w = client.getCanvasWidth();
		// Sit at the very bottom, but lift above the chat box so the tape never
		// covers the chat input line.
		int y = client.getCanvasHeight() - H;
		final Rectangle cb = plugin.chatboxBounds();
		if (cb != null && cb.height > 0)
		{
			y = Math.min(y, cb.y - H);
		}
		if (y < 0)
		{
			return null;
		}
		final int offset = (int) (System.currentTimeMillis() / SCROLL_MS_PER_PX);

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(0, y);
		try
		{
			paintTicker(g, w, flips, offset);
		}
		finally
		{
			g.translate(0, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(w, H);
	}

	/** Pure drawing (0,0-origin). offset scrolls the tape leftward (0 = static, for
	 *  the headless preview). Draws the strip twice for a seamless wrap. */
	static void paintTicker(Graphics2D g, int w, List<FlipData> flips, int offset)
	{
		TerminalKit.hints(g);
		g.setColor(TerminalKit.PANEL); g.fillRect(0, 0, w, H);
		g.setColor(TerminalKit.BORDER); g.drawLine(0, 0, w, 0);

		g.setFont(TerminalKit.monoB(12));
		final FontMetrics fm = g.getFontMetrics();
		final int n = Math.min(flips.size(), 24);
		final int contentW = stripWidth(fm, flips, n);
		if (contentW <= 0)
		{
			return;
		}
		final Shape clip = g.getClip();
		g.clipRect(0, 0, w, H);
		if (contentW <= w)
		{
			drawStrip(g, fm, flips, n, 8);
		}
		else
		{
			final int start = -(offset % contentW);
			drawStrip(g, fm, flips, n, start);
			drawStrip(g, fm, flips, n, start + contentW);
		}
		g.setClip(clip);
	}

	private static final int GAP = 26;

	private static int stripWidth(FontMetrics fm, List<FlipData> flips, int n)
	{
		int x = 0;
		for (int i = 0; i < n; i++)
		{
			x += segW(fm, flips.get(i)) + GAP;
		}
		return x;
	}

	private static int segW(FontMetrics fm, FlipData f)
	{
		return fm.stringWidth(label(f) + "  " + TerminalKit.gp(price(f)));
	}

	private static void drawStrip(Graphics2D g, FontMetrics fm, List<FlipData> flips, int n, int x0)
	{
		final int baseline = H / 2 + 5;
		int x = x0;
		for (int i = 0; i < n; i++)
		{
			final FlipData f = flips.get(i);
			final double t = f.getTrendPct();
			final Color c = t > 0.3 ? TerminalKit.GREEN : t < -0.3 ? TerminalKit.RED : TerminalKit.LABEL;
			final String arrow = t > 0.3 ? "▲" : t < -0.3 ? "▼" : "·";
			g.setColor(TerminalKit.AMBER);
			g.drawString(label(f), x, baseline);
			final int lw = fm.stringWidth(label(f));
			g.setColor(c);
			g.drawString(arrow + TerminalKit.gp(price(f)), x + lw + fm.stringWidth(" "), baseline);
			x += segW(fm, f) + GAP;
		}
	}

	private static long price(FlipData f)
	{
		return f.getSell() > 0 ? f.getSell() : f.getBuy();
	}

	private static String label(FlipData f)
	{
		final String nm = f.getName() == null ? ("#" + f.getGeId()) : f.getName();
		return nm.length() > 16 ? nm.substring(0, 16) : nm;
	}
}

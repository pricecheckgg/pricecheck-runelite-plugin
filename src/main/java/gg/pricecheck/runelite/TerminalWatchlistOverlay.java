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
 * The desk's WATCHLIST, docked in the top-centre box above the Grand Exchange: the
 * entry targets you're tracking (your watch price + how close the market is), then
 * the board's top picks by EV/hr to fill. Fills the gap between the screen top and
 * the held panel. Opt-in via config.terminalDesk(); look from TerminalKit.
 */
class TerminalWatchlistOverlay extends Overlay
{
	private static final int TOP_Y = 8;
	private static final int ROW = 16;
	private static final int MIN_H = 70;

	/** A tracked entry target. state: 2 = at/below target (buy), 1 = near, 0 = wait. */
	static final class Watch
	{
		final String name;
		final long target;
		final int state;
		Watch(String name, long target, int state) { this.name = name; this.target = target; this.state = state; }
	}

	/** A board pick to fill the list. */
	static final class Pick
	{
		final String name;
		final long buy;
		final long evPerHr;
		Pick(String name, long buy, long evPerHr) { this.name = name; this.buy = buy; this.evPerHr = evPerHr; }
	}

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalWatchlistOverlay(Client client, PriceCheckPlugin plugin)
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
		final Rectangle ge = plugin.geGridBounds();
		if (ge == null)
		{
			return null;
		}
		final List<Watch> watch = buildWatch();
		final List<Pick> picks = buildPicks(watch);
		if (watch.isEmpty() && picks.isEmpty())
		{
			return null;
		}
		// Fill the box between the screen top and the held panel (or the status band
		// when nothing is held).
		final int heldTop = plugin.heldTopY();
		final int bottom = heldTop > 0 ? heldTop - 4 : ge.y - 44;
		final int h = bottom - TOP_Y;
		if (h < MIN_H)
		{
			return null;   // not enough room in the top box
		}
		final int x = ge.x;

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, TOP_Y);
		try
		{
			paintWatchlist(g, ge.width, h, watch, picks);
		}
		finally
		{
			g.translate(-x, -TOP_Y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(ge.width, h);
	}

	/** Watch targets: tracked items you don't hold yet, closest to their price first. */
	private List<Watch> buildWatch()
	{
		final List<Watch> out = new ArrayList<>();
		for (final TrackedItem t : plugin.trackedItems())
		{
			if (t == null || t.isHeld() || t.getWatchBuy() <= 0)
			{
				continue;
			}
			final FlipData live = plugin.liveFor(t.getGeId());
			final long marketBuy = live != null ? live.getBuy() : 0;
			int state = 0;
			if (marketBuy > 0)
			{
				if (marketBuy <= t.getWatchBuy()) { state = 2; }
				else if (marketBuy <= t.getWatchBuy() * 1.02) { state = 1; }
			}
			out.add(new Watch(t.getName() == null ? "#" + t.getGeId() : t.getName(), t.getWatchBuy(), state));
		}
		// At-target first, then near, then wait.
		out.sort(Comparator.comparingInt((Watch wch) -> -wch.state));
		return out;
	}

	/** Top board picks by EV, excluding anything already on the watch list. */
	private List<Pick> buildPicks(List<Watch> watch)
	{
		final java.util.Set<String> have = new java.util.HashSet<>();
		for (final Watch w : watch)
		{
			have.add(w.name);
		}
		final List<Pick> out = new ArrayList<>();
		for (final FlipData f : plugin.boardFlips())
		{
			final String nm = f.getName() == null ? "#" + f.getGeId() : f.getName();
			if (have.contains(nm))
			{
				continue;
			}
			out.add(new Pick(nm, f.getBuy(), f.getEvPerHr()));
		}
		return out;
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. Fills
	 *  the whole box (height h); rows that don't fit are dropped. */
	static void paintWatchlist(Graphics2D g, int w, int h, List<Watch> watch, List<Pick> picks)
	{
		TerminalKit.hints(g);
		int cy = TerminalKit.panel(g, 0, 0, w, h, "WATCHLIST  ·  YOUR TARGETS");
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		final int nameClip = w - 8 - 150;
		final int buyXr = w - 92;
		final int tagXr = w - 10;
		final int limitY = h - 6;

		int watchN = Math.min(watch.size(), Math.max(0, (limitY - cy) / ROW));
		for (int i = 0; i < watchN; i++)
		{
			final Watch wc = watch.get(i);
			final Color sc = wc.state == 2 ? TerminalKit.GREEN : wc.state == 1 ? TerminalKit.AMBER : TerminalKit.DIM;
			g.setFont(TerminalKit.monoB(11)); g.setColor(sc);
			g.drawString("◆", 8, cy);
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(wc.name, fm, nameClip - 16), 24, cy);
			g.setFont(TerminalKit.mono(10)); g.setColor(TerminalKit.LABEL);
			TerminalKit.rt(g, "buy " + TerminalKit.gp(wc.target), buyXr, cy);
			g.setFont(TerminalKit.monoB(9)); g.setColor(sc);
			TerminalKit.rt(g, wc.state == 2 ? "BUY" : wc.state == 1 ? "NEAR" : "WAIT", tagXr, cy);
			cy += ROW;
		}

		// Top picks fill the remaining space, under a thin sub-header.
		if (cy + ROW + ROW <= limitY && !picks.isEmpty())
		{
			g.setColor(TerminalKit.GRID); g.drawLine(8, cy - 4, w - 8, cy - 4);
			g.setFont(TerminalKit.mono(8)); g.setColor(TerminalKit.DIM);
			g.drawString(watch.isEmpty() ? "TOP PICKS  ·  EV/HR" : "TOP PICKS  ·  BY EV/HR", 8, cy + 8);
			TerminalKit.rt(g, "EV/HR", tagXr, cy + 8);
			cy += ROW + 4;
			final int pickN = Math.min(picks.size(), Math.max(0, (limitY - cy) / ROW));
			for (int i = 0; i < pickN; i++)
			{
				final Pick p = picks.get(i);
				g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
				g.drawString(clip(p.name, fm, nameClip), 8, cy);
				g.setFont(TerminalKit.mono(10)); g.setColor(TerminalKit.LABEL);
				TerminalKit.rt(g, "buy " + TerminalKit.gp(p.buy), buyXr, cy);
				g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.AMBERHI);
				TerminalKit.rt(g, TerminalKit.gp(p.evPerHr), tagXr, cy);
				cy += ROW;
			}
		}
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

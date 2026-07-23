package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's WATCHLIST, docked in the top-centre box above the Grand Exchange: the
 * entry targets you're tracking (your watch price + how close the market is), then
 * the board's top picks by EV/hr to fill. Content-sized and anchored to the top of
 * the held panel so it always connects to the centre column with no dead space. If
 * you track more favourites than fit, hold/press Shift to page through them (the
 * plugin's shift-to-reveal convention). Opt-in via config.terminalDesk().
 */
class TerminalWatchlistOverlay extends Overlay
{
	private static final int TOP_Y = 8;
	private static final int ROW = 16;
	private static final int TITLE_H = 32;
	private static final int DIVIDER_H = 20;
	private static final int MIN_H = 58;

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
	// Shift-paging state for a long favourites list.
	private boolean lastShift;
	private int favPage;

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
		// Box bottom = the held panel's top (flush), else the status band.
		final int heldTop = plugin.heldTopY();
		final int boxBottom = heldTop > 0 ? heldTop - 4 : ge.y - 44;
		final int maxH = boxBottom - TOP_Y;
		if (maxH < MIN_H)
		{
			return null;
		}
		final int availRows = Math.max(1, (maxH - TITLE_H - 6) / ROW);

		// Advance the favourites page on each Shift press (edge-triggered).
		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		final boolean pressed = shift && !lastShift;
		lastShift = shift;

		// Decide the layout: favourites page (they don't fit) vs favourites + picks.
		final List<Watch> watchPage;
		final List<Pick> showPicks;
		int pageCount = 1;
		final boolean picksWanted = !picks.isEmpty();
		if (!watch.isEmpty() && watch.size() > availRows - (picksWanted ? 2 : 0))
		{
			final int perPage = availRows;
			pageCount = (watch.size() + perPage - 1) / perPage;
			if (pressed) { favPage++; }
			favPage = ((favPage % pageCount) + pageCount) % pageCount;
			final int from = favPage * perPage;
			watchPage = new ArrayList<>(watch.subList(from, Math.min(from + perPage, watch.size())));
			showPicks = Collections.emptyList();
		}
		else
		{
			favPage = 0;
			watchPage = watch;
			final int remain = availRows - watch.size();
			showPicks = picksWanted && remain >= 2
				? new ArrayList<>(picks.subList(0, Math.min(remain - 1, picks.size())))
				: Collections.<Pick>emptyList();
		}

		final int contentH = TITLE_H
			+ watchPage.size() * ROW
			+ (showPicks.isEmpty() ? 0 : DIVIDER_H + showPicks.size() * ROW)
			+ 6;
		final int h = Math.min(contentH, maxH);
		final int y = boxBottom - h;   // bottom-anchored, flush against the held panel
		final int x = ge.x;
		final int fp = favPage;
		final int pc = pageCount;

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, y);
		try
		{
			paintWatchlist(g, ge.width, h, watchPage, showPicks, fp, pc);
		}
		finally
		{
			g.translate(-x, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(ge.width, h);
	}

	/** Watch targets: tracked items you don't hold yet, at-target first. */
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

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintWatchlist(Graphics2D g, int w, int h, List<Watch> watch, List<Pick> picks, int favPage, int pageCount)
	{
		TerminalKit.hints(g);
		int cy = TerminalKit.panel(g, 0, 0, w, h, "WATCHLIST  ·  YOUR TARGETS");
		// Page indicator on the title strip when the favourites span multiple pages.
		if (pageCount > 1)
		{
			g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
			TerminalKit.rt(g, "▸ " + (favPage + 1) + "/" + pageCount + "  shift", w - 8, 13);
		}
		final FontMetrics fm = g.getFontMetrics(TerminalKit.mono(11));
		final int nameClip = w - 8 - 150;
		final int buyXr = w - 92;
		final int tagXr = w - 10;

		for (final Watch wc : watch)
		{
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

		if (!picks.isEmpty())
		{
			g.setColor(TerminalKit.GRID); g.drawLine(8, cy - 4, w - 8, cy - 4);
			g.setFont(TerminalKit.mono(8)); g.setColor(TerminalKit.DIM);
			g.drawString(watch.isEmpty() ? "TOP PICKS  ·  EV/HR" : "TOP PICKS  ·  BY EV/HR", 8, cy + 8);
			TerminalKit.rt(g, "EV/HR", tagXr, cy + 8);
			cy += DIVIDER_H;
			for (final Pick p : picks)
			{
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

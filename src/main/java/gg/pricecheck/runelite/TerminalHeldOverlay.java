package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's HELD panel, docked ABOVE the Grand Exchange window: the positions you
 * are sitting on (open lots from your flip log) with cost basis and live unrealised
 * P&L at the current board price. Distinct from the blotter, which lists active
 * offers. Opt-in via config.terminalDesk(); look from TerminalKit.
 */
class TerminalHeldOverlay extends Overlay
{
	private static final int ROW = 18;
	private static final int STATUS_BAND = 40;   // room reserved for the status bar above the GE
	private static final int MAX_ROWS = 4;

	/** One held-position row, pre-resolved so the draw + preview share a shape. */
	static final class Row
	{
		final String name;
		final long qty;
		final long unitCost;
		final long uPnl;
		final boolean live;
		Row(String name, long qty, long unitCost, long uPnl, boolean live)
		{
			this.name = name; this.qty = qty; this.unitCost = unitCost; this.uPnl = uPnl; this.live = live;
		}
	}

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalHeldOverlay(Client client, PriceCheckPlugin plugin)
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
		if (ge == null || s == null || s.openLots == null || s.openLots.isEmpty())
		{
			return null;
		}
		final List<Row> rows = buildRows(s.openLots);
		if (rows.isEmpty())
		{
			return null;
		}
		final int h = 32 + rows.size() * ROW + 6;
		final int x = ge.x;
		final int y = ge.y - STATUS_BAND - h - 4;
		if (y < 6)
		{
			return null;   // no room above the GE
		}

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, y);
		try
		{
			paintHeld(g, ge.width, rows);
		}
		finally
		{
			g.translate(-x, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(ge.width, h);
	}

	/** Aggregate the open lots per item and price them at the live board sell. */
	private List<Row> buildRows(List<FlipLogEngine.Lot> lots)
	{
		final List<Row> rows = new ArrayList<>();
		// The lots are already per-item lot entries; sum qty/cost by item id.
		final java.util.Map<Integer, long[]> agg = new java.util.LinkedHashMap<>();
		final java.util.Map<Integer, String> names = new java.util.HashMap<>();
		for (final FlipLogEngine.Lot l : lots)
		{
			if (l == null || l.qty <= 0)
			{
				continue;
			}
			final long[] a = agg.computeIfAbsent(l.itemId, k -> new long[2]);
			a[0] += l.qty;
			a[1] += l.cost;
			if (l.name != null)
			{
				names.put(l.itemId, l.name);
			}
		}
		for (final java.util.Map.Entry<Integer, long[]> e : agg.entrySet())
		{
			final long qty = e.getValue()[0];
			final long cost = e.getValue()[1];
			if (qty <= 0)
			{
				continue;
			}
			final long unit = cost / qty;
			final FlipData live = plugin.liveFor(e.getKey());
			final long sell = live != null ? live.getSell() : 0;
			final boolean has = sell > 0;
			final long uPnl = has ? qty * GeTax.net(unit, sell) : 0;
			rows.add(new Row(names.getOrDefault(e.getKey(), "#" + e.getKey()), qty, unit, uPnl, has));
			if (rows.size() >= MAX_ROWS)
			{
				break;
			}
		}
		return rows;
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintHeld(Graphics2D g, int w, List<Row> rows)
	{
		TerminalKit.hints(g);
		final int h = 32 + rows.size() * ROW + 6;
		int cy = TerminalKit.panel(g, 0, 0, w, h, "HELD  ·  YOUR POSITIONS");
		final FontMetrics fm = g.getFontMetrics();
		for (final Row r : rows)
		{
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.AMBER);
			g.drawString(clip(r.name, fm, 150), 8, cy);
			g.setFont(TerminalKit.mono(10)); g.setColor(TerminalKit.LABEL);
			g.drawString(r.qty + " @ " + TerminalKit.gp(r.unitCost), 170, cy);
			if (r.live)
			{
				g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
				TerminalKit.rt(g, "uP&L", w - 96, cy);
				g.setFont(TerminalKit.monoB(12));
				g.setColor(r.uPnl >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
				TerminalKit.rt(g, (r.uPnl >= 0 ? "+" : "-") + TerminalKit.gp(Math.abs(r.uPnl)), w - 10, cy);
			}
			else
			{
				g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.DIM);
				TerminalKit.rt(g, "no live price", w - 10, cy);
			}
			cy += ROW;
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

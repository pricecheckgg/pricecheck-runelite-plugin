package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The desk's ORDER ticket, docked to the right of the Grand Exchange while you set
 * up an offer: a crystal-clear, live profit read of the offer you're typing (your
 * price vs the resale/cost reference, margin after tax, and total profit for the
 * quantity), with your own trade log for the item beneath it - each sale tagged
 * with the profit it made. Replaces the classic "Your trades" log. Opt-in via
 * config.terminalDesk(); look from TerminalKit.
 */
class TerminalOrderOverlay extends Overlay
{
	static final int W = 292;   // match the recent-flips / blotter column width
	private static final int GE_GROUP = 465;
	private static final int[] SETUP_PANELS = { 15, 26 };
	private static final int COINS_ITEM = 995;
	private static final Pattern PRICE = Pattern.compile("^[0-9,]+ coins$");
	private static final int LOG_ROW = 15;
	private static final int MAX_LOG = 8;
	private static final int TICKET_H = 146;

	/** Resolved ticket the paint method draws (shared with the preview). */
	static final class Ticket
	{
		int geId;
		String item;
		boolean sell;
		long price;      // your entered price
		long qty;
		long ref;        // resale (buy side) or cost basis (sell side)
		String refLabel; // "resells at" / "your cost" / "vs buy"
		long netEa;      // profit per item after tax
		long total;      // netEa * qty
		double roi;
		boolean priced;  // false until a price is entered
	}

	private final Client client;
	private final PriceCheckPlugin plugin;

	TerminalOrderOverlay(Client client, PriceCheckPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.terminalDesk() || !plugin.marketDataOk())
		{
			return null;
		}
		final Widget setup = setupPanel();
		if (setup == null)
		{
			return null;
		}
		final Ticket t = readTicket(setup);
		if (t == null)
		{
			return null;
		}
		final Rectangle ge = plugin.geGridBounds();
		if (ge == null)
		{
			return null;
		}
		final int x = ge.x + ge.width + 8;
		if (x + W > client.getCanvasWidth() - 4)
		{
			return null;   // no room to the right
		}
		final long[][] trades = plugin.ownTradesFor(t.geId, MAX_LOG);
		final int logN = trades == null ? 0 : Math.min(trades.length, MAX_LOG);
		final int h = TICKET_H + (logN > 0 ? 24 + logN * LOG_ROW : 0);
		// Sit directly beneath the recent-flips ("closed swaps") panel so the right
		// column reads Recent Flips -> Order ticket; top-align if it isn't shown.
		final int fb = plugin.fillsBottomY();
		final int y = fb > 0 ? fb + 8 : 8;
		final long nowSec = System.currentTimeMillis() / 1000L;

		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, y);
		try
		{
			paintOrder(g, W, t, trades, logN, nowSec);
		}
		finally
		{
			g.translate(-x, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return new Dimension(W, h);
	}

	/** Read the side, entered price and quantity off the set-up panel and compute
	 *  the after-tax profit. Mirrors the card's set-up parse. */
	private Ticket readTicket(Widget setup)
	{
		int itemId = 0;
		boolean sell = false;
		long entered = 0;
		long offerTotal = -1;
		String name = null;
		for (final Widget c : allChildren(setup))
		{
			if (c == null)
			{
				continue;
			}
			if (c.getItemId() > 0 && c.getItemId() != COINS_ITEM && itemId == 0)
			{
				itemId = c.getItemId();
			}
			final String txt = c.getText() == null ? "" : c.getText().replaceAll("<[^>]+>", "").trim();
			if (txt.isEmpty())
			{
				continue;
			}
			if (txt.equals("Sell offer")) { sell = true; }
			else if (txt.equals("Buy offer")) { sell = false; }
			else if (PRICE.matcher(txt).matches())
			{
				final long v = Long.parseLong(txt.replaceAll("[^0-9]", ""));
				if (entered <= 0) { entered = v; }
				else if (offerTotal < 0) { offerTotal = v; }
			}
		}
		if (itemId <= 0)
		{
			itemId = plugin.setupItem();
		}
		if (itemId <= 0)
		{
			return null;
		}
		final FlipData live = plugin.liveFor(itemId);
		if (live != null && live.getName() != null)
		{
			name = live.getName();
		}
		final Ticket t = new Ticket();
		t.item = name != null ? name : "#" + itemId;
		t.sell = sell;
		t.price = entered;
		t.qty = entered > 0 && offerTotal > 0 && offerTotal % entered == 0 ? offerTotal / entered : 1;
		t.priced = entered > 0;
		t.geId = itemId;
		if (!t.priced)
		{
			return t;   // panel open, no price yet
		}
		if (sell)
		{
			final long[] hold = plugin.holdingFor(itemId);
			if (hold != null && hold[0] > 0)
			{
				t.ref = hold[1] / hold[0];   // avg cost basis
				t.refLabel = "your cost";
			}
			else
			{
				t.ref = live != null ? live.getBuy() : 0;
				t.refLabel = "vs buy";
			}
			t.netEa = t.ref > 0 ? GeTax.net(t.ref, entered) : 0;
		}
		else
		{
			t.ref = live != null && live.getSell() > 0 ? live.getSell() : entered;
			t.refLabel = "resells at";
			t.netEa = GeTax.net(entered, t.ref);
		}
		t.total = t.netEa * t.qty;
		t.roi = t.ref > 0 ? t.netEa * 100.0 / (sell ? t.ref : entered) : 0;
		return t;
	}

	/** Pure drawing (0,0-origin) so the preview harness can render it headless. */
	static void paintOrder(Graphics2D g, int w, Ticket t, long[][] trades, int logN, long nowSec)
	{
		TerminalKit.hints(g);
		final int h = TICKET_H + (logN > 0 ? 24 + logN * LOG_ROW : 0);
		TerminalKit.panel(g, 0, 0, w, h, "ORDER TICKET");
		// side + item on the title strip's right
		g.setFont(TerminalKit.monoB(10));
		g.setColor(t.sell ? TerminalKit.RED : TerminalKit.GREEN);
		TerminalKit.rt(g, t.sell ? "SELL" : "BUY", w - 8, 13);

		final int L = 10, R = w - 10, colW = (R - L - 8) / 2;
		g.setFont(TerminalKit.monoB(12)); g.setColor(TerminalKit.AMBERHI);
		final FontMetrics nfm = g.getFontMetrics();
		g.drawString(clip(t.item, nfm, w - 20), L, 42);

		if (!t.priced)
		{
			g.setFont(TerminalKit.mono(11)); g.setColor(TerminalKit.DIM);
			g.drawString("type a price to preview profit", L, 74);
		}
		else
		{
			TerminalKit.cell(g, L, colW, 60, "YOUR PRICE", TerminalKit.commas(t.price), TerminalKit.AMBER);
			TerminalKit.cell(g, L + colW + 8, colW, 60,
				t.refLabel == null ? "REF" : t.refLabel.toUpperCase(),
				t.ref > 0 ? TerminalKit.commas(t.ref) : "-", TerminalKit.AMBER);
			TerminalKit.cell(g, L, colW, 90, "MARGIN/EA",
				(t.netEa >= 0 ? "+" : "") + TerminalKit.commas(t.netEa),
				t.netEa >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
			TerminalKit.cell(g, L + colW + 8, colW, 90, "ROI", signPct(t.roi),
				t.roi >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
			// hero: total profit for the whole order
			g.setColor(TerminalKit.GRID); g.drawLine(L, 116, R, 116);
			g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.LABEL);
			g.drawString(t.qty > 1 ? "PROFIT  ·  x" + t.qty : "PROFIT", L, 134);
			g.setFont(TerminalKit.monoB(17));
			g.setColor(t.total >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
			TerminalKit.rt(g, (t.total >= 0 ? "+" : "-") + TerminalKit.gp(Math.abs(t.total)), R, 137);
		}

		if (logN <= 0)
		{
			return;
		}
		int cy = TICKET_H + 20;
		g.setColor(TerminalKit.GRID); g.drawLine(L, cy - 12, R, cy - 12);
		g.setFont(TerminalKit.mono(8)); g.setColor(TerminalKit.DIM);
		g.drawString("YOUR TRADES  ·  " + t.item.toUpperCase(), L, cy - 2);
		TerminalKit.rt(g, "AGE", R, cy - 2);
		for (int i = 0; i < logN; i++)
		{
			final long[] tr = trades[i];
			final boolean buy = tr[3] == 1;
			final int ry = cy + 8 + i * LOG_ROW;
			g.setFont(TerminalKit.monoB(11)); g.setColor(buy ? TerminalKit.GREEN : TerminalKit.RED);
			g.drawString(buy ? "▲" : "▼", L, ry);
			g.setFont(TerminalKit.monoB(11)); g.setColor(TerminalKit.AMBER);
			String px = TerminalKit.commas(tr[1]);
			if (tr[2] > 1) { px += " x" + tr[2]; }
			g.drawString(px, L + 14, ry);
			// sells carry the realised profit; buys note whether you're still holding
			if (buy)
			{
				if (tr[4] == 1)
				{
					g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.DIM);
					TerminalKit.rt(g, "holding", R - 44, ry);
				}
			}
			else if (tr[5] != 0)
			{
				g.setFont(TerminalKit.monoB(11));
				g.setColor(tr[5] >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
				TerminalKit.rt(g, (tr[5] >= 0 ? "+" : "-") + TerminalKit.gp(Math.abs(tr[5])), R - 44, ry);
			}
			g.setFont(TerminalKit.mono(9)); g.setColor(TerminalKit.DIM);
			TerminalKit.rt(g, age(nowSec - tr[0] / 1000L), R, ry);
		}
	}

	private static String age(long s)
	{
		s = Math.max(0, s);
		if (s < 60) { return s + "s"; }
		if (s < 5400) { return (s / 60) + "m"; }
		if (s < 172800) { return (s / 3600) + "h"; }
		return (s / 86400) + "d";
	}

	private static String signPct(double v)
	{
		return (v >= 0 ? "+" : "-") + String.format("%.1f%%", Math.abs(v));
	}

	private Widget setupPanel()
	{
		for (final int child : SETUP_PANELS)
		{
			final Widget w = client.getWidget(GE_GROUP, child);
			if (w != null && !w.isHidden())
			{
				return w;
			}
		}
		return null;
	}

	private static Widget[] allChildren(Widget panel)
	{
		final Widget[] stat = panel.getStaticChildren();
		final Widget[] dyn = panel.getDynamicChildren();
		final int sl = stat == null ? 0 : stat.length;
		final int dl = dyn == null ? 0 : dyn.length;
		final Widget[] all = new Widget[sl + dl];
		if (sl > 0) { System.arraycopy(stat, 0, all, 0, sl); }
		if (dl > 0) { System.arraycopy(dyn, 0, all, sl, dl); }
		return all;
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

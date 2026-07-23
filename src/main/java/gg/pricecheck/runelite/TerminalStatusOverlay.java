package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Terminal status bar — a thin Bloomberg command strip docked to the TOP edge of
 * the Grand Exchange window: brand, cash, used offer slots, world, and a live
 * clock. Opt-in via config.terminalStatusBar(). Uses only client-readable state
 * (no server call), so it works for free keys too. First panel of the terminal
 * desk; the shared look lives in TerminalKit.
 */
class TerminalStatusOverlay extends Overlay
{
	private static final int COINS_ID = 995;
	private static final int GAP = 6;
	private static final int BAR_H = 30;   // unscaled design height
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	TerminalStatusOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.terminalStatusBar() && !plugin.terminalDesk())
		{
			return null;
		}
		final Rectangle ge = plugin.geGridBounds();
		if (ge == null || !plugin.isGrandExchangeOpen())
		{
			return null;
		}

		// The status bar is a fixed-height header, not a data panel: it does NOT
		// follow overlayScale() (Large/big mode) — scaling it up shrinks the fit
		// width and squeezes out fields. It always spans the GE width at 1:1.
		final double scale = 1.0;
		final int h = (int) Math.round(BAR_H * scale);
		final int w = ge.width;                       // span the GE width
		final int x = ge.x;
		int y = ge.y - h - GAP;                        // dock to the TOP edge
		if (y < 4)
		{
			y = 4;
		}

		// Save the AA hints (the pixel-font overlays run with them OFF; we need ON
		// for the mono font, and we restore so we don't affect the next overlay).
		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Object taa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.translate(x, y);
		g.scale(scale, scale);
		try
		{
			final FlipLogEngine.Summary sum = plugin.flipSummary();
			paintBar(g, (int) Math.round(w / scale), BAR_H, cash(), usedSlots(),
				client.getWorld(), LocalTime.now().format(CLOCK),
				sum != null ? sum.todayProfit : Long.MIN_VALUE);
		}
		finally
		{
			g.scale(1.0 / scale, 1.0 / scale);
			g.translate(-x, -y);
			if (aa != null) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa); }
			if (taa != null) { g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, taa); }
		}
		return null;
	}

	/** Pure drawing (0,0-origin, w x h) so the preview harness can render it headless.
	 *  Fully metrics-driven and self-clamping: fields are only drawn if they fit
	 *  before the right-pinned LIVE/clock block, so it never overlaps on a narrow
	 *  (single-offer) GE window — it just shows fewer fields. */
	static void paintBar(Graphics2D g, int w, int h, long cash, int slots, int world, String clock, long pnlToday)
	{
		TerminalKit.hints(g);
		g.setColor(TerminalKit.PANEL);
		g.fillRect(0, 0, w, h);
		g.setColor(TerminalKit.BORDER);
		g.drawRect(0, 0, w, h);

		// brand
		g.setFont(TerminalKit.monoB(14));
		g.setColor(TerminalKit.AMBERHI);
		g.drawString("PRICECHECK", 12, 20);
		final int brandW = g.getFontMetrics().stringWidth("PRICECHECK");
		g.setFont(TerminalKit.mono(9));
		g.setColor(TerminalKit.DIM);
		g.drawString("ENGINE", 12 + brandW + 8, 20);
		final int engineW = g.getFontMetrics().stringWidth("ENGINE");

		// RIGHT block first: clock (right-pinned) + LIVE label + dot. Compute its
		// left edge so the flowing fields on the left can stop before it.
		g.setFont(TerminalKit.monoB(12));
		final int clockW = g.getFontMetrics().stringWidth(clock);
		g.setColor(TerminalKit.AMBER);
		g.drawString(clock, w - 12 - clockW, 20);
		g.setFont(TerminalKit.mono(10));
		final int liveW = g.getFontMetrics().stringWidth("LIVE");
		final int liveX = w - 12 - clockW - 10 - liveW;
		g.setColor(TerminalKit.LABEL);
		g.drawString("LIVE", liveX, 19);
		g.setColor(TerminalKit.GREEN);
		g.fillOval(liveX - 11, 9, 6, 6);
		final int rEdge = liveX - 11 - 14;   // fields must end before here

		// LEFT fields, left-flowing, clamped so they never cross rEdge.
		int fx = 12 + brandW + 8 + engineW + 22;
		fx = field(g, fx, rEdge, "CASH", TerminalKit.commas(cash), TerminalKit.AMBER);
		fx = field(g, fx, rEdge, "SLOTS", slots + "/8", slots >= 8 ? TerminalKit.RED : TerminalKit.AMBER);
		fx = field(g, fx, rEdge, "WORLD", Integer.toString(world), TerminalKit.AMBER);
		if (pnlToday != Long.MIN_VALUE)
		{
			fx = field(g, fx, rEdge, "P&L TODAY",
				(pnlToday >= 0 ? "+" : "-") + TerminalKit.gp(Math.abs(pnlToday)),
				pnlToday >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		}
	}

	private static int field(Graphics2D g, int x, int rEdge, String label, String value, Color vc)
	{
		g.setFont(TerminalKit.mono(9));
		final int lw = g.getFontMetrics().stringWidth(label);
		g.setFont(TerminalKit.monoB(13));
		final int vw = g.getFontMetrics().stringWidth(value);
		final int fieldW = Math.max(lw, vw);
		if (x + fieldW > rEdge)
		{
			return x;   // no room on this window width — skip, never overlap
		}
		g.setFont(TerminalKit.mono(9));
		g.setColor(TerminalKit.LABEL);
		g.drawString(label, x, 12);
		g.setFont(TerminalKit.monoB(13));
		g.setColor(vc);
		g.drawString(value, x, 25);
		return x + fieldW + 22;
	}

	private long cash()
	{
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0L;
		}
		long c = 0L;
		for (final Item it : inv.getItems())
		{
			if (it != null && it.getId() == COINS_ID)
			{
				c += it.getQuantity();
			}
		}
		return c;
	}

	private int usedSlots()
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return 0;
		}
		int n = 0;
		for (final GrandExchangeOffer o : offers)
		{
			if (o != null && o.getState() != GrandExchangeOfferState.EMPTY)
			{
				n++;
			}
		}
		return n;
	}
}

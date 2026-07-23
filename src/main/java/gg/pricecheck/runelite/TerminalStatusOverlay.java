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
		if (!config.terminalStatusBar())
		{
			return null;
		}
		final Rectangle ge = plugin.geGridBounds();
		if (ge == null || !plugin.isGrandExchangeOpen())
		{
			return null;
		}

		final double scale = plugin.overlayScale();
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
			paintBar(g, (int) Math.round(w / scale), BAR_H, cash(), usedSlots(),
				client.getWorld(), LocalTime.now().format(CLOCK));
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

	/** Pure drawing (0,0-origin, w x h) so the preview harness can render it headless. */
	static void paintBar(Graphics2D g, int w, int h, long cash, int slots, int world, String clock)
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

		// field group
		int fx = 12 + brandW + 66;
		fx = field(g, fx, "CASH", TerminalKit.commas(cash), TerminalKit.AMBER);
		fx = field(g, fx, "SLOTS", slots + "/8", slots >= 8 ? TerminalKit.RED : TerminalKit.AMBER);
		fx = field(g, fx, "WORLD", Integer.toString(world), TerminalKit.AMBER);

		// right: LIVE + clock
		g.setFont(TerminalKit.monoB(12));
		final int clockW = g.getFontMetrics().stringWidth(clock);
		final int liveX = w - 12 - clockW - 52;
		g.setColor(TerminalKit.GREEN);
		g.fillOval(liveX, 9, 6, 6);
		g.setFont(TerminalKit.mono(10));
		g.setColor(TerminalKit.LABEL);
		g.drawString("LIVE", liveX + 11, 19);
		g.setFont(TerminalKit.monoB(12));
		g.setColor(TerminalKit.AMBER);
		TerminalKit.rt(g, clock, w - 12, 20);
	}

	private static int field(Graphics2D g, int x, String label, String value, Color vc)
	{
		g.setFont(TerminalKit.mono(9));
		g.setColor(TerminalKit.LABEL);
		g.drawString(label, x, 12);
		final int lw = g.getFontMetrics().stringWidth(label);
		g.setFont(TerminalKit.monoB(13));
		g.setColor(vc);
		g.drawString(value, x, 25);
		final int vw = g.getFontMetrics().stringWidth(value);
		return x + Math.max(vw, lw) + 26;
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

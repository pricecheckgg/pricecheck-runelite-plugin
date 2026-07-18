package gg.pricecheck.runelite;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.KeyCode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Anchors the item evidence card beside the open Grand Exchange interface
 * whenever an offer screen shows a specific item: the offer-status view (the
 * selected slot's offer, with your price drawn on the chart) or the
 * set-up-offer screen (the item being priced). All data flows through the
 * plugin: cached day series from the server, live board reads from the
 * advisor's poll, and the prints buffer that poll accumulates. Hold Shift to
 * peek past it, like every other GE overlay here.
 */
class GeItemCardOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int GE_SELECTED_SLOT_VARBIT = 4439;
	private static final int CARD_W = 284;

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	GeItemCardOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.geItemCard() || !plugin.isGrandExchangeOpen())
		{
			plugin.noteViewedItem(0);
			return null;
		}

		// The offer-status view names its slot in a varbit; the setup screen
		// is tracked by the chatbox helper. Either way we get one item.
		int geId = 0;
		long yourPrice = 0;
		int slotIdx = -1;
		boolean buying = false;
		final int slotVal = client.getVarbitValue(GE_SELECTED_SLOT_VARBIT);
		if (slotVal >= 1 && slotVal <= 8)
		{
			final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			final GrandExchangeOffer o = offers != null && offers.length >= slotVal ? offers[slotVal - 1] : null;
			if (o != null && o.getState() != GrandExchangeOfferState.EMPTY && o.getItemId() > 0)
			{
				geId = o.getItemId();
				yourPrice = o.getPrice();
				slotIdx = slotVal - 1;
				buying = o.getState() == GrandExchangeOfferState.BUYING
					|| o.getState() == GrandExchangeOfferState.BOUGHT
					|| o.getState() == GrandExchangeOfferState.CANCELLED_BUY;
			}
		}
		if (geId == 0)
		{
			geId = plugin.setupItem();
		}
		plugin.noteViewedItem(geId);
		if (geId <= 0)
		{
			return null;
		}
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return null;
		}

		final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(geId);
		final FlipData live = plugin.liveFor(geId);

		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = live != null && live.getName() != null ? live.getName() : ("#" + geId);
		c.yourPrice = yourPrice;
		c.nowTs = System.currentTimeMillis() / 1000L;
		c.prints = plugin.cardPrintsFor(geId);
		if (slotIdx >= 0)
		{
			for (final OfferAdvice a : plugin.getAdvice())
			{
				if (a.getSlot() == slotIdx && a.getKind() != OfferAdvice.Kind.NO_DATA)
				{
					c.stateText = a.getShortText();
					c.stateColor = a.getColor();
					break;
				}
			}
		}
		if (sd != null && sd.ts != null && sd.ts.length >= 2)
		{
			final ItemChart.Series s = new ItemChart.Series();
			s.ts = sd.ts;
			s.high = sd.ah;
			s.low = sd.al;
			s.hvol = sd.hv;
			s.lvol = sd.lv;
			c.series = s;
			c.fillPct = sd.fillPct;
		}
		if (slotIdx >= 0 && yourPrice > 0)
		{
			if (buying)
			{
				if (live != null && live.getSell() > 0)
				{
					final long m = GeTax.net(yourPrice, live.getSell());
					c.outcomeText = "Resells at market for " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
					c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
				}
			}
			else
			{
				c.outcomeText = "Nets " + Fmt.full(GeTax.net(0, yourPrice)) + " after tax if it sells";
				c.outcomeColor = Palette.LIGHT;
			}
		}
		else if (live != null)
		{
			final long m = live.getProfit();
			c.outcomeText = "Board margin " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
			c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
		}

		// Left of the GE window when there is room, right of it otherwise.
		int x = 10;
		int y = 120;
		Widget w = client.getWidget(GE_GROUP, 2);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(GE_GROUP, 0);
		}
		if (w != null)
		{
			final Rectangle b = w.getBounds();
			x = b.x - CARD_W - 8;
			if (x < 4)
			{
				x = b.x + b.width + 8;
			}
			y = Math.max(4, b.y);
		}
		g.translate(x, y);
		GeItemInfoPainter.paint(g, c);
		g.translate(-x, -y);
		return null;
	}
}

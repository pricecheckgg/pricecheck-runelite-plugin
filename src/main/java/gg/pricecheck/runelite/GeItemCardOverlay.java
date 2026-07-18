package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.KeyCode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The evidence cards beside the open Grand Exchange interface. Three views:
 * an offer's status screen and the set-up screen get the full card for that
 * item; the main grid gets one COMPACT card per distinct item across your
 * offers, buy and sell lines together on one chart when you ride both sides.
 * Hold Shift and a [-]/[+] appears on the top card: click it to collapse the
 * stack to slim verdict bars; the choice persists. Shift alone still peeks
 * on the single-item views, where the card can cover the world.
 */
class GeItemCardOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int[] SETUP_PANELS = { 15, 26 };
	private static final int GE_SELECTED_SLOT_VARBIT = 4439;
	private static final java.util.regex.Pattern PRICE = java.util.regex.Pattern.compile("^[0-9,]+ coins$");
	private static final int CARD_W = 284;
	private static final int BTN = 14;
	private static final int MAX_CHARTED = 4;
	private static final String COLLAPSED_KEY = "geCardsCollapsed";

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;
	private final ConfigManager configManager;

	private boolean collapsed;
	// Canvas-space hit box for the [-]/[+], valid only while Shift is held.
	private volatile Rectangle toggleBounds;

	GeItemCardOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config, ConfigManager configManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.collapsed = Boolean.parseBoolean(configManager.getConfiguration(PriceCheckConfig.GROUP, COLLAPSED_KEY));
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Mouse hook from the plugin: toggles the stack when the button is visible. */
	boolean handleClick(Point p, boolean shiftHeld)
	{
		final Rectangle t = toggleBounds;
		if (!shiftHeld || t == null || p == null || !t.contains(p))
		{
			return false;
		}
		collapsed = !collapsed;
		configManager.setConfiguration(PriceCheckConfig.GROUP, COLLAPSED_KEY, collapsed);
		return true;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		toggleBounds = null;
		if (!config.geItemCard() || !plugin.isGrandExchangeOpen())
		{
			plugin.noteViewedItem(0);
			return null;
		}

		// Which view is up? A selected slot wins, then a visible setup panel,
		// else the main grid.
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		int slotIdx = -1;
		final int slotVal = client.getVarbitValue(GE_SELECTED_SLOT_VARBIT);
		if (slotVal >= 1 && slotVal <= 8 && offers != null && offers.length >= slotVal)
		{
			final GrandExchangeOffer o = offers[slotVal - 1];
			if (o != null && o.getState() != GrandExchangeOfferState.EMPTY && o.getItemId() > 0)
			{
				slotIdx = slotVal - 1;
			}
		}
		Widget setupPanel = null;
		for (final int child : SETUP_PANELS)
		{
			final Widget w = client.getWidget(GE_GROUP, child);
			if (w != null && !w.isHidden())
			{
				setupPanel = w;
				break;
			}
		}

		final Point anchor = anchorPoint();
		if (slotIdx >= 0)
		{
			final int geId = offers[slotIdx].getItemId();
			plugin.noteViewedItem(geId);
			if (client.isKeyPressed(KeyCode.KC_SHIFT))
			{
				return null;
			}
			paintFull(g, anchor, geId, slotIdx, offers, 0, false);
			return null;
		}
		if (setupPanel != null && plugin.setupItem() > 0)
		{
			final int geId = plugin.setupItem();
			plugin.noteViewedItem(geId);
			if (client.isKeyPressed(KeyCode.KC_SHIFT))
			{
				return null;
			}
			// Read the price being typed right off the panel, every frame, so
			// the tag tracks the user while they adjust.
			long entered = 0;
			boolean sellSide = false;
			for (final Widget c : allChildren(setupPanel))
			{
				if (c == null)
				{
					continue;
				}
				final String txt = c.getText() == null ? "" : c.getText().replaceAll("<[^>]+>", "").trim();
				if (txt.isEmpty())
				{
					continue;
				}
				if (txt.equals("Sell offer"))
				{
					sellSide = true;
				}
				else if (entered <= 0 && PRICE.matcher(txt).matches())
				{
					final String digits = txt.replaceAll("[^0-9]", "");
					entered = digits.isEmpty() ? 0 : Long.parseLong(digits);
				}
			}
			paintFull(g, anchor, geId, -1, offers, entered, sellSide);
			return null;
		}

		// Main grid: one compact card per distinct item across your offers.
		plugin.noteViewedItem(0);
		if (offers == null)
		{
			return null;
		}
		final Map<Integer, long[]> byItem = new LinkedHashMap<>();   // geId -> {buyPrice, sellPrice, buySlot, sellSlot}
		for (int i = 0; i < offers.length; i++)
		{
			final GrandExchangeOffer o = offers[i];
			if (o == null || o.getState() == GrandExchangeOfferState.EMPTY || o.getItemId() <= 0)
			{
				continue;
			}
			final long[] e = byItem.computeIfAbsent(o.getItemId(), k -> new long[]{0, 0, -1, -1});
			if (isBuySide(o.getState()))
			{
				e[0] = o.getPrice();
				e[2] = i;
			}
			else
			{
				e[1] = o.getPrice();
				e[3] = i;
			}
		}
		if (byItem.isEmpty())
		{
			return null;
		}

		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		int y = anchor.y;
		int idx = 0;
		for (final Map.Entry<Integer, long[]> e : byItem.entrySet())
		{
			final int geId = e.getKey();
			final long[] v = e.getValue();
			final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
			final FlipData live = plugin.liveFor(geId);
			c.itemName = live != null && live.getName() != null ? live.getName() : ("#" + geId);
			c.youBuy = v[0];
			c.youSell = v[1];
			fillVerdicts(c, (int) v[2], (int) v[3]);
			if (!collapsed && idx < MAX_CHARTED)
			{
				final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(geId);
				c.series = toSeries(sd, live);
			}
			g.translate(anchor.x, y);
			final Dimension d = GeItemInfoPainter.paintCompact(g, c);
			if (idx == 0 && shift)
			{
				paintToggle(g, d.width, anchor.x, y);
			}
			g.translate(-anchor.x, -y);
			y += d.height + 6;
			idx++;
		}
		return null;
	}

	private void paintFull(Graphics2D g, Point anchor, int geId, int slotIdx, GrandExchangeOffer[] offers, long setupPrice, boolean setupSell)
	{
		final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(geId);
		final FlipData live = plugin.liveFor(geId);

		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = live != null && live.getName() != null ? live.getName() : ("#" + geId);
		c.nowTs = System.currentTimeMillis() / 1000L;
		c.prints = plugin.cardPrintsFor(geId);
		c.series = toSeries(sd, live);
		if (sd != null)
		{
			c.fillPct = sd.fillPct;
		}

		// Every side you have live on this item draws on the chart, whichever
		// screen you opened it from.
		long viewPrice = 0;
		boolean viewBuying = false;
		int buySlot = -1;
		int sellSlot = -1;
		if (offers != null)
		{
			for (int i = 0; i < offers.length; i++)
			{
				final GrandExchangeOffer o = offers[i];
				if (o == null || o.getState() == GrandExchangeOfferState.EMPTY || o.getItemId() != geId)
				{
					continue;
				}
				if (isBuySide(o.getState()))
				{
					c.youBuy = o.getPrice();
					buySlot = i;
				}
				else
				{
					c.youSell = o.getPrice();
					sellSlot = i;
				}
				if (i == slotIdx)
				{
					viewPrice = o.getPrice();
					viewBuying = isBuySide(o.getState());
				}
			}
		}
		fillVerdicts(c, buySlot, sellSlot);

		// The price being typed on the setup screen tracks live and wins the
		// tag for its side; the outcome line reads from it too.
		if (setupPrice > 0)
		{
			if (setupSell)
			{
				c.youSell = setupPrice;
				c.outcomeText = "Nets " + Fmt.full(GeTax.net(0, setupPrice)) + " after tax if it sells";
				c.outcomeColor = Palette.LIGHT;
			}
			else
			{
				c.youBuy = setupPrice;
				if (live != null && live.getSell() > 0)
				{
					final long m = GeTax.net(setupPrice, live.getSell());
					c.outcomeText = "Resells at market for " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
					c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
				}
			}
		}

		if (slotIdx >= 0 && viewPrice > 0)
		{
			if (viewBuying)
			{
				if (live != null && live.getSell() > 0)
				{
					final long m = GeTax.net(viewPrice, live.getSell());
					c.outcomeText = "Resells at market for " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
					c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
				}
			}
			else
			{
				c.outcomeText = "Nets " + Fmt.full(GeTax.net(0, viewPrice)) + " after tax if it sells";
				c.outcomeColor = Palette.LIGHT;
			}
		}
		else if (c.outcomeText == null && live != null)
		{
			final long m = live.getProfit();
			c.outcomeText = "Board margin " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
			c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
		}

		g.translate(anchor.x, anchor.y);
		GeItemInfoPainter.paint(g, c);
		g.translate(-anchor.x, -anchor.y);
	}

	/** Advisor verdicts for this item's slots: buy side first, sell second. */
	private void fillVerdicts(GeItemInfoPainter.Context c, int buySlot, int sellSlot)
	{
		for (final OfferAdvice a : plugin.getAdvice())
		{
			if (a.getKind() == OfferAdvice.Kind.NO_DATA)
			{
				continue;
			}
			if (a.getSlot() == buySlot && c.stateText == null)
			{
				c.stateText = a.getShortText();
				c.stateColor = a.getColor();
			}
			else if (a.getSlot() == sellSlot && c.stateText2 == null)
			{
				c.stateText2 = a.getShortText();
				c.stateColor2 = a.getColor();
			}
		}
		// A single verdict reads better in the first seat.
		if (c.stateText == null && c.stateText2 != null)
		{
			c.stateText = c.stateText2;
			c.stateColor = c.stateColor2;
			c.stateText2 = null;
			c.stateColor2 = null;
		}
	}

	private static ItemChart.Series toSeries(PriceCheckApiClient.SeriesData sd, FlipData live)
	{
		if (sd == null || sd.ts == null || sd.ts.length < 2)
		{
			return null;
		}
		final ItemChart.Series s = new ItemChart.Series();
		s.ts = sd.ts;
		s.high = sd.ah;
		s.low = sd.al;
		s.hvol = sd.hv;
		s.lvol = sd.lv;
		if (live != null)
		{
			s.quoteBuy = live.getBuy();
			s.quoteSell = live.getSell();
		}
		return s;
	}

	private static Widget[] allChildren(Widget panel)
	{
		final Widget[] stat = panel.getStaticChildren();
		final Widget[] dyn = panel.getDynamicChildren();
		final int sl = stat == null ? 0 : stat.length;
		final int dl = dyn == null ? 0 : dyn.length;
		final Widget[] all = new Widget[sl + dl];
		if (sl > 0)
		{
			System.arraycopy(stat, 0, all, 0, sl);
		}
		if (dl > 0)
		{
			System.arraycopy(dyn, 0, all, sl, dl);
		}
		return all;
	}

	private static boolean isBuySide(GrandExchangeOfferState st)
	{
		return st == GrandExchangeOfferState.BUYING
			|| st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
	}

	private Point anchorPoint()
	{
		Widget w = client.getWidget(GE_GROUP, 2);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(GE_GROUP, 0);
		}
		if (w != null)
		{
			final Rectangle b = w.getBounds();
			int x = b.x - CARD_W - 8;
			if (x < 4)
			{
				x = b.x + b.width + 8;
			}
			return new Point(x, Math.max(4, b.y));
		}
		return new Point(10, 120);
	}

	private void paintToggle(Graphics2D g, int cardW, int absX, int absY)
	{
		final int bx = cardW - BTN - 4;
		final int by = 4;
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect(bx, by, BTN, BTN, 4, 4);
		g.setColor(Palette.GOLD);
		g.drawRoundRect(bx, by, BTN, BTN, 4, 4);
		final int cx = bx + BTN / 2;
		final int cy = by + BTN / 2;
		g.drawLine(cx - 3, cy, cx + 3, cy);
		if (collapsed)
		{
			g.drawLine(cx, cy - 3, cx, cy + 3);
		}
		toggleBounds = new Rectangle(absX + bx, absY + by, BTN, BTN);
	}
}

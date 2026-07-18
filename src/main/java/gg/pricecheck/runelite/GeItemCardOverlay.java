package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The evidence cards beside the open Grand Exchange interface. The offer
 * status and set-up screens get the full card for their item; the main grid
 * gets one card per distinct item across your offers, every offer tagged on
 * its chart with a side letter, one card even when you ride both sides.
 * Hold Shift and each card grows a [+]/[-]: expand any card to the full
 * evidence view (big chart, trades tape, measured reads) in place; the top
 * card also keeps the stack-wide collapse to slim verdict bars. Expansion is
 * per session; the collapse choice persists.
 */
class GeItemCardOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int[] SETUP_PANELS = { 15, 26 };
	private static final int GE_SELECTED_SLOT_VARBIT = 4439;
	private static final int CARD_W = 284;
	private static final int BTN = 14;
	private static final String COLLAPSED_KEY = "geCardsCollapsed";
	private static final java.util.regex.Pattern PRICE = java.util.regex.Pattern.compile("^[0-9,]+ coins$");

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;
	private final ConfigManager configManager;

	private boolean collapsed;
	// At most ONE card rides big at a time; opening another swaps it. 0 = none.
	private int expandedItem;
	// Canvas-space buttons drawn this frame, valid only while Shift is held.
	private volatile List<Object[]> buttons = java.util.Collections.emptyList();

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

	/** Mouse hook from the plugin: runs whichever button was clicked. */
	boolean handleClick(Point p, boolean shiftHeld)
	{
		if (!shiftHeld || p == null)
		{
			return false;
		}
		for (final Object[] b : buttons)
		{
			if (((Rectangle) b[0]).contains(p))
			{
				((Runnable) b[1]).run();
				return true;
			}
		}
		return false;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final List<Object[]> hits = new ArrayList<>();
		buttons = hits;
		// Market data is a Trader Pro surface; the server refuses it for free
		// keys and the cards stay fully dark rather than rendering shells.
		if (!config.geItemCard() || !plugin.isGrandExchangeOpen() || !plugin.marketDataOk())
		{
			plugin.noteViewedItem(0);
			return null;
		}

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
			final GeItemInfoPainter.Context c = buildContext(geId, offers, true);
			addViewOutcome(c, offers[slotIdx]);
			paintAt(g, anchor.x, anchor.y, () -> GeItemInfoPainter.paint(g, c));
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
			final GeItemInfoPainter.Context c = buildContext(geId, offers, true);
			if (entered > 0)
			{
				if (sellSide)
				{
					c.youSells = append(c.youSells, entered);
					c.outcomeText = "Nets " + Fmt.full(GeTax.net(0, entered)) + " after tax if it sells";
					c.outcomeColor = Palette.LIGHT;
				}
				else
				{
					c.youBuys = append(c.youBuys, entered);
					final FlipData live = plugin.liveFor(geId);
					if (live != null && live.getSell() > 0)
					{
						final long m = GeTax.net(entered, live.getSell());
						c.outcomeText = "Resells at market for " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
						c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
					}
				}
			}
			paintAt(g, anchor.x, anchor.y, () -> GeItemInfoPainter.paint(g, c));
			return null;
		}

		// Main grid: one card per distinct item across your offers.
		plugin.noteViewedItem(0);
		if (offers == null)
		{
			return null;
		}
		final Map<Integer, Boolean> items = new LinkedHashMap<>();
		for (final GrandExchangeOffer o : offers)
		{
			if (o != null && o.getState() != GrandExchangeOfferState.EMPTY && o.getItemId() > 0)
			{
				items.put(o.getItemId(), Boolean.TRUE);
			}
		}
		if (items.isEmpty())
		{
			return null;
		}

		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		// Two columns: left of the GE window, then right of it. Every mini
		// keeps its chart as long as a column has room; a card that cannot
		// fit charted flows to the next column before it gives its chart up.
		final int budget = client.getCanvasHeight() - 8;
		final List<Point> cols = columnAnchors();
		if (cols.isEmpty())
		{
			return null;
		}
		int col = 0;
		int y = cols.get(0).y;
		int idx = 0;
		int remainingItems = items.size();
		for (final int geId : items.keySet())
		{
			final boolean wantFull = geId == expandedItem && !collapsed;
			final int est = wantFull ? 335 : (collapsed ? 28 : 108);
			// Move to the next column when this card will not fit here at its
			// wanted size but would at a fresh column top.
			while (col < cols.size() - 1 && budget - y < est && budget - cols.get(col + 1).y >= est)
			{
				col++;
				y = cols.get(col).y;
			}
			final int left = budget - y;
			if (left < 30)
			{
				if (left >= 0 && remainingItems > 0)
				{
					final GeItemInfoPainter.Context more = new GeItemInfoPainter.Context();
					more.itemName = "+" + remainingItems + " more";
					final int mx = cols.get(col).x;
					final int my = y;
					paintAt(g, mx, my, () -> GeItemInfoPainter.paintCompact(g, more));
				}
				break;
			}
			final boolean canFull = wantFull && left >= 335;
			final boolean canChart = !collapsed && left >= 108;
			final GeItemInfoPainter.Context c = buildContext(geId, offers, canFull || canChart);
			final Dimension d;
			final int yy = y;
			final int xx = cols.get(col).x;
			if (canFull)
			{
				final FlipData live = plugin.liveFor(geId);
				if (c.outcomeText == null && live != null)
				{
					final long m = live.getProfit();
					c.outcomeText = "Board margin " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
					c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
				}
				d = paintAt(g, xx, yy, () -> GeItemInfoPainter.paint(g, c));
			}
			else
			{
				if (!canChart)
				{
					c.series = null;
				}
				d = paintAt(g, xx, yy, () -> GeItemInfoPainter.paintCompact(g, c));
			}
			if (shift)
			{
				// Per-card grow or shrink; the top card also carries the
				// stack-wide bars toggle to its left.
				final int bx = d.width - BTN - 4;
				paintButton(g, xx + bx, yy + 4, canFull);
				hits.add(new Object[]{new Rectangle(xx + bx, yy + 4, BTN, BTN), (Runnable) () ->
					expandedItem = expandedItem == geId ? 0 : geId});
				if (idx == 0)
				{
					final int bx2 = bx - BTN - 6;
					paintBarsButton(g, xx + bx2, yy + 4);
					hits.add(new Object[]{new Rectangle(xx + bx2, yy + 4, BTN, BTN), (Runnable) () ->
					{
						collapsed = !collapsed;
						configManager.setConfiguration(PriceCheckConfig.GROUP, COLLAPSED_KEY, collapsed);
					}});
				}
			}
			y += d.height + 6;
			idx++;
			remainingItems--;
		}
		return null;
	}

	/** Card columns beside the GE window: left of it when there is room,
	 * then right of it. Falls back to a single left-edge column. */
	private List<Point> columnAnchors()
	{
		final List<Point> cols = new ArrayList<>();
		Widget w = client.getWidget(GE_GROUP, 2);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(GE_GROUP, 0);
		}
		if (w != null)
		{
			final Rectangle b = w.getBounds();
			final int top = Math.max(4, b.y);
			if (b.x - CARD_W - 8 >= 4)
			{
				cols.add(new Point(b.x - CARD_W - 8, top));
			}
			if (b.x + b.width + 8 + CARD_W <= client.getCanvasWidth() - 4)
			{
				cols.add(new Point(b.x + b.width + 8, top));
			}
		}
		if (cols.isEmpty())
		{
			cols.add(new Point(10, 120));
		}
		return cols;
	}

	/** Everything both card sizes need for one item: series when asked, all
	 * your offers as side tags, per-side verdicts with B/S prefixes. */
	private GeItemInfoPainter.Context buildContext(int geId, GrandExchangeOffer[] offers, boolean withSeries)
	{
		final FlipData live = plugin.liveFor(geId);
		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = live != null && live.getName() != null ? live.getName() : ("#" + geId);
		c.nowTs = System.currentTimeMillis() / 1000L;
		c.prints = plugin.cardPrintsFor(geId);
		if (withSeries)
		{
			final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(geId);
			if (sd != null && sd.ts != null && sd.ts.length >= 2)
			{
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
				c.series = s;
				c.fillPct = sd.fillPct;
			}
		}
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
					c.youBuys = append(c.youBuys, o.getPrice());
					if (buySlot < 0)
					{
						buySlot = i;
					}
				}
				else
				{
					c.youSells = append(c.youSells, o.getPrice());
					if (sellSlot < 0)
					{
						sellSlot = i;
					}
				}
			}
		}
		for (final OfferAdvice a : plugin.getAdvice())
		{
			if (a.getKind() == OfferAdvice.Kind.NO_DATA)
			{
				continue;
			}
			if (a.getSlot() == buySlot && c.stateText == null)
			{
				c.stateText = "B " + a.getShortText();
				c.stateColor = a.getColor();
			}
			else if (a.getSlot() == sellSlot && c.stateText2 == null)
			{
				c.stateText2 = "S " + a.getShortText();
				c.stateColor2 = a.getColor();
			}
		}
		if (c.stateText == null && c.stateText2 != null)
		{
			c.stateText = c.stateText2;
			c.stateColor = c.stateColor2;
			c.stateText2 = null;
			c.stateColor2 = null;
		}
		return c;
	}

	/** The outcome line for the offer whose status screen is open. */
	private void addViewOutcome(GeItemInfoPainter.Context c, GrandExchangeOffer o)
	{
		final long price = o.getPrice();
		if (price <= 0)
		{
			return;
		}
		if (isBuySide(o.getState()))
		{
			final FlipData live = plugin.liveFor(o.getItemId());
			if (live != null && live.getSell() > 0)
			{
				final long m = GeTax.net(price, live.getSell());
				c.outcomeText = "Resells at market for " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
				c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
			}
		}
		else
		{
			c.outcomeText = "Nets " + Fmt.full(GeTax.net(0, price)) + " after tax if it sells";
			c.outcomeColor = Palette.LIGHT;
		}
	}

	private static Dimension paintAt(Graphics2D g, int x, int y, java.util.function.Supplier<Dimension> painter)
	{
		g.translate(x, y);
		final Dimension d = painter.get();
		g.translate(-x, -y);
		return d;
	}

	private static long[] append(long[] arr, long v)
	{
		final long[] out = java.util.Arrays.copyOf(arr, arr.length + 1);
		out[arr.length] = v;
		return out;
	}

	private void paintButton(Graphics2D g, int x, int y, boolean expanded)
	{
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect(x, y, BTN, BTN, 4, 4);
		g.setColor(Palette.GOLD);
		g.drawRoundRect(x, y, BTN, BTN, 4, 4);
		final int cx = x + BTN / 2;
		final int cy = y + BTN / 2;
		g.drawLine(cx - 3, cy, cx + 3, cy);
		if (!expanded)
		{
			g.drawLine(cx, cy - 3, cx, cy + 3);
		}
	}

	private void paintBarsButton(Graphics2D g, int x, int y)
	{
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect(x, y, BTN, BTN, 4, 4);
		g.setColor(Palette.SUBTLE);
		g.drawRoundRect(x, y, BTN, BTN, 4, 4);
		// Three little bars: the collapse-the-stack glyph.
		for (int i = 0; i < 3; i++)
		{
			g.drawLine(x + 3, y + 4 + i * 3, x + BTN - 4, y + 4 + i * 3);
		}
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
}

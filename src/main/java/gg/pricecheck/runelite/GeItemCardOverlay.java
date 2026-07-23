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
 * status and set-up screens get the full card for their item (always shown, no
 * Shift-to-hide); the main grid gets one card per distinct item across your
 * offers, every offer tagged on its chart with a side letter, one card even
 * when you ride both sides. On the grid, hold Shift and each card grows a
 * [+]/[-]: expand any card to the full evidence view (big chart, trades tape,
 * measured reads) in place; the top card also keeps the stack-wide collapse to
 * slim verdict bars. Expansion is per session; the collapse choice persists.
 * Click the chart's view tag (no Shift) to cycle its views, or hold Shift for
 * the selector: Latest / 24h / 7d (time) and 10 / 20 / 30 (last-N trades). The
 * selected view drives the chart, the range read, and the price-entry options.
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
	// Last coherent setup quantity, kept per item so one frame where the total
	// widget lags the price field cannot flicker the whole-offer math.
	private int setupQtyItem;
	private long setupQty;
	// Canvas-space buttons drawn this frame, valid only while Shift is held.
	private volatile List<Object[]> buttons = java.util.Collections.emptyList();
	// Canvas-space hit box for the chart's timeframe tag, clickable WITHOUT Shift.
	// Null when no full card is up.
	private volatile Rectangle tfPillBounds;
	// Canvas-space 1h/24h/7d chips, drawn while Shift is held so the timeframe
	// control is discoverable. Each entry is {Rectangle, Runnable}.
	private volatile List<Object[]> tfChipHits = java.util.Collections.emptyList();

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

	/** Mouse hook from the plugin: runs whichever button was clicked. The
	 *  timeframe tag cycles WITHOUT Shift (Shift is the peek-behind gesture on the
	 *  single card); the per-card expand/collapse buttons stay Shift-only. */
	boolean handleClick(Point p, boolean shiftHeld)
	{
		if (p == null)
		{
			return false;
		}
		// Shift-revealed 1h/24h/7d chips pick a window directly.
		for (final Object[] b : tfChipHits)
		{
			if (((Rectangle) b[0]).contains(p))
			{
				((Runnable) b[1]).run();
				return true;
			}
		}
		final Rectangle pill = tfPillBounds;
		if (pill != null && pill.contains(p))
		{
			plugin.cycleChartTf();
			return true;
		}
		if (!shiftHeld)
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
		tfPillBounds = null;
		final List<Object[]> tfHits = new ArrayList<>();
		tfChipHits = tfHits;
		// Market data is a Trader Pro surface; the server refuses it for free
		// keys and the cards stay fully dark rather than rendering shells.
		if ((!config.geItemCard() && !plugin.terminalDesk()) || !plugin.isGrandExchangeOpen() || !plugin.marketDataOk())
		{
			plugin.noteViewedItem(0);
			return null;
		}
		final java.awt.FontMetrics tfFm = g.getFontMetrics(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		// Holding Shift reveals the 1h/24h/7d timeframe selector on the card.
		final boolean shiftDown = client.isKeyPressed(KeyCode.KC_SHIFT);

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
		if (setupPanel == null)
		{
			// The cached quantity belongs to the panel session that set it;
			// letting it outlive the panel could tag a later offer's math.
			setupQtyItem = 0;
			setupQty = 0;
		}

		final boolean deskCard = config.terminalCard() || plugin.terminalDesk();
		final int wantW = deskCard ? GeItemInfoPainter.TERM_W : GeItemInfoPainter.W_FULL;
		final int[] anchor = anchorFor(wantW);
		if (slotIdx >= 0)
		{
			final int geId = offers[slotIdx].getItemId();
			plugin.noteViewedItem(geId);
			final GeItemInfoPainter.Context c = buildContext(geId, offers, true);
			addViewOutcome(c, offers, slotIdx);
			c.rangeRow = plugin.viewRangeFor(geId);
			final boolean wantTerm = deskCard && anchor[2] >= GeItemInfoPainter.TERM_W;
			final int termBudget = wantTerm ? termMaxHeight(anchor) : 0;
			// Not enough vertical room for even the terminal card's fixed content (a
			// short window): fall back to the classic card so we never overdraw the
			// chat input. Normal windows leave plenty of room, so term stays on.
			final boolean term = wantTerm && termBudget >= GeItemInfoPainter.terminalFixedHeight(c);
			c.maxHeight = term ? termBudget : 0;
			paintAt(g, anchor[0], anchor[1], () -> term
				? GeItemInfoPainter.paintTerminal(g, c, anchor[2])
				: GeItemInfoPainter.paint(g, c, anchor[2]));
			notePill(anchor[0], anchor[1], c.chartLabel, tfFm, term);
			if (c.chartLabel != null)
			{
				if (shiftDown)
				{
					drawTfChips(g, anchor[0], anchor[1], tfFm, term);
				}
				else
				{
					drawShiftHint(g, anchor[0], anchor[1], tfFm, term);
				}
			}
			return null;
		}
		if (setupPanel != null && plugin.setupItem() > 0)
		{
			final int geId = plugin.setupItem();
			plugin.noteViewedItem(geId);
			// Read the price and the offer total right off the panel, every
			// frame, so the tag tracks the user while they adjust. The setup
			// panel carries exactly two "N coins" texts, price first, then the
			// total bar, and the game computes total = price x quantity, so
			// the quantity comes out of the division exactly.
			long entered = 0;
			long offerTotal = -1;
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
				else if (PRICE.matcher(txt).matches())
				{
					final String digits = txt.replaceAll("[^0-9]", "");
					final long v = digits.isEmpty() ? 0 : Long.parseLong(digits);
					if (entered <= 0)
					{
						entered = v;
					}
					else
					{
						offerTotal = v;
					}
				}
			}
			long qty = setupQtyItem == geId ? setupQty : 1;
			if (entered > 0 && offerTotal == 0)
			{
				// Quantity is genuinely zero (mid-retype): whole-offer math for
				// the old quantity would be a lie, so drop to per-item only.
				qty = 1;
				setupQtyItem = geId;
				setupQty = 1;
			}
			else if (entered > 0 && offerTotal > 0 && offerTotal % entered == 0)
			{
				qty = offerTotal / entered;
				setupQtyItem = geId;
				setupQty = qty;
			}
			final GeItemInfoPainter.Context c = buildContext(geId, offers, true);
			if (entered > 0)
			{
				if (sellSide)
				{
					c.youSells = append(c.youSells, entered);
				}
				else
				{
					c.youBuys = append(c.youBuys, entered);
				}
				// A new sell must be costed behind the lots your open sell
				// offers have already spoken for.
				final long skip = sellSide ? sellQtyCommitted(offers, geId, -1) : 0;
				applyOutcome(c, sellSide, entered, qty, geId, "", skip);
			}
			c.rangeRow = plugin.viewRangeFor(geId);
			final boolean wantTerm = deskCard && anchor[2] >= GeItemInfoPainter.TERM_W;
			final int termBudget = wantTerm ? termMaxHeight(anchor) : 0;
			// Not enough vertical room for even the terminal card's fixed content (a
			// short window): fall back to the classic card so we never overdraw the
			// chat input. Normal windows leave plenty of room, so term stays on.
			final boolean term = wantTerm && termBudget >= GeItemInfoPainter.terminalFixedHeight(c);
			c.maxHeight = term ? termBudget : 0;
			paintAt(g, anchor[0], anchor[1], () -> term
				? GeItemInfoPainter.paintTerminal(g, c, anchor[2])
				: GeItemInfoPainter.paint(g, c, anchor[2]));
			notePill(anchor[0], anchor[1], c.chartLabel, tfFm, term);
			if (c.chartLabel != null)
			{
				if (shiftDown)
				{
					drawTfChips(g, anchor[0], anchor[1], tfFm, term);
				}
				else
				{
					drawShiftHint(g, anchor[0], anchor[1], tfFm, term);
				}
			}
			return null;
		}

		// Main grid: one card per distinct item across your offers.
		plugin.noteViewedItem(0);
		// The terminal desk owns the space around the GE (radar left, blotter right,
		// held/session/fills), so the classic overview mini-cards stand down for it.
		if (plugin.terminalDesk())
		{
			return null;
		}
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

		final boolean shift = shiftDown;
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
			// A full card needs a chart plus a few tape rows to be worth it; below
			// this it stays a mini. The tape is capped to the actual room when it
			// paints (in the canFull branch), so + always expands with as deep a
			// tape as fits instead of silently refusing when Overnight's full
			// 30-row tape would overflow the column.
			final int minFull = 340;
			final int est = wantFull ? minFull : (collapsed ? 28 : 108);
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
			final boolean canFull = wantFull && left >= minFull;
			final boolean canChart = !collapsed && left >= 108;
			final GeItemInfoPainter.Context c = buildContext(geId, offers, canFull || canChart);
			final Dimension d;
			final int yy = y;
			final int xx = cols.get(col).x;
			final int paintedX;
			if (canFull)
			{
				// Cap the tape to the room so a tall Overnight tape does not run off
				// the column; the single-item slot/set-up card keeps the full depth.
				c.tradeDepth = Math.max(4, Math.min(plugin.overlayTradeDepth(), (left - 300) / 13));
				final FlipData live = plugin.viewFor(geId);
				if (c.outcomeText == null && live != null)
				{
					final long m = live.getProfit();
					c.outcomeText = "Board margin " + (m >= 0 ? "+" : "") + Fmt.compact(m) + " after tax";
					c.outcomeColor = m >= 0 ? Palette.GREEN : Palette.RED;
				}
				// The expanded card widens toward free space: a left-column card
				// grows leftward (its right edge stays put), a right-column one
				// grows toward the canvas edge. Neither may touch the GE.
				final Rectangle geB = geBounds();
				int fw = GeItemInfoPainter.W;
				int fx = xx;
				if (geB != null && xx < geB.x && geB.x - 8 - GeItemInfoPainter.W_FULL >= 4)
				{
					fw = GeItemInfoPainter.W_FULL;
					fx = geB.x - 8 - fw;
				}
				else if (geB != null && xx > geB.x
					&& xx + GeItemInfoPainter.W_FULL <= client.getCanvasWidth() - 4)
				{
					fw = GeItemInfoPainter.W_FULL;
				}
				final int fwF = fw;
				paintedX = fx;
				d = paintAt(g, fx, yy, () -> GeItemInfoPainter.paint(g, c, fwF));
				notePill(fx, yy, c.chartLabel, tfFm, false);
				if (c.chartLabel != null)
				{
					if (shiftDown)
					{
						drawTfChips(g, fx, yy, tfFm, false);
					}
					else
					{
						drawShiftHint(g, fx, yy, tfFm, false);
					}
				}
			}
			else
			{
				if (!canChart)
				{
					c.series = null;
				}
				paintedX = xx;
				d = paintAt(g, xx, yy, () -> GeItemInfoPainter.paintCompact(g, c));
			}
			if (shift)
			{
				// Per-card grow or shrink; the top card also carries the
				// stack-wide bars toggle to its left.
				final int bx = d.width - BTN - 4;
				paintButton(g, paintedX + bx, yy + 4, canFull);
				hits.add(new Object[]{new Rectangle(paintedX + bx, yy + 4, BTN, BTN), (Runnable) () ->
					expandedItem = expandedItem == geId ? 0 : geId});
				if (idx == 0)
				{
					final int bx2 = bx - BTN - 6;
					paintBarsButton(g, paintedX + bx2, yy + 4);
					hits.add(new Object[]{new Rectangle(paintedX + bx2, yy + 4, BTN, BTN), (Runnable) () ->
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
			// Columns start near the canvas top, not the GE's: the headroom
			// above the interface is stack room the bottom edge needs.
			final int top = 8;
			if (b.x - CARD_W - 8 >= 4)
			{
				cols.add(new Point(b.x - CARD_W - 8, top));
			}
			// Yield the right column to the active-offers board when it owns the
			// dock; the board's predicate is the single source of truth.
			if (!plugin.geOffersPanelVisible() && b.x + b.width + 8 + CARD_W <= client.getCanvasWidth() - 4)
			{
				cols.add(new Point(b.x + b.width + 8, top));
			}
		}
		// Board-aware fallback: when the board owns the right and there is no left
		// room, cols stays empty and the grid yields entirely (render() returns on
		// empty cols). Board-off behaviour is unchanged.
		if (cols.isEmpty() && !plugin.geOffersPanelVisible())
		{
			cols.add(new Point(10, 120));
		}
		return cols;
	}

	/** Everything both card sizes need for one item: series when asked, all
	 * your offers as side tags, per-side verdicts with B/S prefixes. */
	private GeItemInfoPainter.Context buildContext(int geId, GrandExchangeOffer[] offers, boolean withSeries)
	{
		final FlipData live = plugin.viewFor(geId);
		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = live != null && live.getName() != null ? live.getName() : ("#" + geId);
		c.nowTs = System.currentTimeMillis() / 1000L;
		c.tradeDepth = plugin.overlayTradeDepth();
		c.prints = plugin.cardPrintsFor(geId);
		final long[] lim = plugin.buyLimitInfo(geId);
		if (lim != null)
		{
			c.limitBought = lim[0];
			c.limitTotal = lim[1];
			c.limitResetMs = lim[2];
		}
		final long[] holding = plugin.holdingFor(geId);
		if (holding != null)
		{
			c.lotQty = holding[0];
			c.lotCost = holding[1];
			c.lotOpenedAtMs = holding[2];
			c.lotEntries = plugin.lotsFor(geId).toArray(new long[0][]);
		}
		if (withSeries)
		{
			final PriceCheckPlugin.ChartTf tf = plugin.chartTf();
			c.chartLabel = tf.label;
			if (tf.isTrades())
			{
				// Trades view: the chart plots the last N actual trades (from the
				// tape), which always has data. No corridor series is needed.
				c.tradesChartN = tf.amount;
			}
			else
			{
				// Time view: the traded corridor over a window. 24h/Latest come from
				// the day series (Latest auto-widens from its tail); 7d needs the
				// wider server series, falling back to the day series until it lands.
				PriceCheckApiClient.SeriesData sd = tf == PriceCheckPlugin.ChartTf.D7
					? plugin.cardSeries7dFor(geId) : null;
				if (sd == null || sd.ts == null || sd.ts.length < 2)
				{
					sd = plugin.cardSeriesFor(geId);
				}
				if (sd != null && sd.ts != null && sd.ts.length >= 2)
				{
					final ItemChart.Series s = tf == PriceCheckPlugin.ChartTf.LATEST
						? seriesLatest(sd) : seriesWindow(sd, 0L);
					if (live != null)
					{
						s.quoteBuy = live.getBuy();
						s.quoteSell = live.getSell();
					}
					c.series = s;
					c.fillPct = sd.fillPct;
				}
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

	/** The outcome lines for the offer whose status screen is open. Live buys
	 * are judged over the full offer, cancelled buys over the units actually
	 * bought (the rest will never exist). Live sells are judged over what is
	 * still unsold, behind the lots other open sells have claimed; a
	 * cancelled sell projects nothing, its sold part is booked in the log. */
	private void addViewOutcome(GeItemInfoPainter.Context c, GrandExchangeOffer[] offers, int slotIdx)
	{
		final GrandExchangeOffer o = offers[slotIdx];
		final long price = o.getPrice();
		if (price <= 0)
		{
			return;
		}
		final GrandExchangeOfferState st = o.getState();
		if (isBuySide(st))
		{
			final boolean cancelled = st == GrandExchangeOfferState.CANCELLED_BUY;
			final long qty = cancelled ? o.getQuantitySold() : Math.max(1, o.getTotalQuantity());
			if (qty <= 0)
			{
				return;   // cancelled before anything bought: nothing to resell
			}
			applyOutcome(c, false, price, qty, o.getItemId(), cancelled ? " bought" : "", 0);
		}
		else
		{
			if (st == GrandExchangeOfferState.CANCELLED_SELL)
			{
				return;   // dead offer: the sold part's profit is booked below
			}
			final long left = o.getTotalQuantity() - o.getQuantitySold();
			if (left <= 0)
			{
				return;   // fully sold: the trades section below carries the booked profit
			}
			// Sells of the same item across slots claim disjoint lot spans
			// (earlier slots take the older lots), so their profits sum true.
			final long skip = sellQtyCommitted(offers, o.getItemId(), slotIdx);
			applyOutcome(c, true, price, left, o.getItemId(), o.getQuantitySold() > 0 ? " left" : "", skip);
		}
	}

	/** Unsold units of the item committed to LIVE sell offers in slots before
	 * {@code beforeSlot} (-1 = all slots): the lot span already spoken for. */
	private static long sellQtyCommitted(GrandExchangeOffer[] offers, int geId, int beforeSlot)
	{
		if (offers == null)
		{
			return 0;
		}
		long claimed = 0;
		final int end = beforeSlot >= 0 ? Math.min(beforeSlot, offers.length) : offers.length;
		for (int i = 0; i < end; i++)
		{
			final GrandExchangeOffer o = offers[i];
			if (o != null && o.getItemId() == geId && o.getState() == GrandExchangeOfferState.SELLING)
			{
				claimed += Math.max(0, o.getTotalQuantity() - o.getQuantitySold());
			}
		}
		return claimed;
	}

	/** The card's money math, quantity-aware and exact: line one is the
	 * per-item after-tax outcome, line two the whole offer. Sells are judged
	 * against the FIFO cost of your own tracked lots when they cover the
	 * quantity; every figure is full digits, nothing hides in rounding. */
	private void applyOutcome(GeItemInfoPainter.Context c, boolean sellSide, long price, long qty, int geId, String qtySuffix, long sellSkip)
	{
		final boolean many = qty > 1 || !qtySuffix.isEmpty();
		final String qtyTag = "x" + Fmt.full(qty) + qtySuffix;
		final String ea = many ? "/ea" : "";
		if (sellSide)
		{
			final long taxEach = GeTax.tax(price);
			final long netEach = price - taxEach;
			c.outcomeText = "Nets " + Fmt.full(netEach) + ea + " after tax (tax " + Fmt.full(taxEach) + ea + ")";
			c.outcomeColor = Palette.LIGHT;
			final long fifoCost = plugin.fifoCostFor(geId, sellSkip, qty);
			if (fifoCost >= 0)
			{
				final long profit = netEach * qty - fifoCost;
				c.outcomeText2 = (many ? qtyTag + " " : "") + "vs your " + Fmt.compact(fifoCost) + " cost: " + signed(profit) + " profit";
				c.outcomeColor2 = profit >= 0 ? Palette.GREEN : Palette.RED;
			}
			else if (many)
			{
				c.outcomeText2 = qtyTag + ": " + Fmt.full(netEach * qty) + " back if all sell";
				c.outcomeColor2 = Palette.LIGHT;
			}
		}
		else
		{
			final FlipData live = plugin.viewFor(geId);
			if (live == null || live.getSell() <= 0)
			{
				return;
			}
			final long resell = live.getSell();
			final long each = GeTax.net(price, resell);
			c.outcomeText = "Resells at " + Fmt.compact(resell) + ": " + signed(each) + ea + " after tax";
			c.outcomeColor = each >= 0 ? Palette.GREEN : Palette.RED;
			if (many)
			{
				c.outcomeText2 = qtyTag + ": " + signed(each * qty) + " total (tax " + Fmt.full(GeTax.tax(resell) * qty) + ")";
				c.outcomeColor2 = each >= 0 ? Palette.GREEN : Palette.RED;
			}
		}
	}

	private static String signed(long v)
	{
		return v >= 0 ? "+" + Fmt.full(v) : Fmt.full(v);
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

	/** Registers the chart's timeframe tag (drawn by the painter at a fixed card
	 *  offset) as the clickable timeframe control for a full card at (cardX,
	 *  cardY). No-op when the card drew no chart. */
	private void notePill(int cardX, int cardY, String label, java.awt.FontMetrics fm, boolean term)
	{
		final Rectangle local = term
			? GeItemInfoPainter.termTagBounds(fm, label, plugin.chartTf().isTrades())
			: GeItemInfoPainter.chartTagBounds(fm, label, plugin.chartTf().isTrades());
		if (local != null)
		{
			tfPillBounds = new Rectangle(cardX + local.x, cardY + local.y, local.width, local.height);
		}
	}

	/** A quiet "hold shift" cue after the timeframe tag so users discover the
	 *  chart-view selector. Drawn only when Shift is NOT held (the chips replace
	 *  it when it is). */
	private void drawShiftHint(Graphics2D g, int cardX, int cardY, java.awt.FontMetrics fm, boolean term)
	{
		final Rectangle tag = term
			? GeItemInfoPainter.termTagBounds(fm, plugin.chartTf().label, plugin.chartTf().isTrades())
			: GeItemInfoPainter.chartTagBounds(fm, plugin.chartTf().label, plugin.chartTf().isTrades());
		if (tag == null)
		{
			return;
		}
		final int x = cardX + tag.x + tag.width + 4;
		final int base = cardY + tag.y + fm.getAscent() - 1;
		final String hint = "hold shift";
		g.setColor(new Color(0, 0, 0, 170));
		g.drawString(hint, x + 1, base + 1);
		g.setColor(Palette.SUBTLE_CANVAS);
		g.drawString(hint, x, base);
	}

	private static final PriceCheckPlugin.ChartTf[] TF_TIME =
		{PriceCheckPlugin.ChartTf.LATEST, PriceCheckPlugin.ChartTf.D1, PriceCheckPlugin.ChartTf.D7};
	private static final PriceCheckPlugin.ChartTf[] TF_TRADES =
		{PriceCheckPlugin.ChartTf.T10, PriceCheckPlugin.ChartTf.T20, PriceCheckPlugin.ChartTf.T30};

	/** Draws the chart-view selector over the chart's timeframe tag while Shift is
	 *  held: time views (Latest/24h/7d) on the top row, raw-trade views (10/20/30)
	 *  below. Registers each chip's canvas hit box so it selects that view. */
	private void drawTfChips(Graphics2D g, int cardX, int cardY, java.awt.FontMetrics fm, boolean term)
	{
		final Rectangle tag = term
			? GeItemInfoPainter.termTagBounds(fm, plugin.chartTf().label, plugin.chartTf().isTrades())
			: GeItemInfoPainter.chartTagBounds(fm, plugin.chartTf().label, plugin.chartTf().isTrades());
		if (tag == null)
		{
			return;
		}
		final int h = tag.height;
		final int x0 = cardX + tag.x;
		final int y0 = cardY + tag.y;
		final int rowGap = 2;
		int maxRow = 0;
		for (final PriceCheckPlugin.ChartTf[] row : new PriceCheckPlugin.ChartTf[][]{TF_TIME, TF_TRADES})
		{
			int rw = 0;
			for (final PriceCheckPlugin.ChartTf tf : row)
			{
				rw += fm.stringWidth(tf.label) + 8 + 2;
			}
			maxRow = Math.max(maxRow, rw);
		}
		g.setColor(Palette.INK);
		g.fillRoundRect(x0 - 1, y0 - 1, maxRow + 2, h * 2 + rowGap + 2, 5, 5);
		drawTfRow(g, TF_TIME, x0, y0, h, fm);
		drawTfRow(g, TF_TRADES, x0, y0 + h + rowGap, h, fm);
	}

	private void drawTfRow(Graphics2D g, PriceCheckPlugin.ChartTf[] row, int x0, int y, int h, java.awt.FontMetrics fm)
	{
		int x = x0;
		final int base = y + fm.getAscent() - 1;
		for (final PriceCheckPlugin.ChartTf tf : row)
		{
			final String s = tf.label;
			final int w = fm.stringWidth(s) + 8;
			final boolean active = tf == plugin.chartTf();
			g.setColor(active ? new Color(0xe6, 0xc6, 0x67, 70) : new Color(0, 0, 0, 150));
			g.fillRoundRect(x, y, w, h, 4, 4);
			g.setColor(active ? Palette.GOLD : Palette.SUBTLE);
			g.drawRoundRect(x, y, w, h, 4, 4);
			g.setColor(new Color(0, 0, 0, 180));
			g.drawString(s, x + 5, base + 1);
			g.setColor(active ? Palette.GOLD : Palette.LIGHT);
			g.drawString(s, x + 4, base);
			final PriceCheckPlugin.ChartTf sel = tf;
			tfChipHits.add(new Object[]{new Rectangle(x, y, w, h), (Runnable) () -> plugin.setChartTf(sel)});
			x += w + 2;
		}
	}

	/** The "Latest" view: widen from the last hour until the window holds enough
	 *  traded windows to form a real shape, so a big-ticket item that trades a few
	 *  times an hour still draws instead of starving on a bare 1h tail. */
	private static ItemChart.Series seriesLatest(PriceCheckApiClient.SeriesData sd)
	{
		// Start at 4h and widen: a bare last hour renders as a near-flat ribbon on
		// a liquid item, so give Latest enough traded windows to show real detail
		// while staying the most recent view.
		for (final long w : new long[]{4 * 3600L, 8 * 3600L})
		{
			final ItemChart.Series s = seriesWindow(sd, w);
			if (tradedCount(s) >= 12)
			{
				return s;
			}
		}
		return seriesWindow(sd, 0L);   // fall back to the whole day
	}

	private static int tradedCount(ItemChart.Series s)
	{
		int n = 0;
		if (s.ts != null && s.high != null && s.low != null)
		{
			for (int i = 0; i < s.ts.length; i++)
			{
				if (s.high[i] > 0 && s.low[i] > 0)
				{
					n++;
				}
			}
		}
		return n;
	}

	private static ItemChart.Series seriesWindow(PriceCheckApiClient.SeriesData sd, long windowSec)
	{
		int from = 0;
		if (windowSec > 0)
		{
			final long cut = sd.ts[sd.ts.length - 1] - windowSec;
			while (from < sd.ts.length && sd.ts[from] < cut)
			{
				from++;
			}
			if (sd.ts.length - from < 2)
			{
				from = Math.max(0, sd.ts.length - 2);
			}
		}
		final ItemChart.Series s = new ItemChart.Series();
		if (from == 0)
		{
			s.ts = sd.ts;
			s.high = sd.ah;
			s.low = sd.al;
			s.hvol = sd.hv;
			s.lvol = sd.lv;
		}
		else
		{
			final int n = sd.ts.length;
			s.ts = java.util.Arrays.copyOfRange(sd.ts, from, n);
			s.high = sd.ah != null ? java.util.Arrays.copyOfRange(sd.ah, from, n) : null;
			s.low = sd.al != null ? java.util.Arrays.copyOfRange(sd.al, from, n) : null;
			s.hvol = sd.hv != null ? java.util.Arrays.copyOfRange(sd.hv, from, n) : null;
			s.lvol = sd.lv != null ? java.util.Arrays.copyOfRange(sd.lv, from, n) : null;
		}
		return s;
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

	private Rectangle geBounds()
	{
		Widget w = client.getWidget(GE_GROUP, 2);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(GE_GROUP, 0);
		}
		return w != null ? w.getBounds() : null;
	}

	/** {x, y, width}: the card takes wantW where the chosen side has room and
	 *  steps down to the compact width otherwise, never covering the GE. */
	/** How tall the terminal card may draw before it would cover the chat input.
	 *  Full canvas below the anchor, but clamped to sit above the chat box whenever
	 *  the card's column overlaps it in x. The painter clamps its tape to fit. */
	private int termMaxHeight(int[] anchor)
	{
		final int topY = anchor[1];
		int budget = client.getCanvasHeight() - topY - 8;
		final Rectangle cb = plugin.chatboxBounds();
		if (cb != null)
		{
			final int cardL = anchor[0], cardR = anchor[0] + anchor[2];
			final boolean overlapsX = cardL < cb.x + cb.width && cardR > cb.x;
			if (overlapsX)
			{
				budget = Math.min(budget, cb.y - topY - 6);
			}
		}
		return Math.max(0, budget);
	}

	private int[] anchorFor(int wantW)
	{
		final Rectangle b = geBounds();
		if (b == null)
		{
			return new int[]{10, 120, wantW};
		}
		final int y = 8;
		final int narrow = GeItemInfoPainter.W;
		if (b.x - wantW - 8 >= 4)
		{
			return new int[]{b.x - wantW - 8, y, wantW};
		}
		final int rightX = b.x + b.width + 8;
		if (rightX + wantW <= client.getCanvasWidth() - 4)
		{
			return new int[]{rightX, y, wantW};
		}
		if (b.x - narrow - 8 >= 4)
		{
			return new int[]{b.x - narrow - 8, y, narrow};
		}
		return new int[]{rightX, y, narrow};
	}
}

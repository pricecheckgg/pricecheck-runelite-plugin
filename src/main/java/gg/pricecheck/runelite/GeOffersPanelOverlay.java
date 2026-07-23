package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.KeyCode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The active-offers board, docked to the right of the open Grand Exchange grid.
 * One two-line block per relevant offer: a B/S chip, the item, the live verdict,
 * then your price, how close it sits to a real fill, the last trade on your side,
 * and the coarse pressure lean. Overview only; the mini-cards own the slot and
 * set-up screens. Painted by hand like the advisor box so it matches the
 * plugin's look, and the painter is a static function over plain rows so the
 * headless preview can render it without a Client.
 *
 * Visibility is decided by one order-independent predicate on the plugin
 * (geOffersPanelVisible): the mini-cards read the same predicate to yield the
 * right column, so the two never fight over the same dock.
 */
class GeOffersPanelOverlay extends Overlay
{
	static final int W = 250;
	private static final int PAD = 9;
	private static final Color BAR_TRACK = new Color(0xff, 0xff, 0xff, 24);

	private static final Color FRAME = new Color(0xe6, 0xc6, 0x67, 62);
	private static final Color RULE = new Color(0xe6, 0xc6, 0x67, 38);
	private static final Color NAME = new Color(0xde, 0xd8, 0xc8);
	private static final Color SHADOW = new Color(0, 0, 0, 180);
	private static final Color CHIP_BUY = new Color(0x49, 0xc9, 0x7f);
	private static final Color CHIP_SELL = new Color(0xd9, 0x5c, 0x5e);
	private static final Color CHIP_INK = new Color(0x9a, 0x91, 0x7c);
	private static final Color CHIP_TEXT = new Color(0x12, 0x0e, 0x08);

	private static final int BTN = 14;
	private static final String COLLAPSED_KEY = "offersPanelCollapsed";

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final ConfigManager configManager;

	// Written on the mouse thread, read on the render thread (same as the advisor
	// box). collapsed persists across sessions; toggleBounds is the canvas-space
	// hit box for the [-]/[+] button, valid only while Shift shows it.
	private volatile boolean collapsed;
	private volatile Rectangle toggleBounds;

	GeOffersPanelOverlay(Client client, PriceCheckPlugin plugin, ConfigManager configManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.configManager = configManager;
		this.collapsed = Boolean.parseBoolean(configManager.getConfiguration(PriceCheckConfig.GROUP, COLLAPSED_KEY));
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Mouse hook from the plugin: collapse/expand when the Shift-only button is
	 *  clicked. Mirrors OfferAdvisorOverlay.handleClick. */
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
		if (!plugin.geOffersPanelEnabled())
		{
			return null;
		}
		final List<Row> rows = buildRows();
		if (rows.isEmpty())
		{
			return null;
		}
		// Overnight overlay mode draws the board larger; the dock reserves the
		// scaled width so it still never overlaps the GE.
		final double scale = plugin.overlayScale();
		final boolean term = plugin.terminalOffers();
		final int effW = (int) Math.round((term ? TERM_W : W) * scale);
		final int y = 8;
		final int x;
		if (plugin.isGrandExchangeOpen())
		{
			// GE open (overview OR a slot/offer-status screen): dock to the right
			// of the GE window so the board never vanishes when you open a slot.
			// On the overview the mini-cards yield this column via
			// geOffersPanelVisible; the single item card anchors left, so no
			// overlap on a slot screen either.
			final Rectangle b = plugin.geGridBounds();
			if (b == null)
			{
				return null;
			}
			x = b.x + b.width + 8;
			if (x + effW > client.getCanvasWidth() - 4)
			{
				return null;   // no room to the right; never overlap the GE
			}
		}
		else
		{
			// GE closed: float in the top-right so the board stays up like the
			// advisor box does on the left.
			x = client.getCanvasWidth() - effW - 8;
			if (x < 4)
			{
				return null;
			}
		}
		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		final java.awt.geom.AffineTransform save = g.getTransform();
		g.translate(x, y);
		g.scale(scale, scale);
		final Result r = term ? paintTerminal(g, rows, collapsed, shift) : paint(g, rows, collapsed, shift);
		g.setTransform(save);
		if (r.button != null)
		{
			toggleBounds = new Rectangle(
				x + (int) Math.round(r.button.x * scale),
				y + (int) Math.round(r.button.y * scale),
				(int) Math.round(r.button.width * scale),
				(int) Math.round(r.button.height * scale));
		}
		return null;
	}

	private List<Row> buildRows()
	{
		final List<Row> rows = new ArrayList<>();
		final List<OfferAdvice> advice = plugin.getAdvice();
		final long nowSec = System.currentTimeMillis() / 1000L;
		for (int slot = 0; slot < 8; slot++)
		{
			final TrackedOffer t = plugin.trackedAt(slot);
			if (t == null || !t.isRelevant())
			{
				continue;
			}
			rows.add(buildRow(t, adviceForSlot(advice, slot), nowSec));
		}
		return rows;
	}

	private Row buildRow(TrackedOffer t, OfferAdvice a, long nowSec)
	{
		final Row r = new Row();
		final int itemId = t.getItemId();
		final FlipData live = plugin.liveFor(itemId);
		// Margin/whole-flip profit follow the selected chart timeframe; the closeness
		// edge and last-trade fallback below stay on the live quote (they answer
		// "will this fill soon", which is inherently about the live market).
		final FlipData view = plugin.viewFor(itemId);
		final boolean sell = t.getState() == GrandExchangeOfferState.SELLING
			|| t.getState() == GrandExchangeOfferState.SOLD;

		r.chip = sell ? "S" : "B";
		r.chipColor = t.isDone() ? CHIP_INK : (sell ? CHIP_SELL : CHIP_BUY);

		String name = a != null ? a.getItemName() : null;
		if (name == null || name.isEmpty() || name.startsWith("Item "))
		{
			name = live != null && live.getName() != null ? live.getName() : "#" + itemId;
		}
		r.name = name;

		if (a != null && a.getKind() != OfferAdvice.Kind.NO_DATA && a.getShortText() != null && !a.getShortText().isEmpty())
		{
			r.verdict = a.getShortText();
			r.verdictColor = a.getColor();
		}
		else
		{
			r.verdict = "";
			r.verdictColor = Palette.SUBTLE_CANVAS;
		}

		r.price = t.getPrice();
		r.liveMargin = view != null ? view.getProfit() : 0;
		r.totalQty = Math.max(0, t.getTotalQty());
		r.soldQty = Math.max(0, Math.min(r.totalQty, t.getSoldQty()));
		r.unfilledQty = t.isActive() ? Math.max(0, r.totalQty - r.soldQty) : 0;
		r.posProfit = r.liveMargin * r.totalQty;

		final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(itemId);
		final long[] cl = closeness(t, sd, live);
		r.closenessGp = cl[0];
		r.seated = cl[1] == 1;
		r.trend = gapTrend(sell, sd);

		r.lastTrade = lastTrade(itemId, sell, live, nowSec);

		r.pressure = pressureLean(sd, nowSec);
		if ("sell".equals(r.pressure))
		{
			r.pressureColor = Palette.RED;
		}
		else if ("buy".equals(r.pressure))
		{
			r.pressureColor = Palette.GREEN;
		}
		else
		{
			r.pressureColor = Palette.SUBTLE_CANVAS;
		}
		return r;
	}

	private static OfferAdvice adviceForSlot(List<OfferAdvice> advice, int slot)
	{
		for (final OfferAdvice a : advice)
		{
			if (a.getSlot() == slot)
			{
				return a;
			}
		}
		return null;
	}

	/** Last trade on the side that would fill this offer, "@price age"; falls
	 *  back to the live edge quote (no age) when no matching print is on tape. */
	private String lastTrade(int itemId, boolean sell, FlipData live, long nowSec)
	{
		final List<GeItemInfoPainter.Print> prints = plugin.cardPrintsFor(itemId);
		for (int i = prints.size() - 1; i >= 0; i--)
		{
			final GeItemInfoPainter.Print p = prints.get(i);
			// A SELL fills as buyers print UP (buySide=true); a BUY fills as
			// sellers print DOWN (buySide=false).
			if (p.price > 0 && p.buySide == sell)
			{
				return "@" + Fmt.compact(p.price) + " " + ageShort(Math.max(0, nowSec - p.ts));
			}
		}
		final long edge = sell
			? (live != null ? live.getSell() : 0)
			: (live != null ? live.getBuy() : 0);
		return edge > 0 ? "@" + Fmt.compact(edge) : null;
	}

	/** {gpGap, seated 1/0}; gpGap -1 unknown, 0 seated, else the exact gp your
	 *  price sits from the fill edge. Edge is the LIVE quote when present
	 *  (consistent with liveMargin), the series only as a fallback. */
	private static long[] closeness(TrackedOffer o, PriceCheckApiClient.SeriesData s, FlipData live)
	{
		final long price = o.getPrice();
		if (price <= 0)
		{
			return new long[]{-1, 0};
		}
		final boolean sell = o.getState() == GrandExchangeOfferState.SELLING
			|| o.getState() == GrandExchangeOfferState.SOLD;
		final long edge = sell
			? (live != null && live.getSell() > 0 ? live.getSell() : lastNonZero(s == null ? null : s.ah))
			: (live != null && live.getBuy() > 0 ? live.getBuy() : lastNonZero(s == null ? null : s.al));
		if (edge <= 0)
		{
			return new long[]{-1, 0};
		}
		final boolean seated = sell ? price <= edge : price >= edge;
		if (seated)
		{
			return new long[]{0, 1};   // at/through the fill edge: fills now
		}
		// The exact gp to a real fill - how far to move to head the queue. The
		// colour carries near/far so the raw gp still reads as close or distant.
		final long dist = sell ? (price - edge) : (edge - price);
		return new long[]{Math.max(1, dist), 0};
	}

	/** Is the fill-side market price moving TOWARD our resting offer (so a fill
	 *  is coming) or away from it? +1 closing, -1 drifting, 0 flat/unknown. A
	 *  SELL fills as the insta-buy (high) rises to our ask; a BUY fills as the
	 *  insta-sell (low) falls to our bid. Compares the newest real print on that
	 *  side to one roughly half an hour earlier. */
	private static int gapTrend(boolean sell, PriceCheckApiClient.SeriesData s)
	{
		if (s == null || s.ts == null)
		{
			return 0;
		}
		final long[] side = sell ? s.ah : s.al;
		final int[] vol = sell ? s.hv : s.lv;
		if (side == null)
		{
			return 0;
		}
		long now = 0;
		long prev = 0;
		int seen = 0;
		for (int i = side.length - 1; i >= 0 && seen < 8; i--)
		{
			if (side[i] > 0 && (vol == null || i >= vol.length || vol[i] > 0))
			{
				if (now == 0)
				{
					now = side[i];
				}
				prev = side[i];
				seen++;
			}
		}
		if (now <= 0 || prev <= 0 || seen < 3)
		{
			return 0;
		}
		if (Math.abs(now - prev) * 1000 < now)
		{
			return 0;   // under ~0.1% of drift either way reads as flat
		}
		final boolean closing = sell ? now > prev : now < prev;
		return closing ? 1 : -1;
	}

	private static long lastNonZero(long[] arr)
	{
		if (arr == null)
		{
			return 0;
		}
		for (int i = arr.length - 1; i >= 0; i--)
		{
			if (arr[i] > 0)
			{
				return arr[i];
			}
		}
		return 0;
	}

	/** Coarse trade-pressure lean over the freshest window with enough volume:
	 *  "sell" when insta-sells dominate (downward), "buy" when insta-buys do,
	 *  "flat" otherwise. Null when no window carries enough trades. A rough
	 *  reflection of GeItemInfoPainter's pressure read, never a probability. */
	private static String pressureLean(PriceCheckApiClient.SeriesData s, long nowSec)
	{
		if (s == null || s.ts == null || s.ts.length == 0)
		{
			return null;
		}
		for (final int hrs : new int[]{4, 8, 24})
		{
			final long cut = nowSec - hrs * 3600L;
			long hv = 0;
			long lv = 0;
			for (int i = s.ts.length - 1; i >= 0 && s.ts[i] >= cut; i--)
			{
				hv += s.hv != null && i < s.hv.length && s.hv[i] > 0 ? s.hv[i] : 0;
				lv += s.lv != null && i < s.lv.length && s.lv[i] > 0 ? s.lv[i] : 0;
			}
			final long total = hv + lv;
			if (total >= 12 || (hrs == 24 && total >= 6))
			{
				final long lvPct = Math.round(100.0 * lv / total);
				if (lvPct >= 58)
				{
					return "sell";
				}
				if (lvPct <= 42)
				{
					return "buy";
				}
				return "flat";
			}
		}
		return null;
	}

	private static String ageShort(long secs)
	{
		if (secs < 60)
		{
			return secs + "s";
		}
		final long m = secs / 60;
		if (m < 60)
		{
			return m + "m";
		}
		final long h = m / 60;
		if (h < 24)
		{
			return h + "h";
		}
		return (h / 24) + "d";
	}

	/** One board row: everything two lines need, all plain data. */
	static final class Row
	{
		String chip;
		Color chipColor;
		String name;
		String verdict;
		Color verdictColor;
		long price;
		long liveMargin;    // per-unit live margin, advisor's notion
		long unfilledQty;   // open units on this offer (0 when done)
		long soldQty;       // units already bought/sold on this offer
		long totalQty;      // full offer size
		long posProfit;     // whole-flip profit at the live margin (margin x totalQty)
		long closenessGp;   // exact gp the market must still move to fill us; -1 unknown, 0 seated
		int trend;          // +1 the fill edge is moving toward us (fills soon), -1 drifting away, 0 flat
		boolean seated;
		String lastTrade;   // "@price age" or "@edge", null when none
		String pressure;    // "buy" | "sell" | "flat" | null
		Color pressureColor;
	}

	// ── Terminal (Bloomberg) blotter ───────────────────────────────
	// Same rows as the classic board, re-skinned amber-on-black to sit under the
	// terminal status bar. Routed here from render() when config.terminalOffers().
	static final int TERM_W = 292;

	static Result paintTerminal(Graphics2D g, List<Row> rows, boolean collapsed, boolean shiftHeld)
	{
		TerminalKit.hints(g);
		if (collapsed)
		{
			g.setFont(TerminalKit.monoB(11));
			final String t = "POSITIONS  " + rows.size();
			final int cw = 12 + g.getFontMetrics().stringWidth(t) + (shiftHeld ? BTN + 6 : 0) + 12;
			final int h = 22;
			g.setColor(TerminalKit.PANEL); g.fillRect(0, 0, cw, h);
			g.setColor(TerminalKit.BORDER); g.drawRect(0, 0, cw, h);
			g.setColor(TerminalKit.AMBERHI); g.drawString(t, 12, 15);
			return new Result(new Dimension(cw, h), shiftHeld ? drawToggle(g, cw, true) : null);
		}
		final int w = TERM_W;
		final int rowH = 30;
		final int headerH = 18;
		final int footerH = 18;
		final int h = headerH + rows.size() * rowH + footerH + 4;
		g.setColor(TerminalKit.PANEL); g.fillRect(0, 0, w, h);
		g.setColor(TerminalKit.BORDER); g.drawRect(0, 0, w, h);
		// header
		g.setColor(TerminalKit.TITLEBG); g.fillRect(1, 1, w - 2, 16);
		g.setFont(TerminalKit.monoB(10)); g.setColor(TerminalKit.AMBERHI);
		g.drawString("POSITIONS", 8, 12);
		g.setColor(TerminalKit.LABEL);
		g.drawString("· " + rows.size() + " OFFERS", 8 + g.getFontMetrics().stringWidth("POSITIONS") + 6, 12);
		final Rectangle btn = shiftHeld ? drawToggle(g, w, false) : null;
		g.setColor(TerminalKit.GRID); g.drawLine(1, 18, w - 1, 18);
		// rows
		int y = headerH + 4;
		int seated = 0;
		long total = 0;
		for (final Row r : rows)
		{
			if (r.seated) { seated++; }
			total += r.posProfit;
			paintTermRow(g, w, y, r);
			y += rowH;
		}
		// footer
		g.setColor(TerminalKit.TITLEBG); g.fillRect(1, h - footerH, w - 2, footerH - 1);
		g.setColor(TerminalKit.GRID); g.drawLine(1, h - footerH, w - 1, h - footerH);
		g.setFont(TerminalKit.monoB(10));
		g.setColor(rows.size() > 0 && seated == rows.size() ? TerminalKit.GREEN : TerminalKit.AMBER);
		g.drawString(seated + "/" + rows.size() + " SEATED", 8, h - 5);
		g.setColor(total >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		TerminalKit.rt(g, "NET " + (total >= 0 ? "+" : "") + Fmt.compact(total), w - 8, h - 5);
		return new Result(new Dimension(w, h), btn);
	}

	private static void paintTermRow(Graphics2D g, int w, int top, Row r)
	{
		final int base1 = top + 11;
		final int base2 = top + 24;
		// side chip + name (line 1), verdict right-aligned
		int nameX = 8;
		if (r.chip != null && !r.chip.isEmpty())
		{
			g.setFont(TerminalKit.monoB(11));
			g.setColor(r.chipColor != null ? r.chipColor : TerminalKit.AMBER);
			g.drawString(r.chip, 8, base1);
			nameX = 8 + g.getFontMetrics().stringWidth(r.chip) + 6;
		}
		g.setFont(TerminalKit.monoB(11));
		final FontMetrics fm = g.getFontMetrics();
		int verdW = 0;
		if (r.verdict != null)
		{
			g.setColor(r.verdictColor != null ? r.verdictColor : TerminalKit.AMBER);
			verdW = fm.stringWidth(r.verdict);
			TerminalKit.rt(g, r.verdict, w - 8, base1);
		}
		g.setColor(TerminalKit.AMBERHI);
		g.drawString(ellipsize(r.name, fm, w - 8 - verdW - 8 - nameX), nameX, base1);
		// line 2: qty @ price | closeness/drift | uP&L
		g.setFont(TerminalKit.mono(10));
		g.setColor(TerminalKit.LABEL);
		final long q = r.totalQty > 0 ? r.totalQty : r.unfilledQty;
		g.drawString(q + " @ " + Fmt.compact(r.price), 8, base2);
		final String mid;
		final Color midC;
		if (r.seated) { mid = "seated"; midC = TerminalKit.GREEN; }
		else if (r.closenessGp > 0)
		{
			mid = Fmt.compact(r.closenessGp) + (r.trend > 0 ? " closing" : r.trend < 0 ? " drifting" : " away");
			midC = r.trend > 0 ? TerminalKit.GREEN : r.trend < 0 ? TerminalKit.RED : TerminalKit.LABEL;
		}
		else { mid = ""; midC = TerminalKit.LABEL; }
		g.setColor(midC);
		g.drawString(mid, 8 + 96, base2);
		g.setFont(TerminalKit.monoB(11));
		g.setColor(r.posProfit >= 0 ? TerminalKit.GREEN : TerminalKit.RED);
		TerminalKit.rt(g, (r.posProfit >= 0 ? "+" : "") + Fmt.compact(r.posProfit), w - 8, base2);
		g.setColor(TerminalKit.GRID);
		g.drawLine(4, top + 28, w - 4, top + 28);
	}

	/** Draws the board (or a collapsed title pill) at 0,0 and reports its size
	 *  plus the Shift-only toggle's local hit box. Pure over its inputs. */
	static Result paint(Graphics2D g, List<Row> rows, boolean collapsed, boolean shiftHeld)
	{
		final Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// The pixel font must stay crisp: no text antialiasing.
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		final int lineH = fm.getHeight();
		final int headerH = fm.getHeight() + 5;
		final int baseline = 3 + fm.getAscent();
		final String title = "Active offers";
		final String count = " · " + rows.size();

		if (collapsed)
		{
			// A pill hugging the title, with room for the button on Shift.
			final int w = PAD + fm.stringWidth(title + count) + (shiftHeld ? BTN + 6 : 0) + PAD;
			final int h = headerH + 3;
			g.setColor(Palette.INK);
			g.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
			g.setColor(FRAME);
			g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
			shadowed(g, title, PAD, baseline, Palette.GOLD);
			shadowed(g, count, PAD + fm.stringWidth(title), baseline, Palette.SUBTLE_CANVAS);
			return new Result(new Dimension(w, h), shiftHeld ? drawToggle(g, w, true) : null);
		}

		final int rowH = 2 * lineH + 8;   // two text lines + a hairline fill bar
		final int footerH = lineH + 6;
		final int w = W;
		final int h = headerH + 4 + rows.size() * rowH + 5 + footerH + PAD - 4;

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

		shadowed(g, title, PAD, baseline, Palette.GOLD);
		shadowed(g, count, PAD + fm.stringWidth(title), baseline, Palette.SUBTLE_CANVAS);
		final Rectangle btn = shiftHeld ? drawToggle(g, w, false) : null;

		g.setColor(RULE);
		g.drawLine(PAD - 2, headerH, w - PAD + 2, headerH);

		int y = headerH + 4;
		int seatedCount = 0;
		long flipTotal = 0;
		for (final Row r : rows)
		{
			paintRow(g, fm, w, y, r);
			if (r.seated)
			{
				seatedCount++;
			}
			flipTotal += r.posProfit;
			y += rowH;
		}

		final int footY = y + 3;
		g.setColor(RULE);
		g.drawLine(PAD - 2, footY, w - PAD + 2, footY);
		final int fb = footY + 2 + fm.getAscent();
		final String left = seatedCount + "/" + rows.size() + " seated";
		shadowed(g, left, PAD, fb, Palette.SUBTLE_CANVAS);
		// Whole-board profit if every offer completes at the live margin.
		final String right = "all " + (flipTotal >= 0 ? "+" : "-") + Fmt.compact(Math.abs(flipTotal));
		final int rw = fm.stringWidth(right);
		shadowed(g, right, w - PAD - rw, fb, flipTotal >= 0 ? Palette.GREEN : Palette.RED);

		return new Result(new Dimension(w, h), btn);
	}

	/** The Shift-only [-]/[+] toggle at the header's top-right; returns its local
	 *  hit box. Same glyphs as the advisor box so the two read alike. */
	private static Rectangle drawToggle(Graphics2D g, int w, boolean collapsed)
	{
		final Rectangle btn = new Rectangle(w - BTN - 4, 2, BTN, BTN);
		final int cx = btn.x + BTN / 2;
		final int cy = btn.y + BTN / 2;
		g.setColor(SHADOW);
		g.drawLine(cx - 3 + 1, cy + 1, cx + 3 + 1, cy + 1);
		g.setColor(Palette.GOLD);
		g.drawLine(cx - 3, cy, cx + 3, cy);   // minus
		if (collapsed)
		{
			g.setColor(SHADOW);
			g.drawLine(cx + 1, cy - 3 + 1, cx + 1, cy + 3 + 1);
			g.setColor(Palette.GOLD);
			g.drawLine(cx, cy - 3, cx, cy + 3);   // vertical stroke turns it into a plus
		}
		return btn;
	}

	/** What one paint produced: the box size + the toggle's local hit box (null
	 *  unless Shift drew it). */
	static final class Result
	{
		final Dimension size;
		final Rectangle button;

		Result(Dimension size, Rectangle button)
		{
			this.size = size;
			this.button = button;
		}
	}

	private static void paintRow(Graphics2D g, FontMetrics fm, int w, int top, Row r)
	{
		final int lineH = fm.getHeight();
		final int base1 = top + fm.getAscent();

		// Line 1: chip pill, name, verdict on the right.
		int nameX = PAD;
		if (r.chip != null && !r.chip.isEmpty())
		{
			final int cw = fm.stringWidth(r.chip) + 8;
			g.setColor(SHADOW);
			g.fillRoundRect(PAD + 1, top + 1, cw, lineH - 1, 5, 5);
			g.setColor(r.chipColor);
			g.fillRoundRect(PAD, top, cw, lineH - 1, 5, 5);
			g.setColor(CHIP_TEXT);
			g.drawString(r.chip, PAD + 4, base1);
			nameX = PAD + cw + 5;
		}

		final String verdict = r.verdict == null ? "" : r.verdict;
		final int vw = fm.stringWidth(verdict);
		if (!verdict.isEmpty())
		{
			shadowed(g, verdict, w - PAD - vw, base1, r.verdictColor);
		}

		final int nameMax = w - PAD - (verdict.isEmpty() ? 0 : vw + 6) - nameX;
		shadowed(g, ellipsize(r.name, fm, nameMax), nameX, base1, NAME);

		// Line 2: price [x qty] | closeness | whole-flip profit | last trade |
		// lean. Floors are the price and closeness; the rest drop back-to-front
		// (lean, then last trade) so the whole-flip profit survives longest.
		final int base2 = top + lineH + fm.getAscent();
		final int maxW = w - 2 * PAD;
		final List<String> segs = new ArrayList<>();
		final List<Color> cols = new ArrayList<>();
		segs.add(Fmt.compact(r.price) + (r.totalQty > 1 ? " x" + r.totalQty : ""));
		cols.add(Palette.LIGHT);
		if (r.seated)
		{
			segs.add("at mkt");
			cols.add(Palette.GREEN);
		}
		else if (r.closenessGp > 0)
		{
			// How far the market must still move to fill us, and whether it is
			// coming (closing -> fills soon) or drifting off. The point is to
			// watch the offer fill, not to reprice it.
			final String word = r.trend > 0 ? "closing" : r.trend < 0 ? "drifting" : "away";
			segs.add(Fmt.compact(r.closenessGp) + " " + word);
			cols.add(r.trend > 0 ? Palette.GREEN : r.trend < 0 ? Palette.RED : Palette.SUBTLE_CANVAS);
		}
		final List<String> opt = new ArrayList<>();
		final List<Color> optc = new ArrayList<>();
		// Whole-flip profit (margin x full size) - the number that actually
		// matters, which the per-unit verdict never shows. Only when qty > 1
		// adds information (for a single unit it equals the verdict).
		if (r.totalQty > 1 && r.posProfit != 0)
		{
			opt.add((r.posProfit >= 0 ? "+" : "-") + Fmt.compact(Math.abs(r.posProfit)));
			optc.add(r.posProfit >= 0 ? Palette.GREEN : Palette.RED);
		}
		if (r.lastTrade != null)
		{
			opt.add(r.lastTrade);
			optc.add(Palette.SUBTLE_CANVAS);
		}
		if (r.pressure != null)
		{
			opt.add(r.pressure);
			optc.add(r.pressureColor);
		}

		final String sep = "  ";
		List<String> pick = segs;
		List<Color> pickC = cols;
		for (int take = opt.size(); take >= 0; take--)
		{
			final List<String> s = new ArrayList<>(segs);
			final List<Color> c = new ArrayList<>(cols);
			for (int i = 0; i < take; i++)
			{
				s.add(opt.get(i));
				c.add(optc.get(i));
			}
			int tot = 0;
			for (int i = 0; i < s.size(); i++)
			{
				tot += fm.stringWidth(s.get(i));
				if (i > 0)
				{
					tot += fm.stringWidth(sep);
				}
			}
			if (tot <= maxW || take == 0)
			{
				pick = s;
				pickC = c;
				break;
			}
		}
		int x = PAD;
		for (int i = 0; i < pick.size(); i++)
		{
			if (i > 0)
			{
				x += fm.stringWidth(sep);
			}
			shadowed(g, pick.get(i), x, base2, pickC.get(i));
			x += fm.stringWidth(pick.get(i));
		}

		// Hairline fill bar at the row foot: the bought/sold fraction, in the
		// offer's side colour over a dim track, so progress reads across the
		// whole board at a glance without opening each slot.
		if (r.totalQty > 1)
		{
			final int barY = top + 2 * lineH + 3;
			g.setColor(BAR_TRACK);
			g.fillRect(PAD, barY, maxW, 2);
			final int fw = (int) Math.max(0, Math.min(maxW, maxW * r.soldQty / Math.max(1, r.totalQty)));
			if (fw > 0)
			{
				g.setColor(r.chipColor);
				g.fillRect(PAD, barY, fw, 2);
			}
		}
	}

	private static void shadowed(Graphics2D g, String s, int x, int y, Color c)
	{
		g.setColor(SHADOW);
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}

	private static String ellipsize(String s, FontMetrics fm, int max)
	{
		if (s == null)
		{
			return "";
		}
		if (fm.stringWidth(s) <= max)
		{
			return s;
		}
		String t = s;
		while (t.length() > 1 && fm.stringWidth(t + "..") > max)
		{
			t = t.substring(0, t.length() - 1);
		}
		return t + "..";
	}
}

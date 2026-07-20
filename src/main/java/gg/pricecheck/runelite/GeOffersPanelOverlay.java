package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
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
	static final int W = 224;
	private static final int PAD = 9;

	private static final Color FRAME = new Color(0xe6, 0xc6, 0x67, 62);
	private static final Color RULE = new Color(0xe6, 0xc6, 0x67, 38);
	private static final Color NAME = new Color(0xde, 0xd8, 0xc8);
	private static final Color SHADOW = new Color(0, 0, 0, 180);
	private static final Color CHIP_BUY = new Color(0x49, 0xc9, 0x7f);
	private static final Color CHIP_SELL = new Color(0xd9, 0x5c, 0x5e);
	private static final Color CHIP_INK = new Color(0x9a, 0x91, 0x7c);
	private static final Color CHIP_TEXT = new Color(0x12, 0x0e, 0x08);

	private final Client client;
	private final PriceCheckPlugin plugin;

	GeOffersPanelOverlay(Client client, PriceCheckPlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// The render gate IS the shared predicate, so nothing here can disagree
		// with the mini-cards' right-column guard.
		if (!plugin.geOffersPanelVisible())
		{
			return null;
		}
		final Rectangle b = plugin.geGridBounds();
		if (b == null)
		{
			return null;
		}
		final int x = b.x + b.width + 8;
		final int y = 8;
		if (x + W > client.getCanvasWidth() - 4)
		{
			return null;   // never falls back to the left
		}
		final List<Row> rows = buildRows();
		if (rows.isEmpty())
		{
			return null;
		}
		g.translate(x, y);
		paint(g, rows);
		g.translate(-x, -y);
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
		r.liveMargin = live != null ? live.getProfit() : 0;
		r.unfilledQty = t.isActive() ? Math.max(0, t.getTotalQty() - t.getSoldQty()) : 0;

		final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(itemId);
		final int[] cl = closeness(t, sd, live);
		r.closenessPct = cl[0];
		r.seated = cl[1] == 1;

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

	/** {pct 0..100, seated 1/0}; pct -1 unknown. Edge is the LIVE quote when
	 *  present (consistent with liveMargin), the series only as a fallback. */
	private static int[] closeness(TrackedOffer o, PriceCheckApiClient.SeriesData s, FlipData live)
	{
		final long price = o.getPrice();
		if (price <= 0)
		{
			return new int[]{-1, 0};
		}
		final boolean sell = o.getState() == GrandExchangeOfferState.SELLING
			|| o.getState() == GrandExchangeOfferState.SOLD;
		final long edge = sell
			? (live != null && live.getSell() > 0 ? live.getSell() : lastNonZero(s == null ? null : s.ah))
			: (live != null && live.getBuy() > 0 ? live.getBuy() : lastNonZero(s == null ? null : s.al));
		if (edge <= 0)
		{
			return new int[]{-1, 0};
		}
		final boolean seated = sell ? price <= edge : price >= edge;
		final long dist = sell ? Math.max(0, price - edge) : Math.max(0, edge - price);
		final int pct = (int) Math.max(0, Math.min(100, 100 - dist * 100 / Math.max(1, edge)));
		return new int[]{pct, seated ? 1 : 0};
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
		int closenessPct;   // 0..100, -1 unknown
		boolean seated;
		String lastTrade;   // "@price age" or "@edge", null when none
		String pressure;    // "buy" | "sell" | "flat" | null
		Color pressureColor;
	}

	/** Draws the whole board at 0,0 and reports its size. Pure over its inputs. */
	static Dimension paint(Graphics2D g, List<Row> rows)
	{
		final Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// The pixel font must stay crisp: no text antialiasing.
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		final int lineH = fm.getHeight();
		final int headerH = fm.getHeight() + 5;
		final int rowH = 2 * lineH + 3;
		final int footerH = lineH + 6;

		final int w = W;
		final int h = headerH + 4 + rows.size() * rowH + 5 + footerH + PAD - 4;

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

		final int baseline = 3 + fm.getAscent();
		final String title = "Active offers";
		shadowed(g, title, PAD, baseline, Palette.GOLD);
		final String count = " · " + rows.size();
		shadowed(g, count, PAD + fm.stringWidth(title), baseline, Palette.SUBTLE_CANVAS);

		g.setColor(RULE);
		g.drawLine(PAD - 2, headerH, w - PAD + 2, headerH);

		int y = headerH + 4;
		int seatedCount = 0;
		long liveTotal = 0;
		for (final Row r : rows)
		{
			paintRow(g, fm, w, y, r);
			if (r.seated)
			{
				seatedCount++;
			}
			liveTotal += r.liveMargin * r.unfilledQty;
			y += rowH;
		}

		final int footY = y + 3;
		g.setColor(RULE);
		g.drawLine(PAD - 2, footY, w - PAD + 2, footY);
		final int fb = footY + 2 + fm.getAscent();
		final String left = seatedCount + "/" + rows.size() + " seated";
		shadowed(g, left, PAD, fb, Palette.SUBTLE_CANVAS);
		// Qty-weighted open spread: a real gp figure, not a per-unit sum.
		final String right = "±" + Fmt.compact(Math.abs(liveTotal)) + " live";
		final int rw = fm.stringWidth(right);
		shadowed(g, right, w - PAD - rw, fb, liveTotal >= 0 ? Palette.GREEN : Palette.RED);

		return new Dimension(w, h);
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

		// Line 2: floors kept, optionals dropped pressure-first when narrow.
		final int base2 = top + lineH + fm.getAscent();
		final List<String> segs = new ArrayList<>();
		final List<Color> cols = new ArrayList<>();
		segs.add("you " + Fmt.compact(r.price));
		cols.add(Palette.LIGHT);
		if (r.closenessPct >= 0)
		{
			segs.add(r.closenessPct + "% " + (r.seated ? "seat" : "off"));
			cols.add(r.seated ? Palette.GREEN : Palette.AMBER);
		}
		final List<String> opt = new ArrayList<>();
		final List<Color> optc = new ArrayList<>();
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
		final int maxW = w - 2 * PAD;
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

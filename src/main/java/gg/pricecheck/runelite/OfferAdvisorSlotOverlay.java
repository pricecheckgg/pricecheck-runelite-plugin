package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints each active offer's advice directly onto its Grand Exchange slot: a
 * status-coloured frame plus a bar across the bottom with the live status and
 * margin ("RAISE +12.9k", "OK +26.2k", "MARGIN DEAD"). The bar sits over the
 * slot's static offer-price caption, never the fill progress bar, so nothing
 * you actually watch is hidden. Attention hierarchy: DEAD greys the whole
 * slot, action states (RAISE/DROP/FALLING) get a bright frame + a direction
 * triangle, ON_TRACK stays dim with its margin quietly readable. Slot
 * geometry: widgets 465:7 .. 465:14, live only while 465:0 is up.
 */
class OfferAdvisorSlotOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int GE_WINDOW_CHILD = 0;
	private static final int FIRST_SLOT_CHILD = 7;
	private static final int SLOTS = 8;
	private static final int BAR_H = 15;

	private static final Stroke FRAME = new BasicStroke(2f);
	private static final Stroke HALO = new BasicStroke(4f);

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	OfferAdvisorSlotOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config)
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
		if (!config.showAdvisor())
		{
			return null;
		}
		// Hold Shift to peek at the untouched slots (price captions included).
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return null;
		}
		final Widget window = client.getWidget(GE_GROUP, GE_WINDOW_CHILD);
		if (window == null || window.isHidden())
		{
			return null;
		}
		final List<OfferAdvice> advice = plugin.getAdvice();
		if (advice.isEmpty())
		{
			return null;
		}

		final OfferAdvice[] bySlot = new OfferAdvice[SLOTS];
		for (OfferAdvice a : advice)
		{
			final int s = a.getSlot();
			if (s >= 0 && s < SLOTS)
			{
				bySlot[s] = a;
			}
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();

		for (int i = 0; i < SLOTS; i++)
		{
			final OfferAdvice a = bySlot[i];
			if (a == null || a.getKind() == OfferAdvice.Kind.NO_DATA)
			{
				continue;
			}
			final String label = a.getShortText();
			if (label == null || label.isEmpty())
			{
				continue;
			}
			final Widget slot = client.getWidget(GE_GROUP, FIRST_SLOT_CHILD + i);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			final Rectangle b = slot.getBounds();
			if (b == null || b.width < 24 || b.height < 24)
			{
				continue;
			}
			// Proximity to a real fill: how far this offer's price sits from the
			// last DETECTED trade on the side that would take it, in tenths of a
			// percent. -1 == no real print to measure (never a fake 0).
			int closeTenths = -1;
			final TrackedOffer t = plugin.trackedAt(i);
			if (t != null && t.isActive() && a.getKind() != OfferAdvice.Kind.DEAD)
			{
				final PriceCheckApiClient.SeriesData sd = plugin.cardSeriesFor(t.getItemId());
				closeTenths = closenessTenths(t, sd);
			}
			final long quietMs = plugin.slotQuietMs(i);
			paintSlot(g, fm, b, a.getKind(), a.getColor(), label, closeTenths, quietMs);
		}
		return null;
	}

	private void paintSlot(Graphics2D g, FontMetrics fm, Rectangle b, OfferAdvice.Kind kind, Color col, String label, int closeTenths, long quietMs)
	{
		final boolean dead = kind == OfferAdvice.Kind.DEAD;
		final boolean onTrack = kind == OfferAdvice.Kind.ON_TRACK;
		final boolean collect = kind == OfferAdvice.Kind.COLLECT;
		// A patient HOLD needs no action, so it reads calm (dim frame) and, because
		// it is neither RAISE nor DROP/FALLING, draws no direction arrow.
		final boolean quiet = onTrack || collect || kind == OfferAdvice.Kind.HOLD;

		// DEAD greys the whole slot so it reads from across the room.
		if (dead)
		{
			g.setColor(new Color(0, 0, 0, 70));
			g.fillRoundRect(b.x + 2, b.y + 2, b.width - 4, b.height - 4, 8, 8);
		}

		// Frame: dark halo pass, then the colour pass (bright for action, dim for quiet).
		g.setStroke(HALO);
		g.setColor(new Color(0, 0, 0, 140));
		g.drawRoundRect(b.x + 1, b.y + 1, b.width - 3, b.height - 3, 8, 8);
		g.setStroke(FRAME);
		g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), quiet ? 120 : 230));
		g.drawRoundRect(b.x + 1, b.y + 1, b.width - 3, b.height - 3, 8, 8);

		// Status bar: rounded-bottom, square top to meet the frame flush. It
		// covers the static offer-price caption (recoverable by opening the
		// slot), not the progress bar above it.
		final int barX = b.x + 2;
		final int barY = b.y + b.height - BAR_H - 2;
		final int barW = b.width - 4;
		g.setColor(Palette.INK);
		g.fillRoundRect(barX, barY, barW, BAR_H, 6, 6);
		g.fillRect(barX, barY, barW, 6);
		// Closeness wash from the left edge: fuller the nearer the offer sits to
		// a real fill (seated fills the bar), fading out as the gap widens. The
		// same honest gap as the number, never a modelled probability.
		if (!dead && closeTenths >= 0)
		{
			final int cap = 30;   // a 3.0% gap or worse leaves no wash
			final int near = cap - Math.min(closeTenths, cap);
			if (near > 0)
			{
				g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 26));
				final int washW = Math.max(4, barW * near / cap);
				g.fillRoundRect(barX, barY, washW, BAR_H, 6, 6);
			}
		}
		g.setColor(col);
		g.fillRect(barX, barY, barW, 2);   // signature coloured top edge

		// Direction triangle for the actionable states (drawn, not font glyphs).
		final boolean up = kind == OfferAdvice.Kind.RAISE_BUY;
		final boolean down = kind == OfferAdvice.Kind.DROP_SELL || kind == OfferAdvice.Kind.FALLING;
		final int triW = (up || down) ? 11 : 0;   // 7px glyph + 4px gap

		String text = label;
		final int maxTextW = barW - 4 - triW;
		if (fm.stringWidth(text) > maxTextW)
		{
			text = fitToWidth(fm, text, maxTextW);
		}
		final int totalW = triW + fm.stringWidth(text);
		int cx = barX + Math.max(1, (barW - totalW) / 2);
		final int midY = barY + 2 + (BAR_H - 2) / 2;

		if (up || down)
		{
			drawTriangle(g, cx, midY, up, col);
			cx += triW;
		}
		final int ty = barY + 2 + ((BAR_H - 2 - (fm.getAscent() + fm.getDescent())) / 2) + fm.getAscent();
		g.setColor(new Color(0, 0, 0, 205));
		g.drawString(text, cx + 1, ty + 1);
		g.setColor(col);
		g.drawString(text, cx, ty);

		// Right segment: proximity to a real fill. Never contradicts the centre
		// verdict - the seated "at mkt" shows only on non-action slots, and AMBER
		// stays reserved for the quiet-time caution. Nothing at all when there is
		// no real print to measure.
		String side = null;
		Color sideCol = Palette.SUBTLE_CANVAS;
		final boolean action = up || down;   // up/down are already computed above
		final String quietStr = (quiet && quietMs >= 10 * 60_000L) ? quietLabel(quietMs) : null;
		if (!dead && closeTenths > 0)
		{
			side = (closeTenths / 10) + "." + (closeTenths % 10) + "%";
			// grey -> gold as it nears a fill; AMBER is the caution colour, not "close".
			sideCol = closeTenths <= 10 ? Palette.GOLD : Palette.SUBTLE_CANVAS;
		}
		else if (!dead && closeTenths == 0 && !action)
		{
			// Seated: fills now. Only on non-action slots so green never fights a
			// RAISE/DROP/FALLING verdict. A seated-but-dry patient offer shows its wait.
			side = quietStr != null ? quietStr : "at mkt";
			sideCol = quietStr != null ? Palette.AMBER : Palette.GREEN;
		}
		else if (quietStr != null)
		{
			// No usable closeness (no print, DEAD, or a seated action slot): quiet time.
			side = quietStr;
			sideCol = Palette.AMBER;
		}
		if (side != null)
		{
			final int sw = fm.stringWidth(side);
			final int sx = barX + barW - sw - 5;
			if (sx > cx + fm.stringWidth(text) + 8)
			{
				g.setColor(new Color(0, 0, 0, 205));
				g.drawString(side, sx + 1, ty + 1);
				g.setColor(sideCol);
				g.drawString(side, sx, ty);
			}
		}
	}

	// 7x5 filled triangle, vertically centred on midY, with a shadow pass.
	private static void drawTriangle(Graphics2D g, int x, int midY, boolean up, Color col)
	{
		final int[] xs = { x, x + 3, x + 6 };
		final int[] ys = up ? new int[]{ midY + 3, midY - 3, midY + 3 } : new int[]{ midY - 3, midY + 3, midY - 3 };
		final int[] xsh = { x + 1, x + 4, x + 7 };
		final int[] ysh = up ? new int[]{ midY + 4, midY - 2, midY + 4 } : new int[]{ midY - 2, midY + 4, midY - 2 };
		g.setColor(new Color(0, 0, 0, 205));
		g.fillPolygon(xsh, ysh, 3);
		g.setColor(col);
		g.fillPolygon(xs, ys, 3);
	}

	private static String fitToWidth(FontMetrics fm, String s, int maxW)
	{
		if (fm.stringWidth(s) <= maxW)
		{
			return s;
		}
		while (s.length() > 1 && fm.stringWidth(s + "..") > maxW)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + "..";
	}

	// A fill-side print older than this in REAL time is treated as no live
	// reference. 24h keeps once-or-twice-a-day illiquid items readable (the card
	// seeds prints back a week for them) while suppressing genuinely dead sides.
	private static final long STALE_SECS = 24 * 3600L;

	/**
	 * How close an offer's price sits to the last DETECTED trade on the side that
	 * would fill it, in tenths of a percent of that trade. A SELL fills as buyers
	 * print UP at/through the ask -> measured against the last insta-buy print (ah
	 * at the newest window with hv&gt;0); a BUY fills as sellers print DOWN at/through
	 * the bid -> the last insta-sell print (al at the newest window with lv&gt;0).
	 * Returns: -1 unknown (no series/volume, unpriced offer, or too stale); 0
	 * seated (at/through the print, fills now); &gt;0 the gap in tenths of a percent
	 * (21 == 2.1%). A plain gap to a real print, never an invented probability.
	 */
	private static int closenessTenths(TrackedOffer t, PriceCheckApiClient.SeriesData sd)
	{
		if (t == null || sd == null)
		{
			return -1;
		}
		final long yourPrice = t.getPrice();
		if (yourPrice <= 0)
		{
			return -1;   // unpriced/returned offer deserializes to 0 - never a real gap
		}
		final boolean buying = t.isBuying();
		final int idx = buying ? lastPrintIdx(sd.al, sd.lv) : lastPrintIdx(sd.ah, sd.hv);
		if (idx < 0)
		{
			return -1;   // no observed trade on the side that would fill this offer
		}
		final long trade = buying ? sd.al[idx] : sd.ah[idx];
		if (trade <= 0)
		{
			return -1;
		}
		// Age the print against the wall clock (not the newest cached window) so a
		// globally stale cache cannot pass off an old print as live. If ts is
		// missing or mis-sized, freshness is unverifiable -> report unknown.
		if (sd.ts == null || idx >= sd.ts.length
			|| System.currentTimeMillis() / 1000L - sd.ts[idx] > STALE_SECS)
		{
			return -1;
		}
		// Seated (gap <= 0): the market already prints at/through your price.
		final long gap = buying ? (trade - yourPrice) : (yourPrice - trade);
		if (gap <= 0)
		{
			return 0;
		}
		return (int) Math.round(gap * 1000.0D / trade);
	}

	/** Newest index carrying a real trade: positive volume AND a real price. -1 if none. */
	private static int lastPrintIdx(long[] price, int[] vol)
	{
		if (price == null || vol == null)
		{
			return -1;
		}
		final int n = Math.min(price.length, vol.length);
		for (int i = n - 1; i >= 0; i--)
		{
			if (vol[i] > 0 && price[i] > 0)
			{
				return i;
			}
		}
		return -1;
	}

	private static String quietLabel(long quietMs)
	{
		final long m = quietMs / 60_000L;
		return m >= 60 ? (m / 60) + "h" + (m % 60 > 0 ? " " + (m % 60) + "m" : "") : m + "m";
	}
}

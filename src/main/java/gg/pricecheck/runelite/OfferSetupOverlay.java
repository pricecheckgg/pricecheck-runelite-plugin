package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The "type THIS" overlay. On the Grand Exchange Set-up-offer screen it reads the
 * item you're pricing and the live market, then shows the exact price to type in
 * the Price-per-item field. Renders as ONE compact chip straddling the top edge
 * of the field's ring, so the only thing it can cover is the static
 * "Price per item:" caption, never the item description or the presets. The
 * ring colour carries the state: gold = here's the price, red = what you typed
 * will not fill, amber = it fills but you're giving margin away.
 */
class OfferSetupOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int[] SETUP_PANELS = { 15, 26 };
	private static final int COINS_ITEM = 995;
	private static final Pattern PRICE = Pattern.compile("^[0-9,]+ coins$");

	private static final Stroke RING = new BasicStroke(2f);
	private static final Stroke HALO_STROKE = new BasicStroke(4f);
	private static final int PAD = 6;

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	OfferSetupOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config)
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
		Widget panel = null;
		for (int child : SETUP_PANELS)
		{
			final Widget w = client.getWidget(GE_GROUP, child);
			if (w != null && !w.isHidden())
			{
				panel = w;
				break;
			}
		}
		if (panel == null)
		{
			return null;
		}

		int geId = 0;
		boolean sell = false;
		Widget priceField = null;
		long entered = -1;
		for (final Widget c : allChildren(panel))
		{
			if (c == null)
			{
				continue;
			}
			final int item = c.getItemId();
			if (item > 0 && item != COINS_ITEM && geId == 0)
			{
				geId = item;
			}
			final String txt = c.getText() == null ? "" : c.getText().replaceAll("<[^>]+>", "").trim();
			if (txt.isEmpty())
			{
				continue;
			}
			if (txt.equals("Sell offer"))
			{
				sell = true;
			}
			if (priceField == null && PRICE.matcher(txt).matches())
			{
				priceField = c;
				entered = parseGp(txt);
			}
		}
		if (geId <= 0 || priceField == null)
		{
			return null;
		}

		plugin.noteSetupItem(geId);
		final FlipData live = plugin.liveFor(geId);
		final Rectangle b = priceField.getBounds();
		if (b == null || b.width < 20)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		if (live == null)
		{
			drawRing(g, b, Palette.GOLD);
			drawChip(g, b, Palette.GOLD, line(seg("PriceCheck…", small(g), Palette.SUBTLE_CANVAS)));
			return null;
		}

		final long target = sell ? live.getSell() : live.getBuy();
		if (target <= 0)
		{
			drawRing(g, b, Palette.GOLD);
			drawChip(g, b, Palette.GOLD, line(seg("no live price", small(g), Palette.SUBTLE_CANVAS)));
			return null;
		}

		// One line: the number is the payload. The suffix is either the margin
		// (all good) or the correction (typed price is off market), and the ring
		// colour repeats the state so it reads without the text.
		Color state = Palette.GOLD;
		Seg tail = seg("  +" + Fmt.compact(live.getProfit()), small(g), Palette.GREEN);
		final long tol = Math.max(target / 100, 1);
		if (entered > 0 && Math.abs(entered - target) > tol)
		{
			final long d = Math.abs(entered - target);
			if (sell)
			{
				if (entered > target)
				{
					state = Palette.RED;
					tail = seg("  drop " + Fmt.compact(d) + " to fill", small(g), Palette.RED);
				}
				else
				{
					state = Palette.AMBER;
					tail = seg("  under market by " + Fmt.compact(d), small(g), Palette.AMBER);
				}
			}
			else
			{
				if (entered < target)
				{
					state = Palette.RED;
					tail = seg("  raise " + Fmt.compact(d) + " to fill", small(g), Palette.RED);
				}
				else
				{
					state = Palette.AMBER;   // overpay fills, just worse
					tail = seg("  overpaying by " + Fmt.compact(d), small(g), Palette.AMBER);
				}
			}
		}

		drawRing(g, b, state);
		drawChip(g, b, state, line(
			seg("Type ", small(g), Palette.SUBTLE_CANVAS),
			seg(Fmt.full(target), bold(g), Palette.GOLD),
			tail));
		return null;
	}

	// Halo + state-coloured ring around the field you type into.
	private void drawRing(Graphics2D g, Rectangle b, Color col)
	{
		g.setStroke(HALO_STROKE);
		g.setColor(new Color(0, 0, 0, 120));
		g.drawRoundRect(b.x - 2, b.y - 2, b.width + 3, b.height + 3, 8, 8);
		g.setStroke(RING);
		g.setColor(col);
		g.drawRoundRect(b.x - 2, b.y - 2, b.width + 3, b.height + 3, 8, 8);
	}

	// ── chip layout: one line, straddling the ring's top edge ──
	private void drawChip(Graphics2D g, Rectangle field, Color border, Line ln)
	{
		final int w = ln.width() + PAD * 2;
		final int h = ln.height() + 4;

		// Centre on the field, clamp to the canvas; straddle the TOP edge of the
		// ring so the chip covers the caption row above the field, never the
		// item description or the preset buttons.
		int x = field.x + (field.width - w) / 2;
		final int cw = client.getCanvasWidth();
		if (cw > 0 && x + w > cw - 2)
		{
			x = cw - w - 2;
		}
		if (x < 2)
		{
			x = 2;
		}
		int y = field.y - h + 4;
		if (y < 2)
		{
			y = field.y + field.height - 4;   // no room above: straddle the bottom edge
		}

		g.translate(1, 1);
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(x, y, w, h, 8, 8);
		g.translate(-1, -1);
		g.setColor(Palette.INK);
		g.fillRoundRect(x, y, w, h, 8, 8);
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(border.getRed(), border.getGreen(), border.getBlue(), 200));
		g.drawRoundRect(x, y, w, h, 8, 8);

		int tx = x + PAD;
		final int ascent = ln.ascent();
		final int ty = y + (h - ln.height()) / 2;
		for (final Seg s : ln.segs)
		{
			g.setFont(s.font);
			final FontMetrics fm = g.getFontMetrics();
			g.setColor(new Color(0, 0, 0, 190));
			g.drawString(s.text, tx + 1, ty + ascent + 1);
			g.setColor(s.color);
			g.drawString(s.text, tx, ty + ascent);
			tx += fm.stringWidth(s.text);
		}
	}

	// ── tiny text model (per-segment font/colour) ──
	private static final class Seg
	{
		final String text; final Font font; final Color color;
		Seg(String t, Font f, Color c) { text = t; font = f; color = c; }
	}

	private final class Line
	{
		final Seg[] segs;
		Line(Seg[] s) { segs = s; }
		int width()
		{
			int w = 0;
			for (final Seg s : segs) { w += fm(s.font).stringWidth(s.text); }
			return w;
		}
		int ascent()
		{
			int a = 0;
			for (final Seg s : segs) { a = Math.max(a, fm(s.font).getAscent()); }
			return a;
		}
		int height()
		{
			int hgt = 0;
			for (final Seg s : segs) { hgt = Math.max(hgt, fm(s.font).getHeight()); }
			return hgt;
		}
	}

	private Graphics2D g2;
	private FontMetrics fm(Font f) { return g2.getFontMetrics(f); }
	private Seg seg(String t, Font f, Color c) { return new Seg(t, f, c); }
	private Line line(Seg... s) { return new Line(s); }
	private Font bold(Graphics2D g) { g2 = g; return FontManager.getRunescapeBoldFont(); }
	private Font small(Graphics2D g) { g2 = g; return FontManager.getRunescapeSmallFont(); }

	// ── widget helpers ──
	private static Widget[] allChildren(Widget panel)
	{
		final Widget[] stat = panel.getStaticChildren();
		final Widget[] dyn = panel.getDynamicChildren();
		final int sl = stat == null ? 0 : stat.length;
		final int dl = dyn == null ? 0 : dyn.length;
		final Widget[] all = new Widget[sl + dl];
		if (sl > 0) System.arraycopy(stat, 0, all, 0, sl);
		if (dl > 0) System.arraycopy(dyn, 0, all, sl, dl);
		return all;
	}

	private static long parseGp(String s)
	{
		final String digits = s.replaceAll("[^0-9]", "");
		if (digits.isEmpty())
		{
			return -1;
		}
		try { return Long.parseLong(digits); }
		catch (NumberFormatException e) { return -1; }
	}
}

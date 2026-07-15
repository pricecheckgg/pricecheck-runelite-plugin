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
 * item you're pricing and the live market, then paints the exact price to type in
 * the Price-per-item field: the current low for a buy, the current high for a sell.
 * Renders as an opaque floating card anchored to the field, flipping above it when
 * there's no room below.
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

		// Halo + gold ring around the field you type into (same recipe as the slots).
		g.setStroke(HALO_STROKE);
		g.setColor(new Color(0, 0, 0, 120));
		g.drawRoundRect(b.x - 2, b.y - 2, b.width + 3, b.height + 3, 8, 8);
		g.setStroke(RING);
		g.setColor(Palette.GOLD);
		g.drawRoundRect(b.x - 2, b.y - 2, b.width + 3, b.height + 3, 8, 8);

		if (live == null)
		{
			drawCard(g, b, new Line[]{ line(seg("PriceCheck: loading…", small(g), Palette.SUBTLE_CANVAS)) });
			return null;
		}

		final long target = sell ? live.getSell() : live.getBuy();
		if (target <= 0)
		{
			drawCard(g, b, new Line[]{ line(seg("PriceCheck: no live price", small(g), Palette.SUBTLE_CANVAS)) });
			return null;
		}

		// Main line: the number is the payload — bold + gold, "Type " quiet before it.
		final Line main = line(
			seg("Type ", reg(g), Palette.SUBTLE_CANVAS),
			seg(Fmt.full(target), bold(g), Palette.GOLD));
		// Sub line: what it is, then WHY (the margin) in green.
		final Line sub = line(
			seg((sell ? "market sell · " : "market buy · "), small(g), Palette.SUBTLE_CANVAS),
			seg("+" + Fmt.compact(live.getProfit()) + " margin", small(g), Palette.GREEN));

		// Warn line only when what's typed is meaningfully off market.
		Line warn = null;
		final long tol = Math.max(target / 100, 1);
		if (entered > 0 && Math.abs(entered - target) > tol)
		{
			final long d = Math.abs(entered - target);
			final String msg;
			final Color col;
			if (sell)
			{
				msg = entered > target ? "won't fill — drop " + Fmt.compact(d) : "under market by " + Fmt.compact(d);
				col = entered > target ? Palette.RED : Palette.AMBER;
			}
			else
			{
				msg = entered < target ? "won't fill — raise " + Fmt.compact(d) : "overpaying by " + Fmt.compact(d);
				col = entered < target ? Palette.RED : Palette.AMBER;   // overpay = amber (fills, just worse)
			}
			warn = line(seg(msg, small(g), col));
			warn.rule = true;
		}

		drawCard(g, b, warn == null ? new Line[]{ main, sub } : new Line[]{ main, sub, warn });
		return null;
	}

	// ── card layout ──
	private void drawCard(Graphics2D g, Rectangle field, Line[] lines)
	{
		int w = 0, h = PAD * 2;
		for (final Line ln : lines)
		{
			w = Math.max(w, ln.width());
			h += ln.height();
			if (ln.rule)
			{
				h += 4;
			}
		}
		w += PAD * 2;

		// Sit ABOVE the field by default so we never cover the quantity/price preset
		// buttons or the total below it; only cover the redundant "Price per item:"
		// label. Flip below if there isn't room above.
		final int tail = 7;
		int x = field.x;
		final int cw = client.getCanvasWidth();
		if (cw > 0 && x + w > cw)
		{
			x = Math.max(2, cw - w - 2);
		}
		boolean above = true;
		int y = field.y - h - tail - 2;
		if (y < 2)
		{
			above = false;
			y = field.y + field.height + tail + 2;
		}

		// Speech bubble: rounded card + a tail pointing at the field (one seamless
		// outline so no border line cuts across the tail).
		final int tailCx = Math.min(Math.max(field.x + field.width / 2, x + 12), x + w - 12);
		final java.awt.geom.Area shape = new java.awt.geom.Area(
			new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, 9, 9));
		final java.awt.Polygon tri = above
			? new java.awt.Polygon(new int[]{ tailCx - tail, tailCx + tail, tailCx }, new int[]{ y + h - 1, y + h - 1, y + h + tail }, 3)
			: new java.awt.Polygon(new int[]{ tailCx - tail, tailCx + tail, tailCx }, new int[]{ y + 1, y + 1, y - tail }, 3);
		shape.add(new java.awt.geom.Area(tri));

		g.translate(2, 2);
		g.setColor(new Color(0, 0, 0, 90));
		g.fill(shape);
		g.translate(-2, -2);
		g.setColor(Palette.INK);
		g.fill(shape);
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(Palette.GOLD.getRed(), Palette.GOLD.getGreen(), Palette.GOLD.getBlue(), 190));
		g.draw(shape);

		int ty = y + PAD;
		for (final Line ln : lines)
		{
			if (ln.rule)
			{
				g.setColor(new Color(255, 255, 255, 26));
				g.fillRect(x + PAD, ty + 1, w - PAD * 2, 1);
				ty += 4;
			}
			int tx = x + PAD;
			final int ascent = ln.ascent();
			for (final Seg s : ln.segs)
			{
				g.setFont(s.font);
				final FontMetrics fm = g.getFontMetrics();
				// shadow
				g.setColor(new Color(0, 0, 0, 190));
				g.drawString(s.text, tx + 1, ty + ascent + 1);
				g.setColor(s.color);
				g.drawString(s.text, tx, ty + ascent);
				tx += fm.stringWidth(s.text);
			}
			ty += ln.height();
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
		final Seg[] segs; boolean rule;
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
	private Font reg(Graphics2D g) { g2 = g; return FontManager.getRunescapeFont(); }
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

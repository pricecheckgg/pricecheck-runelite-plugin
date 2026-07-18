package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The price-state ring. On the Grand Exchange Set-up-offer screen it reads the
 * item you're pricing and the live market, then wraps the Price-per-item field
 * in a state-coloured ring: gold = on market, red = what you typed will not
 * fill, amber = it fills but you're giving margin away. Just the ring, no
 * text: the setup screen is dense and everything on it matters, so the exact
 * numbers stay in the evidence card and the click-to-fill price lines.
 */
class OfferSetupOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int[] SETUP_PANELS = { 15, 26 };
	private static final int COINS_ITEM = 995;
	private static final Pattern PRICE = Pattern.compile("^[0-9,]+ coins$");

	private static final Stroke RING = new BasicStroke(2f);
	private static final Stroke HALO_STROKE = new BasicStroke(4f);

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
		// Hold Shift to peek under the ring.
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
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

		final long target = live == null ? 0 : (sell ? live.getSell() : live.getBuy());
		if (target <= 0)
		{
			drawRing(g, b, Palette.GOLD);
			return null;
		}

		// Ring colour only: gold when the typed price is on market (within 1%),
		// red when it will not fill, amber when it fills but gives margin away.
		Color state = Palette.GOLD;
		final long tol = Math.max(target / 100, 1);
		if (entered > 0 && Math.abs(entered - target) > tol)
		{
			final boolean noFill = sell ? entered > target : entered < target;
			state = noFill ? Palette.RED : Palette.AMBER;
		}

		drawRing(g, b, state);
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

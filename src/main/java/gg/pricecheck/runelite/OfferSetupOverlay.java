package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Your trades, docked below the Grand Exchange window. The item's own buy/sell
 * history (from the flip log) is drawn in the empty floor space under the GE,
 * above the chatbox, so it never crowds the interface. Holding Shift reveals
 * page arrows when the history runs longer than the space. Also detects the
 * item being priced so the evidence card renders on the set-up screen.
 */
class OfferSetupOverlay extends Overlay
{
	private static final int GE_GROUP = 465;
	private static final int[] SETUP_PANELS = { 15, 26 };
	private static final int COINS_ITEM = 995;

	private static final Color INK = new Color(0x08, 0x07, 0x05, 238);
	private static final Color FRAME = new Color(0xe6, 0xc6, 0x67, 70);
	private static final Color RULE = new Color(0xe6, 0xc6, 0x67, 38);
	private static final Color NAME = new Color(0xde, 0xd8, 0xc8);
	private static final Color SHADOW = new Color(0, 0, 0, 180);
	private static final int PAD = 8;
	private static final int ROW_H = 14;

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	private int page;
	// {Rectangle, Integer delta} page arrows, canvas space, only while Shift is held.
	private volatile List<Object[]> navHits = java.util.Collections.emptyList();

	OfferSetupOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Shift-click a page arrow to turn the page. Returns true when it handled the click. */
	boolean handleClick(Point p, boolean shiftHeld)
	{
		if (p == null || !shiftHeld)
		{
			return false;
		}
		for (final Object[] b : navHits)
		{
			if (((Rectangle) b[0]).contains(p))
			{
				page += (int) b[1];
				return true;
			}
		}
		return false;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		navHits = java.util.Collections.emptyList();

		// Keep feeding the set-up item to the card even when the panel is off.
		final Widget setupPanel = setupPanel();
		if (setupPanel != null)
		{
			for (final Widget c : allChildren(setupPanel))
			{
				if (c == null)
				{
					continue;
				}
				final int item = c.getItemId();
				if (item > 0 && item != COINS_ITEM)
				{
					plugin.noteSetupItem(item);
					break;
				}
			}
		}

		// The classic "Your trades" log is retired: the terminal ORDER panel
		// covers the FULL tier and the card's own-trades tape covers the rest.
		// This overlay's remaining job is the setup-item feed above, which the
		// chart card and the GE prefills read.
		return null;
	}

	private static int rowWidth(FontMetrics fm, long[] t)
	{
		final boolean buy = t[3] == 1;
		String label = Fmt.full(t[1]);
		if (t[2] > 1)
		{
			label += " x" + Fmt.full(t[2]);
		}
		final String mid = buy ? (t[4] == 1 ? "holding" : "") : signed(t[5]);
		return 14 + fm.stringWidth(label) + 18 + fm.stringWidth(mid) + 18 + fm.stringWidth("00h ago");
	}

	private void paintRow(Graphics2D g, FontMetrics fm, int x, int top, int w, long[] t, long nowSec)
	{
		final boolean buy = t[3] == 1;
		final int ty = top + fm.getAscent();
		final Path2D tri = new Path2D.Float();
		final int tx = x + PAD;
		if (buy)
		{
			tri.moveTo(tx, ty - 1);
			tri.lineTo(tx + 7, ty - 1);
			tri.lineTo(tx + 3.5, ty - 7);
		}
		else
		{
			tri.moveTo(tx, ty - 7);
			tri.lineTo(tx + 7, ty - 7);
			tri.lineTo(tx + 3.5, ty - 1);
		}
		tri.closePath();
		g.setColor(SHADOW);
		g.translate(1, 1);
		g.fill(tri);
		g.translate(-1, -1);
		g.setColor(buy ? Palette.GREEN : Palette.GOLD);
		g.fill(tri);

		String label = Fmt.full(t[1]);
		if (t[2] > 1)
		{
			label += " x" + Fmt.full(t[2]);
		}
		shadowed(g, label, x + PAD + 12, ty, NAME);

		final String mid;
		final Color midCol;
		if (buy)
		{
			mid = t[4] == 1 ? "holding" : "";
			midCol = Palette.SUBTLE;
		}
		else
		{
			mid = signed(t[5]);
			midCol = t[5] >= 0 ? Palette.GREEN : Palette.RED;
		}
		if (!mid.isEmpty())
		{
			shadowed(g, mid, x + w / 2 + 6, ty, midCol);
		}

		final long ago = Math.max(0, nowSec - t[0] / 1000L);
		final String age = ago < 60 ? ago + "s ago"
			: ago < 5400 ? (ago / 60) + "m ago"
			: ago < 172800 ? (ago / 3600) + "h ago" : (ago / 86400) + "d ago";
		shadowed(g, age, x + w - PAD - fm.stringWidth(age), ty, Palette.SUBTLE);
	}

	private static String signed(long v)
	{
		return v == 0 ? "" : (v >= 0 ? "+" : "") + Fmt.compact(v);
	}

	private void arrow(Graphics2D g, Rectangle r, boolean right)
	{
		final int cx = r.x + r.width / 2;
		final int cy = r.y + r.height / 2;
		final Path2D p = new Path2D.Float();
		if (right)
		{
			p.moveTo(cx - 2, cy - 4);
			p.lineTo(cx + 3, cy);
			p.lineTo(cx - 2, cy + 4);
		}
		else
		{
			p.moveTo(cx + 2, cy - 4);
			p.lineTo(cx - 3, cy);
			p.lineTo(cx + 2, cy + 4);
		}
		p.closePath();
		g.setColor(SHADOW);
		g.translate(1, 1);
		g.fill(p);
		g.translate(-1, -1);
		g.setColor(Palette.GOLD);
		g.fill(p);
	}

	private Widget setupPanel()
	{
		for (final int child : SETUP_PANELS)
		{
			final Widget w = client.getWidget(GE_GROUP, child);
			if (w != null && !w.isHidden())
			{
				return w;
			}
		}
		return null;
	}

	private static void shadowed(Graphics2D g, String s, int x, int y, Color c)
	{
		g.setColor(SHADOW);
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
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
}

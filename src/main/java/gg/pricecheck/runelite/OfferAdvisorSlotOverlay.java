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
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints each active offer's advice directly onto its Grand Exchange slot: a
 * status-coloured frame plus a bar across the bottom with the exact instruction
 * ("RAISE +12.9k", "MARGIN DEAD", "ON TRACK"). Attention hierarchy: DEAD greys the
 * whole slot, action states (RAISE/DROP/FALLING) get a bright frame + a direction
 * triangle, ON_TRACK is deliberately quiet so a wall of slots pops only where you
 * need to act. Slot geometry: widgets 465:7 .. 465:14, live only while 465:0 is up.
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
			paintSlot(g, fm, b, a.getKind(), a.getColor(), label);
		}
		return null;
	}

	private void paintSlot(Graphics2D g, FontMetrics fm, Rectangle b, OfferAdvice.Kind kind, Color col, String label)
	{
		final boolean dead = kind == OfferAdvice.Kind.DEAD;
		final boolean onTrack = kind == OfferAdvice.Kind.ON_TRACK;
		final boolean collect = kind == OfferAdvice.Kind.COLLECT;
		final boolean quiet = onTrack || collect;

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

		// Status bar: rounded-bottom, square top to meet the frame flush.
		final int barX = b.x + 2;
		final int barY = b.y + b.height - BAR_H - 2;
		final int barW = b.width - 4;
		g.setColor(Palette.INK);
		g.fillRoundRect(barX, barY, barW, BAR_H, 6, 6);
		g.fillRect(barX, barY, barW, 6);
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
		while (s.length() > 1 && fm.stringWidth(s + "…") > maxW)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}
}

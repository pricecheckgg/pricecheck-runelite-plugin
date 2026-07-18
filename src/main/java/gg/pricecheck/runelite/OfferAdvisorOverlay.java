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
import net.runelite.api.KeyCode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The live advisor, floating fallback for when the GE grid isn't open. One
 * compact line per offer: status dot + item left, live instruction + margin
 * right. On-track rows drop the "OK" word — the green dot carries it — so the
 * box is quiet until something needs a hand. Hold Shift and a small [-]
 * appears in the header: click it to collapse the box to a title pill; Shift
 * again shows [+] to expand. The state persists across sessions. Draggable;
 * the per-slot overlay takes over while the grid is up.
 *
 * Painted by hand (no OverlayPanel components) so the box matches the plugin's
 * own look: ink surface, dim gold frame, hairline under the header, shadowed
 * RuneScape text. The painter is a static function over plain data so the
 * headless preview harness can render it without a Client.
 */
class OfferAdvisorOverlay extends Overlay
{
	private static final int WIDTH = 224;
	private static final int PAD = 9;
	private static final int ROW_H = 15;
	private static final int BTN = 14;
	private static final String COLLAPSED_KEY = "advisorCollapsed";

	private static final Color FRAME = new Color(0xe6, 0xc6, 0x67, 62);
	private static final Color RULE = new Color(0xe6, 0xc6, 0x67, 38);
	private static final Color NAME = new Color(0xde, 0xd8, 0xc8);
	private static final Color SHADOW = new Color(0, 0, 0, 180);

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;
	private final ConfigManager configManager;

	// Written on the mouse thread, read on the render thread, like toggleBounds.
	private volatile boolean collapsed;
	// Canvas-space hit box for the [-]/[+] button, valid only while Shift is
	// held (button visible). Volatile: written on the render thread, read on
	// the mouse thread.
	private volatile Rectangle toggleBounds;

	OfferAdvisorOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config, ConfigManager configManager)
	{
		// The base class must know its owning plugin: the synthetic
		// open-config event resolves the target plugin through the overlay.
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.collapsed = Boolean.parseBoolean(configManager.getConfiguration(PriceCheckConfig.GROUP, COLLAPSED_KEY));
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Mouse hook from the plugin: toggles collapse when the visible button is clicked. */
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
	public Dimension render(Graphics2D graphics)
	{
		toggleBounds = null;
		if (!config.showAdvisor())
		{
			return null;
		}
		if (plugin.isGrandExchangeOpen())
		{
			return null;
		}
		final List<OfferAdvice> advice = plugin.getAdvice();
		final List<OfferAdvice> rows = new ArrayList<>();
		for (OfferAdvice a : advice)
		{
			if (a.getKind() != OfferAdvice.Kind.NO_DATA)
			{
				rows.add(a);
			}
		}
		if (rows.isEmpty())
		{
			return null;
		}

		final Result r = paint(graphics, rows, collapsed, client.isKeyPressed(KeyCode.KC_SHIFT));
		if (r.button != null)
		{
			final Rectangle mine = getBounds();
			if (mine != null)
			{
				toggleBounds = new Rectangle(mine.x + r.button.x, mine.y + r.button.y, r.button.width, r.button.height);
			}
		}
		return r.size;
	}

	/** What one paint produced: the box size, and the toggle's local hit box (null unless drawn). */
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

	/** Draws the whole box at 0,0 and reports its size. Pure over its inputs. */
	static Result paint(Graphics2D g, List<OfferAdvice> rows, boolean collapsed, boolean shiftHeld)
	{
		final Font font = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// The pixel font must stay crisp: no text antialiasing.
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		final String title = "PriceCheck";
		final String count = " · " + rows.size();
		final int headerH = fm.getHeight() + 5;

		final int w;
		final int h;
		if (collapsed)
		{
			// A pill hugging its text: brand, count, room for the toggle on Shift.
			w = PAD + fm.stringWidth(title + count) + (shiftHeld ? BTN + 6 : 0) + PAD;
			h = headerH + 3;
		}
		else
		{
			w = WIDTH;
			h = headerH + 4 + rows.size() * ROW_H + PAD - 2;
		}

		g.setColor(Palette.INK);
		g.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
		g.setColor(FRAME);
		g.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

		final int baseline = 3 + fm.getAscent();
		shadowed(g, title, PAD, baseline, Palette.GOLD);
		shadowed(g, count, PAD + fm.stringWidth(title), baseline, Palette.SUBTLE_CANVAS);

		Rectangle btn = null;
		if (shiftHeld)
		{
			btn = new Rectangle(w - BTN - 4, 2, BTN, BTN);
			final int cx = btn.x + BTN / 2;
			final int cy = btn.y + BTN / 2;
			g.setColor(SHADOW);
			g.drawLine(cx - 3 + 1, cy + 1, cx + 3 + 1, cy + 1);
			g.setColor(Palette.GOLD);
			g.drawLine(cx - 3, cy, cx + 3, cy);                 // minus
			if (collapsed)
			{
				g.setColor(SHADOW);
				g.drawLine(cx + 1, cy - 3 + 1, cx + 1, cy + 3 + 1);
				g.setColor(Palette.GOLD);
				g.drawLine(cx, cy - 3, cx, cy + 3);             // the vertical stroke turns it into plus
			}
		}

		if (collapsed)
		{
			return new Result(new Dimension(w, h), btn);
		}

		g.setColor(RULE);
		g.drawLine(PAD - 2, headerH, w - PAD + 2, headerH);

		int y = headerH + 4;
		for (OfferAdvice a : rows)
		{
			final int rowBase = y + (ROW_H + fm.getAscent()) / 2 - 1;
			// Status dot: the quiet signal. Green = leave it alone.
			g.setColor(SHADOW);
			g.fillOval(PAD + 1, rowBase - 4, 5, 5);
			g.setColor(a.getColor());
			g.fillOval(PAD, rowBase - 5, 5, 5);

			final String right = rightText(a);
			final int rightW = fm.stringWidth(right);
			shadowed(g, right, w - PAD - rightW, rowBase, a.getColor());

			final int nameX = PAD + 11;
			final String name = ellipsize(a.getItemName(), fm, w - PAD - rightW - 6 - nameX);
			shadowed(g, name, nameX, rowBase, NAME);
			y += ROW_H;
		}
		return new Result(new Dimension(w, h), btn);
	}

	/** "OK +86.1k" carries no news on a green-dotted row; everything else keeps its word. */
	private static String rightText(OfferAdvice a)
	{
		final String s = a.getShortText();
		if (a.getKind() == OfferAdvice.Kind.ON_TRACK && s.startsWith("OK "))
		{
			return s.substring(3);
		}
		return s;
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
		while (t.length() > 1 && fm.stringWidth(t + "…") > max)
		{
			t = t.substring(0, t.length() - 1);
		}
		return t + "…";
	}
}

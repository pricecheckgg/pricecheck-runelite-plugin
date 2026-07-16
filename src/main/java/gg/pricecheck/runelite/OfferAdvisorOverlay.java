package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The live advisor, floating fallback for when the GE grid isn't open. One
 * compact line per offer: item left, live status + margin right ("OK +26.2k",
 * "RAISE +23.8k"). Hold Shift and a small [-] appears in the corner: click it
 * to collapse the box to its title bar; Shift again shows [+] to expand. The
 * state persists across sessions. Draggable; the per-slot overlay takes over
 * while the grid is up.
 */
class OfferAdvisorOverlay extends OverlayPanel
{
	private static final int MAX_NAME = 20;
	private static final int BTN = 12;
	private static final String COLLAPSED_KEY = "advisorCollapsed";

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;
	private final ConfigManager configManager;

	private boolean collapsed;
	// Canvas-space hit box for the [-]/[+] button, valid only while Shift is
	// held (button visible). Volatile: written on the render thread, read on
	// the mouse thread.
	private volatile Rectangle toggleBounds;

	OfferAdvisorOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config, ConfigManager configManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.collapsed = Boolean.parseBoolean(configManager.getConfiguration(PriceCheckConfig.GROUP, COLLAPSED_KEY));
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		panelComponent.setPreferredSize(new Dimension(216, 0));
		panelComponent.setBackgroundColor(Palette.INK);
		panelComponent.setGap(new Point(0, 2));
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
		if (advice.isEmpty())
		{
			return null;
		}
		int active = 0;
		for (OfferAdvice a : advice)
		{
			if (a.getKind() != OfferAdvice.Kind.NO_DATA)
			{
				active++;
			}
		}
		if (active == 0)
		{
			return null;
		}

		panelComponent.getChildren().clear();
		if (collapsed)
		{
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("PriceCheck · " + active)
				.color(Palette.GOLD)
				.build());
		}
		else
		{
			panelComponent.getChildren().add(TitleComponent.builder()
				.text("PriceCheck")
				.color(Palette.GOLD)
				.build());
			for (OfferAdvice a : advice)
			{
				if (a.getKind() == OfferAdvice.Kind.NO_DATA)
				{
					continue;
				}
				panelComponent.getChildren().add(LineComponent.builder()
					.left(shortName(a.getItemName()))
					.right(a.getShortText())
					.leftColor(Palette.SUBTLE_CANVAS)
					.rightColor(a.getColor())
					.build());
			}
		}

		final Dimension d = super.render(graphics);

		// The collapse control only exists while Shift is held, so it can never
		// eat a stray click and never clutters the box.
		if (d != null && client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			final int bx = d.width - BTN - 3;
			final int by = 3;
			graphics.setColor(new Color(0, 0, 0, 170));
			graphics.fillRoundRect(bx, by, BTN, BTN, 4, 4);
			graphics.setColor(Palette.GOLD);
			graphics.drawRoundRect(bx, by, BTN, BTN, 4, 4);
			final int cx = bx + BTN / 2;
			final int cy = by + BTN / 2;
			graphics.drawLine(cx - 3, cy, cx + 3, cy);          // minus
			if (collapsed)
			{
				graphics.drawLine(cx, cy - 3, cx, cy + 3);      // the vertical stroke turns it into plus
			}
			final Rectangle mine = getBounds();
			if (mine != null)
			{
				toggleBounds = new Rectangle(mine.x + bx, mine.y + by, BTN, BTN);
			}
		}
		return d;
	}

	private static String shortName(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME - 1) + "…";
	}
}

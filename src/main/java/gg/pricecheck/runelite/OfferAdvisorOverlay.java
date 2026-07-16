package gg.pricecheck.runelite;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The live advisor, floating fallback for when the GE grid isn't open. One
 * compact line per offer: item left, live status + margin right ("OK +26.2k",
 * "RAISE +23.8k"), so the margins stay readable at a glance. Hold Shift to
 * hide it. Draggable; the per-slot overlay takes over while the grid is up.
 */
class OfferAdvisorOverlay extends OverlayPanel
{
	private static final int MAX_NAME = 20;

	private final Client client;
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	OfferAdvisorOverlay(Client client, PriceCheckPlugin plugin, PriceCheckConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		panelComponent.setPreferredSize(new Dimension(216, 0));
		panelComponent.setBackgroundColor(Palette.INK);
		panelComponent.setGap(new Point(0, 2));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showAdvisor())
		{
			return null;
		}
		// Hold Shift to peek at the game under any PriceCheck overlay.
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
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

		int shown = 0;
		panelComponent.getChildren().clear();
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
			shown++;
			panelComponent.getChildren().add(LineComponent.builder()
				.left(shortName(a.getItemName()))
				.right(a.getShortText())
				.leftColor(Palette.SUBTLE_CANVAS)
				.rightColor(a.getColor())
				.build());
		}
		if (shown == 0)
		{
			return null;
		}
		return super.render(graphics);
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

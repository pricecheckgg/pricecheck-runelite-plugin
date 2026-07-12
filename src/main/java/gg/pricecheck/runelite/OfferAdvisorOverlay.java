package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The live advisor. For each of your active GE offers it draws one status line
 * plus the exact instruction (raise/drop by X, or the margin died) computed by
 * {@link OfferAdvisor}. Draggable; hides itself when you have no offers.
 */
class OfferAdvisorOverlay extends OverlayPanel
{
	private static final Color GOLD = new Color(0xe6, 0xc6, 0x67);

	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	OfferAdvisorOverlay(PriceCheckPlugin plugin, PriceCheckConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(252, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showAdvisor())
		{
			return null;
		}
		final List<OfferAdvice> advice = plugin.getAdvice();
		if (advice.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("PriceCheck offers")
			.color(GOLD)
			.build());

		for (OfferAdvice a : advice)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(a.getItemName())
				.right(a.getSide())
				.leftColor(Color.WHITE)
				.rightColor(a.getColor())
				.build());
			panelComponent.getChildren().add(LineComponent.builder()
				.left(a.getMessage())
				.leftColor(a.getColor())
				.build());
		}
		return super.render(graphics);
	}
}

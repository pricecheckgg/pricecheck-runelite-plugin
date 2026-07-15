package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The live advisor, floating fallback for when the GE grid isn't open. For each
 * active offer it draws the item + the exact instruction from {@link OfferAdvisor}.
 * Draggable; hides itself when you have no offers or the grid is up (the per-slot
 * overlay takes over there).
 */
class OfferAdvisorOverlay extends OverlayPanel
{
	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	OfferAdvisorOverlay(PriceCheckPlugin plugin, PriceCheckConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		panelComponent.setPreferredSize(new Dimension(252, 0));
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
		if (plugin.isGrandExchangeOpen())
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
			.text("PriceCheck")
			.color(Palette.GOLD)
			.build());

		boolean first = true;
		for (OfferAdvice a : advice)
		{
			if (!first)
			{
				panelComponent.getChildren().add(LineComponent.builder().left("").build());  // group separator
			}
			first = false;
			panelComponent.getChildren().add(LineComponent.builder()
				.left(a.getItemName())
				.right(a.getSide())
				.leftColor(Color.WHITE)
				.rightColor(Palette.SUBTLE_CANVAS)   // side is identity, not state
				.build());
			panelComponent.getChildren().add(LineComponent.builder()
				.left(a.getMessage())
				.leftColor(a.getColor())
				.build());
		}
		return super.render(graphics);
	}
}

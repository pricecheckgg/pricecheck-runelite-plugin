package gg.pricecheck.runelite;

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
 * The live advisor, floating fallback for when the GE grid isn't open. Only
 * offers that NEED something get a line (one line each: item left, instruction
 * right); quiet on-track offers collapse into a single dim summary, and when
 * nothing needs attention the whole box stays hidden. Draggable; the per-slot
 * overlay takes over while the grid is up.
 */
class OfferAdvisorOverlay extends OverlayPanel
{
	private static final int MAX_NAME = 20;

	private final PriceCheckPlugin plugin;
	private final PriceCheckConfig config;

	OfferAdvisorOverlay(PriceCheckPlugin plugin, PriceCheckConfig config)
	{
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
		if (plugin.isGrandExchangeOpen())
		{
			return null;
		}
		final List<OfferAdvice> advice = plugin.getAdvice();
		if (advice.isEmpty())
		{
			return null;
		}

		int quiet = 0;
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
			if (a.getKind() == OfferAdvice.Kind.ON_TRACK)
			{
				quiet++;
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

		// All quiet: no box at all. The advisor earns screen space only when
		// an offer actually needs a decision.
		if (shown == 0)
		{
			return null;
		}
		if (quiet > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(quiet + " on track")
				.leftColor(Palette.SUBTLE_CANVAS)
				.build());
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

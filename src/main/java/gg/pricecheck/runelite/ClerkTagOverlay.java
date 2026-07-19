package gg.pricecheck.runelite;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The other half of the clerk cosmetic: a small pricecheck.gg wordmark
 * floating over each clerk's head. Models cannot carry custom textures, so
 * the "logo" lives in the overlay layer where text is ours to draw. Rides
 * the same opt-in toggle as the recolour.
 */
class ClerkTagOverlay extends Overlay
{
	private final Client client;
	private final PriceCheckConfig config;

	ClerkTagOverlay(Client client, PriceCheckConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.geClerkStyle())
		{
			return null;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		for (final NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !GeClerkCosmetic.isClerk(npc))
			{
				continue;
			}
			final String tag = "pricecheck.gg";
			final Point p = npc.getCanvasTextLocation(g, tag, npc.getLogicalHeight() + 28);
			if (p == null)
			{
				continue;
			}
			g.setColor(new java.awt.Color(0, 0, 0, 190));
			g.drawString(tag, p.getX() + 1, p.getY() + 1);
			g.setColor(Palette.GOLD);
			g.drawString(tag, p.getX(), p.getY());
		}
		return null;
	}
}

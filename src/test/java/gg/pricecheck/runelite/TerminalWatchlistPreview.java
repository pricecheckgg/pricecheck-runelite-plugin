package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev-only: renders TerminalWatchlistOverlay.paintWatchlist (the top-centre box:
 *  your watch targets + top EV picks). Args: [outputPath]. */
public final class TerminalWatchlistPreview
{
	private TerminalWatchlistPreview() { }

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "watchlist.png";

		final List<TerminalWatchlistOverlay.Watch> watch = new ArrayList<>();
		watch.add(new TerminalWatchlistOverlay.Watch("Twisted bow", 1_200_000_000L, 2));
		watch.add(new TerminalWatchlistOverlay.Watch("Masori body", 53_100_000L, 1));
		watch.add(new TerminalWatchlistOverlay.Watch("Elysian spirit shield", 515_000_000L, 0));

		final List<TerminalWatchlistOverlay.Pick> picks = new ArrayList<>();
		picks.add(new TerminalWatchlistOverlay.Pick("Dragon claws", 167_500_000L, 1_900_000L));
		picks.add(new TerminalWatchlistOverlay.Pick("Zaryte crossbow", 37_400_000L, 910_000L));
		picks.add(new TerminalWatchlistOverlay.Pick("Voidwaker", 88_400_000L, 870_000L));
		picks.add(new TerminalWatchlistOverlay.Pick("Avernic defender", 61_900_000L, 980_000L));
		picks.add(new TerminalWatchlistOverlay.Pick("Elder maul", 47_700_000L, 540_000L));

		final int w = 560, hBox = 250, pad = 16, sc = 2;
		final BufferedImage img = new BufferedImage((w + pad * 2) * sc, (hBox + pad * 2) * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, w + pad * 2, hBox + pad * 2);
		g.translate(pad, pad);
		TerminalWatchlistOverlay.paintWatchlist(g, w, hBox, watch, picks);
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

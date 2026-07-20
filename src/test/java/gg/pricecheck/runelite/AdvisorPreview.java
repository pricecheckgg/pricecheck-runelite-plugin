package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Dev-only: renders the floating advisor overlay headlessly to a PNG -
 * expanded, expanded+Shift, and collapsed+Shift, side by side over a flat
 * canvas-coloured ground. Args: [outputPath]. Never shipped (test source set).
 */
public final class AdvisorPreview
{
	private static final Color GREEN = new Color(0x5d, 0xf2, 0x9a);
	private static final Color AMBER = new Color(0xe6, 0xc6, 0x67);
	private static final Color RED = new Color(0xf2, 0x6b, 0x6d);
	private static final Color GREY = new Color(0x9a, 0x91, 0x7c);
	private static final Color BLUE = new Color(0x7f, 0xb0, 0xff);

	private AdvisorPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/advisor.png";

		final List<OfferAdvice> rows = Arrays.asList(
			new OfferAdvice(0, "Uncharged toxic trident", "BUY", OfferAdvice.Kind.ON_TRACK, "", "OK +86.1k", GREEN),
			new OfferAdvice(1, "Ranger boots", "SELL", OfferAdvice.Kind.ON_TRACK, "", "OK +385.24k", GREEN),
			new OfferAdvice(2, "Tonalztics of ralos (uncharged)", "BUY", OfferAdvice.Kind.RAISE_BUY, "", "RAISE +23.8k", AMBER),
			new OfferAdvice(3, "Zulrah's scales", "BUY", OfferAdvice.Kind.FALLING, "", "FALLING 1.8%", AMBER),
			new OfferAdvice(4, "Scythe of vitur (uncharged)", "BUY", OfferAdvice.Kind.HOLD, "", "HOLD", BLUE),
			new OfferAdvice(5, "Harmonised orb", "SELL", OfferAdvice.Kind.HOLD, "", "HOLD", BLUE),
			new OfferAdvice(6, "Dragon claws", "BUY", OfferAdvice.Kind.DEAD, "", "MARGIN DEAD", RED),
			new OfferAdvice(7, "Armadyl chainskirt", "", OfferAdvice.Kind.COLLECT, "", "COLLECT", GREY)
		);

		final int w = 780;
		final int h = 250;
		final BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(2, 2);
		// Flat stand-in for the game world so contrast reads honestly.
		g.setColor(new Color(0x4a, 0x42, 0x36));
		g.fillRect(0, 0, w, h);
		g.setColor(new Color(0x55, 0x4c, 0x3e));
		for (int i = 0; i < 40; i++)
		{
			g.fillRect((i * 97) % w, (i * 53) % h, 18, 3);
		}

		g.translate(16, 16);
		OfferAdvisorOverlay.paint(g, rows, false, false);
		g.translate(260, 0);
		OfferAdvisorOverlay.paint(g, rows, false, true);
		g.translate(260, 0);
		OfferAdvisorOverlay.paint(g, rows, true, true);
		g.dispose();

		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

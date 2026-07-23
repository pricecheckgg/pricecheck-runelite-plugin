package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Dev-only: renders GeItemInfoPainter.paintTerminal (the amber DES card) from a
 * realistic Context (Tumeken's shadow sell scene), so the terminal card can be
 * checked against the approved mock without the game. Args: [outputPath].
 */
public final class TerminalCardPreview
{
	private TerminalCardPreview() { }

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/termcard.png";

		final int n = 72;
		final ItemChart.Series s = new ItemChart.Series();
		s.ts = new long[n];
		s.high = new long[n];
		s.low = new long[n];
		s.hvol = new int[n];
		s.lvol = new int[n];
		final long t0 = 1784500200L;
		for (int i = 0; i < n; i++)
		{
			s.ts[i] = t0 + i * 300L;
			final double wave = Math.sin(i / 9.0) * 6500;
			final long mid = 1_020_000 + i * 620 + (long) wave;
			final long spread = 22_000 + (long) Math.abs(wave);
			s.high[i] = mid + spread / 2;
			s.low[i] = mid - spread / 2;
			s.hvol[i] = 1 + (i * 7) % 4;
			s.lvol[i] = 1 + (i * 5) % 3 + (i > 40 ? 3 : 0);
		}
		// Quiet windows (0 high/low) like the real feed, to prove the chart gap fix.
		for (final int gap : new int[]{11, 12, 34, 55, 56})
		{
			s.high[gap] = 0;
			s.low[gap] = 0;
		}

		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = "Tumeken's shadow (uncharged)";
		c.youSells = new long[]{1_050_000};
		c.youBuys = new long[]{1_022_500};
		c.stateText = "B WAIT";
		c.stateColor = TerminalKit.AMBER;
		c.stateText2 = "S OK +6.5k";
		c.stateColor2 = TerminalKit.GREEN;
		c.series = s;
		c.chartLabel = "24h";
		c.nowTs = t0 + n * 300L;
		c.rangeRow = new long[]{1_072_000, c.nowTs - 3600, 1_012_000, c.nowTs - 7200};
		c.fillPct = 71;
		final List<GeItemInfoPainter.Print> prints = new ArrayList<>();
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 5400, 1_041_000, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 3600, 1_043_500, false, true, true));  // your buy
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 3300, 1_047_200, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 2500, 1_044_100, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 1600, 1_050_600, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 900, 1_045_400, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 430, 1_048_200, true, true, false));   // your sell
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 40, 1_049_900, true));
		c.prints = prints;
		c.tradeDepth = 10;
		c.lotQty = 4;
		c.lotCost = 4 * 1_021_000L;
		c.lotEntries = new long[][]{{4, 1_021_000, (t0 + 20 * 300L) * 1000L}};
		c.outcomeText = "If it sells: +26.25k after tax";
		c.outcomeColor = TerminalKit.GREEN;

		final int w = GeItemInfoPainter.TERM_W, pad = 16, sc = 2;
		final int hGuess = 700;
		final BufferedImage img = new BufferedImage((w + pad * 2) * sc, (hGuess + pad * 2) * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, w + pad * 2, hGuess + pad * 2);
		g.translate(pad, pad);
		final java.awt.Dimension d = GeItemInfoPainter.paintTerminal(g, c, w);
		g.dispose();
		// crop to the actual card height
		final BufferedImage crop = img.getSubimage(0, 0, (w + pad * 2) * sc, Math.min((d.height + pad * 2) * sc, img.getHeight()));
		ImageIO.write(crop, "png", new File(out));
		System.out.println("wrote " + out + " (card " + d.width + "x" + d.height + ")");
	}
}

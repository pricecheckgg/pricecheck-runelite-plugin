package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev-only: renders a grid of TerminalMiniGraph cards (the overview "one graph per
 *  GE item" layout) from synthetic series. Args: [outputPath]. */
public final class TerminalMiniPreview
{
	private TerminalMiniPreview() { }

	private static GeItemInfoPainter.Context ctx(String name, long base, boolean sell, long offer, int seed)
	{
		final int n = 60;
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
			final double wave = Math.sin((i + seed) / 8.0) * (base / 120.0);
			final long mid = base + (long) (i * base / 9000.0) + (long) wave;
			final long spread = base / 60 + (long) Math.abs(wave);
			s.high[i] = mid + spread / 2;
			s.low[i] = mid - spread / 2;
			s.hvol[i] = 1 + (i * 7) % 4;
			s.lvol[i] = 1 + (i * 5) % 3;
		}
		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = name;
		c.series = s;
		c.nowTs = t0 + n * 300L;
		c.chartLabel = "24h";
		c.tradeDepth = 10;
		if (sell) { c.youSells = new long[]{offer}; }
		else { c.youBuys = new long[]{offer}; }
		final List<GeItemInfoPainter.Print> prints = new ArrayList<>();
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 900, base + base / 200, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 300, base + base / 150, false));
		c.prints = prints;
		return c;
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "mini.png";

		final GeItemInfoPainter.Context[] cards = {
			ctx("Elysian spirit shield", 515_000_000L, true, 522_000_000L, 1),
			ctx("Scythe of vitur (uncharged)", 1_280_000_000L, false, 1_284_000_000L, 5),
			ctx("Twisted bow", 1_200_000_000L, true, 1_210_000_000L, 9),
			ctx("Tumeken's shadow", 805_000_000L, false, 808_000_000L, 13),
			ctx("Masori body", 53_000_000L, true, 54_000_000L, 3),
			ctx("Dragon claws", 167_000_000L, false, 168_000_000L, 7),
		};

		final int miniW = 190, miniH = 92, gap = 6, cols = 2, pad = 16, sc = 2;
		final int rows = (cards.length + cols - 1) / cols;
		final int totalW = cols * miniW + (cols - 1) * gap + pad * 2;
		final int totalH = rows * miniH + (rows - 1) * gap + pad * 2;
		final BufferedImage img = new BufferedImage(totalW * sc, totalH * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, totalW, totalH);
		for (int i = 0; i < cards.length; i++)
		{
			final int cx = pad + (i % cols) * (miniW + gap);
			final int cy = pad + (i / cols) * (miniH + gap);
			g.translate(cx, cy);
			GeItemInfoPainter.paintMiniGraph(g, cards[i], miniW, miniH);
			g.translate(-cx, -cy);
		}
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

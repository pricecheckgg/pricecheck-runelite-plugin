package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Dev-only: renders the GE-anchored item card headlessly over a GE-interface
 * coloured backdrop, using the Heavy ballista sell-offer scenario from the
 * owner's screenshot. Args: [outputPath]. Never shipped (test source set).
 */
public final class GeItemInfoPreview
{
	private GeItemInfoPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/geinfo.png";

		// Six hours of 5m windows around ~1.05m with a soft rise.
		final int n = 72;
		final ItemChart.Series s = new ItemChart.Series();
		s.ts = new long[n];
		s.high = new long[n];
		s.low = new long[n];
		s.hvol = new int[n];
		s.lvol = new int[n];
		s.manip = new boolean[n];
		final long t0 = 1784500200L;
		for (int i = 0; i < n; i++)
		{
			s.ts[i] = t0 + i * 300L;
			final double wave = Math.sin(i / 9.0) * 6_500;
			final long mid = 1_012_000 + i * 560 + (long) wave;
			final long spread = 26_000 + (long) Math.abs(wave);
			if (i % 11 == 3)
			{
				continue;   // quiet window, honest gap
			}
			s.high[i] = mid + spread / 2;
			s.low[i] = mid - spread / 2;
			s.hvol[i] = 1 + (i * 7) % 4;
			s.lvol[i] = 1 + (i * 5) % 3;
		}

		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = "Heavy ballista";
		c.yourPrice = 1_050_000;
		c.stateText = "OK +26.2k";
		c.stateColor = Palette.GREEN;
		c.series = s;
		c.nowTs = t0 + n * 300L;
		final List<GeItemInfoPainter.Print> prints = new ArrayList<>();
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 660, 1_042_800, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 430, 1_048_200, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 150, 1_046_500, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 40, 1_049_900, true));
		c.prints = prints;
		c.fillPct = 71;
		c.outcomeText = "If it sells: +26.25k after tax";
		c.outcomeColor = Palette.GREEN;

		final int w = 300;
		final int h = 320;
		final BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(2, 2);
		// GE-interface parchment brown, so contrast reads like the real canvas.
		g.setColor(new Color(0x49, 0x42, 0x36));
		g.fillRect(0, 0, w, h);
		g.setColor(new Color(0x55, 0x4c, 0x3e));
		for (int i = 0; i < 30; i++)
		{
			g.fillRect((i * 97) % w, (i * 53) % h, 18, 3);
		}
		g.translate(8, 8);
		GeItemInfoPainter.paint(g, c);
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

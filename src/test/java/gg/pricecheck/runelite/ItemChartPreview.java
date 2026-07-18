package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Dev-only: renders the per-item evidence chart headlessly to a PNG with a
 * realistic synthetic day (drift, a dip and recovery, a manipulated spike,
 * thin overnight hours). Args: [outputPath]. Never shipped (test source set).
 */
public final class ItemChartPreview
{
	private ItemChartPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/itemchart.png";

		final int n = 288;
		final ItemChart.Series s = new ItemChart.Series();
		s.ts = new long[n];
		s.high = new long[n];
		s.low = new long[n];
		s.hvol = new int[n];
		s.lvol = new int[n];
		s.manip = new boolean[n];
		final long t0 = 1784480400L;   // 09:00 UTC, deterministic
		long mid = 41_200_000L;
		for (int i = 0; i < n; i++)
		{
			s.ts[i] = t0 + i * 300L;
			final int hour = (int) ((s.ts[i] / 3600) % 24);
			// Shape: slow drift up, a sharp dip around window 120 with recovery,
			// wide spread while it settles, thin overnight (02:00-07:00).
			final double wave = Math.sin(i / 34.0) * 140_000;
			final double dip = i > 110 && i < 190 ? -(1 - Math.abs(i - 150) / 40.0) * 1_100_000 : 0;
			mid = 41_200_000L + (long) (i * 3_400 + wave + dip);
			// Spread rides from under the tax floor (no gold wash) to well past
			// it during the dip recovery, so both corridor states render.
			final long spread = (long) (420_000 + (i > 110 && i < 210 ? 1_150_000 : 0) + Math.abs(wave) * 0.4);
			final boolean thin = hour >= 2 && hour < 7;
			if (thin && i % 9 == 0)
			{
				continue;
			}
			s.high[i] = mid + spread / 2;
			s.low[i] = mid - spread / 2;
			s.hvol[i] = thin ? (i % 4 == 0 ? 1 : 0) : 2 + (i * 7) % 6;
			s.lvol[i] = thin ? 1 : 2 + (i * 5) % 5;
			if (i == 226 || i == 227)
			{
				s.manip[i] = true;
				s.low[i] -= 380_000;
				s.lvol[i] = 60;
			}
		}
		s.quoteBuy = 40_720_000L;
		s.quoteSell = 41_890_000L;
		s.fillPct = 78;
		s.fcFrom = 19;
		s.fcTo = 21;
		s.fcNote = "14/18";

		final int w = 226;
		final int h = 150;
		final ItemChart chart = new ItemChart(s, w, h);
		chart.setSize(w, h);
		final BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(2, 2);
		g.setColor(new Color(43, 43, 43));
		g.fillRect(0, 0, w, h);
		chart.paint(g);
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

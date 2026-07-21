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
			s.lvol[i] = 1 + (i * 5) % 3 + (i > 40 ? 3 : 0);
		}

		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = "Tumeken's shadow (uncharged)";
		c.youSells = new long[]{1_050_000};
		c.stateText = "B MARGIN DEAD";
		c.stateColor = Palette.RED;
		c.stateText2 = "S OK -396.37k";
		c.stateColor2 = Palette.GREEN;
		c.series = s;
		c.nowTs = t0 + n * 300L;
		final List<GeItemInfoPainter.Print> prints = new ArrayList<>();
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 9200, 1_041_000, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 7100, 1_043_500, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 5400, 1_039_800, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 3300, 1_047_200, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 2500, 1_044_100, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 1600, 1_050_600, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 900, 1_045_400, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 430, 1_048_200, true, true, false));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 150, 1_046_500, false, true, true));
		prints.add(new GeItemInfoPainter.Print(c.nowTs - 40, 1_049_900, true));
		c.prints = prints;
		c.fillPct = 71;
		c.outcomeText = "If it sells: +26.25k after tax";
		c.outcomeColor = Palette.GREEN;
		// Held across three separate buys at different prices.
		c.lotQty = 4;
		c.lotCost = 1_016_000 + 2 * 1_022_500 + 1_037_900;
		c.lotOpenedAtMs = (t0 + 20 * 300L) * 1000L;
		c.lotEntries = new long[][]{
			{1, 1_016_000, (t0 + 20 * 300L) * 1000L},
			{2, 1_022_500, (t0 + 38 * 300L) * 1000L},
			{1, 1_037_900, (t0 + 61 * 300L) * 1000L},
		};


		final int w = 380;
		final int h = 560;
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

		// Second scene: the grid stack, one compact card per distinct item.
		// Antifire rides both sides on one chart; the second card is a single
		// sell with no chart yet (loading state).
		final GeItemInfoPainter.Context g1 = new GeItemInfoPainter.Context();
		g1.itemName = "Extended super antifire(4)";
		g1.youBuys = new long[]{18_140};
		g1.youSells = new long[]{18_760};
		g1.stateText = "OK +394";
		g1.stateColor = Palette.GREEN;
		g1.stateText2 = "OK +317";
		g1.stateColor2 = Palette.GREEN;
		final ItemChart.Series cs = new ItemChart.Series();
		final int cn = 288;
		cs.ts = new long[cn];
		cs.high = new long[cn];
		cs.low = new long[cn];
		cs.hvol = new int[cn];
		cs.lvol = new int[cn];
		for (int i = 0; i < cn; i++)
		{
			cs.ts[i] = t0 + i * 300L;
			final double wobble = Math.sin(i / 13.0) * 160 + Math.sin(i / 47.0) * 260;
			final long mid = 18_400 + (long) wobble;
			final long spread = 420 + (i % 7) * 40;
			if (i % 17 == 5)
			{
				continue;
			}
			cs.high[i] = mid + spread / 2;
			cs.low[i] = mid - spread / 2;
			cs.hvol[i] = 40 + (i * 13) % 90;
			cs.lvol[i] = 35 + (i * 7) % 80;
		}
		g1.series = cs;
		// An open position built from two buys at different prices.
		g1.lotQty = 200;
		g1.lotCost = 120 * 18_050L + 80 * 18_420L;
		g1.lotOpenedAtMs = (t0 + 96 * 300L) * 1000L;
		g1.lotEntries = new long[][]{
			{120, 18_050, (t0 + 96 * 300L) * 1000L},
			{80, 18_420, (t0 + 210 * 300L) * 1000L},
		};
		final GeItemInfoPainter.Context g2c = new GeItemInfoPainter.Context();
		g2c.itemName = "Rune platebody";
		g2c.youSells = new long[]{38_900};
		g2c.stateText = "OK +512";
		g2c.stateColor = Palette.GREEN;

		final BufferedImage img2 = new BufferedImage(320 * 2, 300 * 2, BufferedImage.TYPE_INT_RGB);
		final Graphics2D gg = img2.createGraphics();
		gg.scale(2, 2);
		gg.setColor(new Color(0x49, 0x42, 0x36));
		gg.fillRect(0, 0, 320, 300);
		gg.translate(12, 10);
		final java.awt.Dimension d1 = GeItemInfoPainter.paintCompact(gg, g1);
		gg.translate(0, d1.height + 6);
		GeItemInfoPainter.paintCompact(gg, g2c);
		gg.dispose();
		final String out2 = out.replace(".png", "-stack.png");
		ImageIO.write(img2, "png", new File(out2));
		System.out.println("wrote " + out2);
	}
}

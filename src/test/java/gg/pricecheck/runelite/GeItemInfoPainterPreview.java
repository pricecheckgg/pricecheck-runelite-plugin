package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev preview only: renders the evidence card's last-N-trades tick chart to a
 *  PNG so the new view can be judged headless. Not a test. */
public final class GeItemInfoPainterPreview
{
	private GeItemInfoPainterPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		final GeItemInfoPainter.Context c = new GeItemInfoPainter.Context();
		c.itemName = "Twisted bow";
		c.nowTs = 1_000_000L;
		c.stateText = "S OK +1.8m";
		c.stateColor = Palette.GREEN;
		c.youSells = new long[]{1_490_000_000L};
		c.youBuys = new long[]{1_483_000_000L};

		// 30 synthetic prints wandering the 1.48b-1.49b corridor, alternating side.
		final List<GeItemInfoPainter.Print> prints = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			final boolean buySide = i % 2 == 0;
			final long jitter = ((i * 137) % 7 - 3) * 300_000L;
			final long px = 1_486_000_000L + (buySide ? 1_600_000L : -2_100_000L) + jitter;
			prints.add(new GeItemInfoPainter.Print(c.nowTs - (30 - i) * 200L, px, buySide, i == 27, false));
		}
		c.prints = prints;
		c.tradeDepth = 30;
		c.lotQty = 1;
		c.lotCost = 1_483_000_000L;
		c.lotOpenedAtMs = (c.nowTs - 8 * 3600L) * 1000L;
		c.outcomeText = "Nets 1.48b/ea after tax (tax 5,000,000/ea)";
		c.outcomeColor = Palette.LIGHT;

		// Selected-view high/low with timestamps for the range read.
		c.rangeRow = new long[]{1_490_000_000L, c.nowTs - 720L, 1_483_000_000L, c.nowTs - 2880L};

		c.tradesChartN = 30;
		c.chartLabel = "30";
		render(c, args.length > 0 ? args[0] : "card-trades.png");

		c.tradesChartN = 10;
		c.chartLabel = "10";
		render(c, (args.length > 0 ? args[0] : "card-trades.png").replace(".png", "-10.png"));
	}

	private static void render(GeItemInfoPainter.Context c, String path) throws Exception
	{
		final int scale = 2;
		final int wLogical = GeItemInfoPainter.W_FULL + 16;
		final int hLogical = 640;
		final BufferedImage img = new BufferedImage(wLogical * scale, hLogical * scale, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setColor(new Color(0x22, 0x1d, 0x16));
		g.fillRect(0, 0, wLogical, hLogical);
		g.translate(8, 8);
		final java.awt.Dimension d = GeItemInfoPainter.paint(g, c, GeItemInfoPainter.W_FULL);
		g.dispose();
		ImageIO.write(img, "png", new File(path));
		System.out.println("wrote " + path + " card " + d.width + "x" + d.height);
	}
}

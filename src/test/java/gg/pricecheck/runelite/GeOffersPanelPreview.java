package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev preview only: renders the active-offers board to a PNG so the layout can
 *  be judged headless. Not a test. */
public final class GeOffersPanelPreview
{
	private static final Color BUY = new Color(0x49, 0xc9, 0x7f);
	private static final Color SELL = new Color(0xd9, 0x5c, 0x5e);
	private static final Color HOLD = new Color(0x7f, 0xb0, 0xff);

	private GeOffersPanelPreview()
	{
	}

	private static GeOffersPanelOverlay.Row row(String chip, Color chipCol, String name, String verdict, Color vCol,
		long price, long margin, long total, long sold, long closeGp, int trend, boolean seated, String last, String lean)
	{
		final GeOffersPanelOverlay.Row r = new GeOffersPanelOverlay.Row();
		r.chip = chip;
		r.chipColor = chipCol;
		r.name = name;
		r.verdict = verdict;
		r.verdictColor = vCol;
		r.price = price;
		r.liveMargin = margin;
		r.totalQty = total;
		r.soldQty = sold;
		r.unfilledQty = Math.max(0, total - sold);
		r.posProfit = margin * total;
		r.closenessGp = closeGp;
		r.trend = trend;
		r.seated = seated;
		r.lastTrade = last;
		r.pressure = lean;
		r.pressureColor = "sell".equals(lean) ? Palette.RED : "buy".equals(lean) ? Palette.GREEN : Palette.SUBTLE_CANVAS;
		return r;
	}

	public static void main(String[] args) throws Exception
	{
		final List<GeOffersPanelOverlay.Row> rows = new ArrayList<>();
		rows.add(row("S", SELL, "Inquisitor's hauberk", "OK +1.85m", Palette.GREEN, 116_240_000L, 1_850_000L, 1, 0, 0, 1, true, "@116.24m 19m", "sell"));
		rows.add(row("S", SELL, "Scythe of vitur (uncharged)", "OK -2.64m", Palette.RED, 1_300_000_000L, -2_640_000L, 1, 0, 3_900_000L, 1, false, "@1.3b 14m", "flat"));
		rows.add(row("B", BUY, "Inquisitor's hauberk", "OK +1.85m", Palette.GREEN, 112_060_000L, 1_850_000L, 6, 2, 112_000L, 1, false, "@112m 29m", "sell"));
		rows.add(row("B", BUY, "Serpentine visage", "RAISE +156.88k", Palette.AMBER, 3_360_000L, 156_880L, 5, 0, 150_000L, -1, false, "@3.51m 14m", "sell"));
		rows.add(row("S", SELL, "Twisted bow", "OK +538.99k", Palette.GREEN, 1_490_000_000L, 538_990L, 1, 0, 1_490_000L, 0, false, "@1.49b 9m", "flat"));
		rows.add(row("B", BUY, "Harmonised orb", "HOLD", HOLD, 374_100_000L, 0, 1, 0, 7_100_000L, 1, false, "@378.46m 19m", "flat"));
		rows.add(row("B", BUY, "Twisted buckler", "OK +136.23k", Palette.GREEN, 17_800_000L, 136_230L, 8, 3, 90_000L, 0, false, "@17.89m 21s", "flat"));

		final String base = args.length > 0 ? args[0] : "offers-panel.png";
		render(rows, false, true, base);                                  // expanded, Shift shows [-]
		render(rows, true, true, base.replace(".png", "-collapsed.png")); // collapsed pill, [+]
	}

	private static void render(List<GeOffersPanelOverlay.Row> rows, boolean collapsed, boolean shift, String path) throws Exception
	{
		final int scale = 2;
		final int wLogical = GeOffersPanelOverlay.W + 16;
		final int hLogical = 340;
		final BufferedImage img = new BufferedImage(wLogical * scale, hLogical * scale, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setColor(new Color(0x33, 0x2c, 0x22));   // GE-parchment-ish backdrop
		g.fillRect(0, 0, wLogical, hLogical);
		g.translate(8, 8);
		final GeOffersPanelOverlay.Result r = GeOffersPanelOverlay.paint(g, rows, collapsed, shift);
		g.dispose();
		ImageIO.write(img, "png", new File(path));
		System.out.println("wrote " + path + " panel " + r.size.width + "x" + r.size.height);
	}
}

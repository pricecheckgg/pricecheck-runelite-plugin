package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev-only: renders TerminalRadarOverlay.paintColumn (the desk's left column:
 *  radar + fresh dips + top movers) from mock board data. Args: [outputPath]. */
public final class TerminalRadarPreview
{
	private TerminalRadarPreview() { }

	private static FlipData flip(String name, long margin, long evPerHr, double trend)
	{
		final FlipData f = new FlipData();
		f.setName(name);
		f.setGeId(name.hashCode() & 0xffff);
		f.setMargin(margin);
		f.setEvPerHr(evPerHr);
		f.setTrendPct(trend);
		f.setTrend24h(Math.round(trend * 2.3 * 10) / 10.0);
		f.setTrend7d(Math.round(trend * 3.7 * 10) / 10.0);
		return f;
	}

	private static CatchData dip(String name, double pct, int mins, String state)
	{
		final CatchData c = new CatchData();
		c.setName(name);
		c.setPctMove(pct);
		c.setMinutesRunning(mins);
		c.setDir("down");
		c.setState(state);
		c.setCatchable("catchable".equals(state));
		c.setKnife("knife".equals(state));
		return c;
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "radar.png";

		final List<FlipData> flips = new ArrayList<>();
		flips.add(flip("Dragon claws", 429_000, 1_900_000, 1.4));
		flips.add(flip("Twisted bow", 2_100_000, 1_600_000, 0.9));
		flips.add(flip("Masori body", 812_000, 1_200_000, 0.1));
		flips.add(flip("Torva platebody", 1_440_000, 1_100_000, -0.6));
		flips.add(flip("Avernic defender", 190_000, 980_000, 0.4));
		flips.add(flip("Zaryte crossbow", 1_020_000, 910_000, 3.2));
		flips.add(flip("Voidwaker", 640_000, 870_000, 2.1));
		flips.add(flip("Primordial boots", 118_000, 760_000, 0.2));
		flips.add(flip("Ancestral hat", 540_000, 690_000, -0.4));
		flips.add(flip("Sanguinesti staff", 930_000, 640_000, -4.1));
		flips.add(flip("Elder maul", 305_000, 540_000, 1.8));
		flips.add(flip("Ghrazi rapier", 96_000, 590_000, 0.6));

		final List<CatchData> catches = new ArrayList<>();
		catches.add(dip("Sanguinesti staff", -4.1, 12, "knife"));
		catches.add(dip("Kodai wand", -3.6, 8, "catchable"));
		catches.add(dip("Harmonised orb", -2.9, 5, "watch"));
		catches.add(dip("Dinh's bulwark", -2.4, 3, "catchable"));

		final int w = TerminalRadarOverlay.W, pad = 16, sc = 2, availH = 760;
		final BufferedImage img = new BufferedImage((w + pad * 2) * sc, (availH + pad * 2) * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, w + pad * 2, availH + pad * 2);
		g.translate(pad, pad);
		final int tfIdx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
		// "flash" mode: two passes so the static image can show flash-on-change.
		// Seed the tracker with the current values, nudge a few, then render - the
		// nudged rows flash (green up / red down).
		if (args.length > 2 && "flash".equals(args[2]))
		{
			final BufferedImage warm = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
			TerminalRadarOverlay.paintColumn(warm.createGraphics(), w, availH, flips, catches, tfIdx, new ArrayList<>());
			flips.get(5).setTrendPct(flips.get(5).getTrendPct() + 2.0);
			flips.get(11).setTrendPct(flips.get(11).getTrendPct() + 0.8);
			flips.get(9).setTrendPct(flips.get(9).getTrendPct() - 2.5);
			flips.get(3).setTrendPct(flips.get(3).getTrendPct() - 0.9);
		}
		TerminalRadarOverlay.paintColumn(g, w, availH, flips, catches, tfIdx, new ArrayList<>());
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

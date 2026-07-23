package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev-only: renders the WHOLE terminal desk from the real panel paint methods
 *  (status bar, radar column, held, session, recent flips, ticker) docked around a
 *  placeholder GE, so the live layout can be checked headless. Args: [outputPath]. */
public final class TerminalDeskPreview
{
	private TerminalDeskPreview() { }

	private static FlipData flip(String name, long margin, long ev, double trend, long sell)
	{
		final FlipData f = new FlipData();
		f.setName(name); f.setGeId(name.hashCode() & 0xffff);
		f.setMargin(margin); f.setEvPerHr(ev); f.setTrendPct(trend); f.setSell(sell);
		return f;
	}

	private static CatchData dip(String name, double pct, int mins)
	{
		final CatchData c = new CatchData();
		c.setName(name); c.setPctMove(pct); c.setMinutesRunning(mins); c.setDir("down");
		return c;
	}

	private static FlipLogEngine.Flip closed(String name, long profit, long agoMs, long now)
	{
		final FlipLogEngine.Flip f = new FlipLogEngine.Flip();
		f.name = name; f.profit = profit; f.closedAt = now - agoMs;
		return f;
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "desk.png";
		final long now = 1_700_000_000_000L;

		final List<FlipData> flips = new ArrayList<>();
		flips.add(flip("Dragon claws", 429_000, 1_900_000, 1.4, 168_000_000));
		flips.add(flip("Twisted bow", 2_100_000, 1_600_000, 0.9, 1_204_000_000));
		flips.add(flip("Masori body", 812_000, 1_200_000, 0.1, 54_000_000));
		flips.add(flip("Torva platebody", 1_440_000, 1_100_000, -0.6, 512_000_000));
		flips.add(flip("Avernic defender", 190_000, 980_000, 0.4, 62_100_000));
		flips.add(flip("Zaryte crossbow", 1_020_000, 910_000, 3.2, 38_400_000));
		flips.add(flip("Voidwaker", 640_000, 870_000, 2.1, 89_000_000));
		flips.add(flip("Primordial boots", 118_000, 760_000, 0.2, 32_000_000));
		flips.add(flip("Ancestral hat", 540_000, 690_000, -0.4, 41_000_000));
		flips.add(flip("Sanguinesti staff", 930_000, 640_000, -4.1, 78_900_000));
		flips.add(flip("Elder maul", 305_000, 540_000, 1.8, 48_000_000));
		flips.add(flip("Ghrazi rapier", 96_000, 590_000, 0.6, 43_000_000));

		final List<CatchData> catches = new ArrayList<>();
		catches.add(dip("Sanguinesti staff", -4.1, 12));
		catches.add(dip("Kodai wand", -3.6, 8));
		catches.add(dip("Harmonised orb", -2.9, 5));
		catches.add(dip("Dinh's bulwark", -2.4, 3));

		final FlipLogEngine.Summary s = new FlipLogEngine.Summary();
		s.todayProfit = 48_700_000; s.sessionGpHr = 12_300_000; s.winRatePct = 88;
		s.allFlips = 34; s.allTax = 9_400_000; s.avgRoiPct = 2.4;
		s.recent = new ArrayList<>();
		s.recent.add(closed("Elysian spirit shield", 2_070_000, 60_000, now));
		s.recent.add(closed("Twisted bow", 6_200_000, 190_000, now));
		s.recent.add(closed("Scythe of vitur", -3_490_000, 360_000, now));
		s.recent.add(closed("Torva platebody", 1_240_000, 540_000, now));
		s.recent.add(closed("Masori body", 812_000, 720_000, now));
		s.recent.add(closed("Dragon claws", 429_000, 1_100_000, now));
		s.recent.add(closed("Avernic defender", -190_000, 1_500_000, now));

		final List<TerminalHeldOverlay.Row> held = new ArrayList<>();
		held.add(new TerminalHeldOverlay.Row("Tumeken's shadow", 2, 805_000_000, -15_200_000, true));
		held.add(new TerminalHeldOverlay.Row("Scythe of vitur", 1, 1_280_000_000, -3_490_000, true));
		held.add(new TerminalHeldOverlay.Row("Elysian spirit shield", 1, 519_800_000, 2_820_000, true));

		final int W = 1500, Hc = 940, sc = 1;
		final BufferedImage img = new BufferedImage(W * sc, Hc * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x04, 0x04, 0x06));
		g.fillRect(0, 0, W, Hc);

		// centre GE box
		final int geW = 560, geH = 340, geX = (W - geW) / 2, geY = 300;
		g.setColor(new Color(0x2a, 0x22, 0x12));
		g.fillRect(geX, geY, geW, geH);
		g.setColor(new Color(0x5a, 0x4a, 0x24));
		g.drawRect(geX, geY, geW, geH);
		g.setFont(TerminalKit.monoB(13)); g.setColor(TerminalKit.DIM);
		g.drawString("GRAND EXCHANGE  ( the live game window )", geX + 16, geY + geH / 2);

		draw(g, geX, geY - 36, () -> TerminalStatusOverlay.paintBar(g, geW, 30, 71_792_312L, 3, 405, "23:10:59"));

		final int heldH = 32 + held.size() * 18 + 6;
		draw(g, geX, geY - 36 - heldH - 4, () -> TerminalHeldOverlay.paintHeld(g, geW, held));

		draw(g, geX - TerminalRadarOverlay.W - 8, 8,
			() -> TerminalRadarOverlay.paintColumn(g, TerminalRadarOverlay.W, Hc - 16, flips, catches));

		draw(g, geX, geY + geH + 4, () -> TerminalSessionOverlay.paintStrip(g, geW, s));

		// blotter placeholder (already shipped/verified elsewhere)
		final int bx = geX + geW + 8;
		g.setColor(TerminalKit.PANEL); g.fillRect(bx, 8, 292, 240);
		g.setColor(TerminalKit.BORDER); g.drawRect(bx, 8, 292, 240);
		g.setColor(TerminalKit.TITLEBG); g.fillRect(bx + 1, 9, 290, 17);
		g.setFont(TerminalKit.monoB(10)); g.setColor(TerminalKit.AMBERHI);
		g.drawString("POSITIONS BLOTTER  ·  (shipped)", bx + 8, 21);

		final int floorY = Hc - TerminalTickerOverlay.H - 8;
		int rowN = Math.min(s.recent.size(), 8);
		final int fh = 32 + rowN * 16 + 6;
		final int fN = rowN;
		draw(g, bx, floorY - fh, () -> TerminalFillsOverlay.paintFills(g, TerminalFillsOverlay.W, s.recent, fN, now));

		draw(g, 0, Hc - TerminalTickerOverlay.H, () -> TerminalTickerOverlay.paintTicker(g, W, flips, 0));

		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}

	private static void draw(Graphics2D g, int x, int y, Runnable r)
	{
		g.translate(x, y);
		try { r.run(); }
		finally { g.translate(-x, -y); }
	}
}

package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Dev-only: renders TerminalOrderOverlay.paintOrder (the setup-screen ORDER
 *  ticket + your-trades log) for a SELL and a BUY. Args: [outputPath]. */
public final class TerminalOrderPreview
{
	private TerminalOrderPreview() { }

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "order.png";
		final long now = 1_700_000_000_000L;
		final long nowSec = now / 1000L;

		// SELL: hold at 515.6m cost, listing at 518.8m.
		final TerminalOrderOverlay.Ticket sell = new TerminalOrderOverlay.Ticket();
		sell.item = "Elysian spirit shield";
		sell.sell = true;
		sell.price = 518_800_000L;
		sell.qty = 1;
		sell.ref = 498_000_000L;
		sell.refLabel = "your cost";
		sell.netEa = GeTax.net(sell.ref, sell.price);
		sell.total = sell.netEa * sell.qty;
		sell.roi = sell.netEa * 100.0 / sell.ref;
		sell.priced = true;
		final long[][] sellTrades = {
			{now - 3_600_000L, 511_000_000L, 1, 1, 1, 0},
			{now - 7_200_000L, 505_000_000L, 1, 0, 0, 6_800_000L},
			{now - 90_000_000L, 500_000_000L, 2, 0, 0, 12_400_000L},
		};

		// BUY: buying claws at 167.5m, resells at 169.4m.
		final TerminalOrderOverlay.Ticket buy = new TerminalOrderOverlay.Ticket();
		buy.item = "Dragon claws";
		buy.sell = false;
		buy.price = 158_000_000L;
		buy.qty = 8;
		buy.ref = 169_400_000L;
		buy.refLabel = "resells at";
		buy.netEa = GeTax.net(buy.price, buy.ref);
		buy.total = buy.netEa * buy.qty;
		buy.roi = buy.netEa * 100.0 / buy.price;
		buy.priced = true;
		final long[][] buyTrades = {
			{now - 1_800_000L, 166_000_000L, 4, 1, 1, 0},
			{now - 200_000_000L, 165_000_000L, 3, 0, 0, 4_100_000L},
		};

		final int w = TerminalOrderOverlay.W, pad = 16, sc = 2, gap = 24;
		final int hA = 146 + 24 + sellTrades.length * 15;
		final int hB = 146 + 24 + buyTrades.length * 15;
		final int totalW = w * 2 + pad * 3;
		final int totalH = Math.max(hA, hB) + pad * 2;
		final BufferedImage img = new BufferedImage(totalW * sc, totalH * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, totalW, totalH);
		g.translate(pad, pad);
		TerminalOrderOverlay.paintOrder(g, w, sell, sellTrades, sellTrades.length, nowSec);
		g.translate(w + gap, 0);
		TerminalOrderOverlay.paintOrder(g, w, buy, buyTrades, buyTrades.length, nowSec);
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

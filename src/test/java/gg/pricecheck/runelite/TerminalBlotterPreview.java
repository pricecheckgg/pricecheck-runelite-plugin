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
 * Dev-only: renders GeOffersPanelOverlay.paintTerminal (the terminal blotter) from
 * mock rows matching the owner's 4-offer scene. Args: [outputPath].
 */
public final class TerminalBlotterPreview
{
	private TerminalBlotterPreview() { }

	private static GeOffersPanelOverlay.Row row(String chip, Color chipC, String name, String verdict,
		Color verdictC, long price, long qty, long pnl, long close, int trend, boolean seated)
	{
		final GeOffersPanelOverlay.Row r = new GeOffersPanelOverlay.Row();
		r.chip = chip;
		r.chipColor = chipC;
		r.name = name;
		r.verdict = verdict;
		r.verdictColor = verdictC;
		r.price = price;
		r.totalQty = qty;
		r.posProfit = pnl;
		r.closenessGp = close;
		r.trend = trend;
		r.seated = seated;
		return r;
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/termblotter.png";
		final Color RED = TerminalKit.RED, GREEN = TerminalKit.GREEN, AMBER = TerminalKit.AMBER;
		final List<GeOffersPanelOverlay.Row> rows = new ArrayList<>();
		rows.add(row("S", RED, "Tumeken's shadow (uncharged)", "OK +1.12m", GREEN, 805_000_000L, 2, 2_240_000L, 5_760_000L, 1, false));
		rows.add(row("S", RED, "Scythe of vitur (uncharged)", "OK -2m", GREEN, 1_290_000_000L, 1, -2_000_000L, 8_550_000L, -1, false));
		rows.add(row("S", RED, "Tumeken's shadow (uncharged)", "HOLD", AMBER, 805_000_000L, 1, -1_120_000L, 6_000_000L, -1, false));
		rows.add(row("S", RED, "Elysian spirit shield", "OK +2.82m", GREEN, 519_830_000L, 1, 2_820_000L, 0L, 0, true));

		final int pad = 16, sc = 2, guessW = GeOffersPanelOverlay.TERM_W + pad * 2, guessH = 240;
		final BufferedImage img = new BufferedImage(guessW * sc, guessH * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, guessW, guessH);
		g.translate(pad, pad);
		final GeOffersPanelOverlay.Result r = GeOffersPanelOverlay.paintTerminal(g, rows, false, false);
		g.dispose();
		final BufferedImage crop = img.getSubimage(0, 0, guessW * sc, Math.min((r.size.height + pad * 2) * sc, img.getHeight()));
		ImageIO.write(crop, "png", new File(out));
		System.out.println("wrote " + out + " (blotter " + r.size.width + "x" + r.size.height + ")");
	}
}

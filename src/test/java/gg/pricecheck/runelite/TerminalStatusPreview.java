package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Dev-only: renders TerminalStatusOverlay.paintBar headlessly so the terminal
 * status bar's look can be checked without the game. Draws it on a GE-width strip
 * over a dark backdrop. Args: [outputPath]. Never shipped (test source set).
 */
public final class TerminalStatusPreview
{
	private TerminalStatusPreview() { }

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/termbar.png";
		final int w = 640, h = 30, pad = 16, s = 2;
		final BufferedImage img = new BufferedImage((w + pad * 2) * s, (h + pad * 2) * s, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(s, s);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// dark "game" backdrop so the bar reads as an overlay
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, w + pad * 2, h + pad * 2);
		g.translate(pad, pad);
		TerminalStatusOverlay.paintBar(g, w, h, 4_266_644_991L, 4, 302, "20:14:07");
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

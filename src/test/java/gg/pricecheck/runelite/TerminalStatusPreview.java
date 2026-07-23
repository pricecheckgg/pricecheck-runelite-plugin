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
		final int h = 30, pad = 16, s = 2;
		// wide = GE overview window; narrow = single-offer status window (the tight case)
		final int wide = 660, narrow = 460;
		final BufferedImage img = new BufferedImage((wide + pad * 2) * s, (h * 2 + pad * 3) * s, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(s, s);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, wide + pad * 2, h * 2 + pad * 3);
		g.translate(pad, pad);
		TerminalStatusOverlay.paintBar(g, wide, h, 4_266_644_991L, 4, 302, "20:14:07");
		g.translate(0, h + pad);
		TerminalStatusOverlay.paintBar(g, narrow, h, 64_139_323L, 4, 465, "20:59:24");
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

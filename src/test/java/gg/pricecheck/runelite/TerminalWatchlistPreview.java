package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Dev-only: renders TerminalWatchlistOverlay.paintWatchlist - left: a few favs +
 *  picks (content-sized); right: many favs paged (page 1/2). Args: [outputPath]. */
public final class TerminalWatchlistPreview
{
	private TerminalWatchlistPreview() { }

	private static TerminalWatchlistOverlay.Watch w(String n, long t, int s)
	{
		return new TerminalWatchlistOverlay.Watch(n, t, s);
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "watchlist.png";

		// Case A: 3 favs + picks fill, content-sized.
		final List<TerminalWatchlistOverlay.Watch> few = new ArrayList<>();
		few.add(w("Twisted bow", 1_200_000_000L, 2));
		few.add(w("Masori body", 53_100_000L, 1));
		few.add(w("Elysian spirit shield", 515_000_000L, 0));
		final List<TerminalWatchlistOverlay.Pick> picks = new ArrayList<>();
		picks.add(new TerminalWatchlistOverlay.Pick("Dragon claws", 167_500_000L, 1_900_000L));
		picks.add(new TerminalWatchlistOverlay.Pick("Zaryte crossbow", 37_400_000L, 910_000L));
		picks.add(new TerminalWatchlistOverlay.Pick("Voidwaker", 88_400_000L, 870_000L));
		final int hA = 32 + few.size() * 16 + 20 + picks.size() * 16 + 6;

		// Case B: 11 favs -> paged; page 1 of 2 with 6 rows.
		final List<TerminalWatchlistOverlay.Watch> many = new ArrayList<>();
		final String[] nm = {"Twisted bow", "Scythe of vitur", "Tumeken's shadow", "Masori body",
			"Torva platebody", "Venator ring", "Zaryte crossbow", "Voidwaker", "Elder maul",
			"Ancestral hat", "Primordial boots"};
		for (int i = 0; i < nm.length; i++)
		{
			many.add(w(nm[i], 100_000_000L + i * 7_000_000L, i < 2 ? 2 : i < 4 ? 1 : 0));
		}
		final int perPage = 6;
		final List<TerminalWatchlistOverlay.Watch> page1 = new ArrayList<>(many.subList(0, perPage));
		final int hB = 32 + perPage * 16 + 6;

		final int w = 560, pad = 16, sc = 2, gap = 24;
		final int totalW = (w * 2 + pad * 3);
		final int totalH = Math.max(hA, hB) + pad * 2;
		final BufferedImage img = new BufferedImage(totalW * sc, totalH * sc, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(sc, sc);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x14, 0x12, 0x0d));
		g.fillRect(0, 0, totalW, totalH);
		g.translate(pad, pad);
		TerminalWatchlistOverlay.paintWatchlist(g, w, hA, few, picks, 0, 1);
		g.translate(w + gap, 0);
		TerminalWatchlistOverlay.paintWatchlist(g, w, hB, page1, new ArrayList<>(), 0, 2);
		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

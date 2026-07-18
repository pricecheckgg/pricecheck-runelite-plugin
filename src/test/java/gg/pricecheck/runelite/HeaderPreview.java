package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import javax.imageio.ImageIO;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * Dev-only: renders the Log tab headlessly to a PNG so panel design can be
 * iterated without launching the client. Run main() from an IDE on the test
 * classpath with args [outputPath, variant]; variants are active, loss,
 * fresh. Never shipped (test source set).
 */
public final class HeaderPreview
{
	private HeaderPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/header.png";
		final String variant = args.length > 1 ? args[1] : "active";

		final PriceCheckPanel.Listener stub = new PriceCheckPanel.Listener()
		{
			public void onTrack(FlipData flip) { }
			public void onUntrack(int geId) { }
			public void onSearch(String query) { }
			public void onFetchAccount() { }
			public void onBuildPlan(long capital, int slots, int accounts, int hours) { }
			public void onDeleteFlip(String flipId) { }
			public void onDeleteLot(int itemId, int qty, long cost, long openedAt) { }
		};

		final PriceCheckPanel[] holder = new PriceCheckPanel[1];
		EventQueue.invokeAndWait(() ->
		{
			final PriceCheckPanel panel = new PriceCheckPanel(stub, null, null, new PriceCheckConfig() { });
			final MaterialTabGroup group = (MaterialTabGroup) panel.getComponent(0);
			group.select((MaterialTab) group.getComponent(1));   // Log tab
			holder[0] = panel;
		});

		final FlipLogEngine.Summary s = new FlipLogEngine.Summary();
		if ("active".equals(variant))
		{
			s.sessionProfit = 572_930;
			s.sessionGpHr = 224_460;
			s.todayProfit = 873_760;
			s.weekProfit = 3_990_000;
			s.allProfit = 3_990_000;
			s.allTax = 8_190_000;
			s.allFlips = 131;
			s.allWins = 123;
			s.winRatePct = 94;
			s.avgRoiPct = 1.83;
			s.checks = 2;
			s.untrackedSells = 9908;
			s.pendingSync = 0;
		}
		else if ("fresh".equals(variant))
		{
			s.allFlips = 0;
			s.winRatePct = -1;
		}
		else if ("loss".equals(variant))
		{
			s.sessionProfit = -1_240_000;
			s.sessionGpHr = -310_000;
			s.todayProfit = -2_140_000;
			s.weekProfit = 12_400_000;
			s.allProfit = 1_020_000_000L;
			s.allTax = 42_000_000;
			s.allFlips = 4212;
			s.allWins = 2990;
			s.winRatePct = 71;
			s.avgRoiPct = -0.42;
			s.checks = 155;
			s.pendingSync = 37;
		}
		final java.util.List<FlipLogEngine.Lot> lots = new java.util.ArrayList<>();
		final FlipLogEngine.Lot lot = new FlipLogEngine.Lot();
		lot.itemId = 11732; lot.name = "Armadyl chainskirt"; lot.qty = 1; lot.cost = 26_720_000L; lot.openedAt = System.currentTimeMillis() - 73 * 60_000L;
		lots.add(lot);
		s.openLots = "fresh".equals(variant) ? Collections.emptyList() : lots;
		final java.util.List<FlipLogEngine.Flip> flips = new java.util.ArrayList<>();
		final String[] nm = {"Bloodbark legs", "Tonalztics of ralos (uncharged)", "Dragon claws", "Zulrah's scales", "Twisted bow"};
		final long[] pf = {9_528, -40_010, 142_500, -3_240, 512_000};
		final int[] qty = {1, 1, 3, 10_000, 1};
		final long[] bg = {352_000, 40_010_000L, 180_600_000L, 2_250_000L, 1_524_036_000L};
		final long[] hold = {21, 107, 34, 260, 73};   // minutes, exercises the h/m formats
		for (int i = 0; i < nm.length; i++) {
			final FlipLogEngine.Flip f = new FlipLogEngine.Flip();
			f.itemId = 100 + i; f.name = nm[i]; f.qty = qty[i]; f.profit = pf[i]; f.buyGross = bg[i];
			f.closedAt = System.currentTimeMillis() - (i + 1) * 900_000L;
			f.openedAt = f.closedAt - hold[i] * 60_000L;
			f.check = i == 3;
			flips.add(f);
		}
		s.recent = "fresh".equals(variant) ? Collections.emptyList() : flips;

		holder[0].setFlipLog(s, !"fresh".equals(variant));
		EventQueue.invokeAndWait(() -> { });

		EventQueue.invokeAndWait(() ->
		{
			// Paint the header in isolation at the true side-panel inner width
			// (RuneLite panel ~226px). The whole scrollpane can't lay out
			// headless without a shown window, but the header can.
			final javax.swing.JComponent head = holder[0].logBodyForPreview();
			final int w = 226;
			invalidateAll(head);
			head.setSize(w, 2000);
			layoutTree(head);
			// Override each nested BoxLayout container's stale preferred height
			// with the real extent of its children (headless caches don't clear).
			fixHeights(head, w);
			invalidateAll(head);
			final int h = head.getPreferredSize().height;
			head.setSize(w, h);
			layoutTree(head);
			final BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
			final Graphics2D g = img.createGraphics();
			g.scale(2, 2);
			g.setColor(new Color(34, 34, 34));
			g.fillRect(0, 0, w, h);
			head.paint(g);
			g.dispose();
			try
			{
				ImageIO.write(img, "png", new File(out));
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		System.out.println("wrote " + out);
	}



	private static void invalidateAll(Component c)
	{
		if (c instanceof Container)
		{
			for (final Component k : ((Container) c).getComponents())
			{
				invalidateAll(k);
			}
		}
		c.invalidate();
	}

	private static int fixHeights(Component c, int w)
	{
		final boolean vbox = c instanceof Container
			&& ((Container) c).getLayout() instanceof javax.swing.BoxLayout;
		if (!vbox)
		{
			return c.getPreferredSize().height;   // BorderLayout rows, labels: trust their own pref
		}
		int sum = 0;
		for (final Component k : ((Container) c).getComponents())
		{
			sum += fixHeights(k, w);
		}
		final java.awt.Insets in = ((javax.swing.JComponent) c).getInsets();
		final int total = sum + in.top + in.bottom;
		c.setPreferredSize(new java.awt.Dimension(w, total));
		c.setSize(w, total);
		return total;
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (final Component k : ((Container) c).getComponents())
			{
				layoutTree(k);
			}
		}
	}
}

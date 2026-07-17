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
 * Dev-only: renders the Log tab headlessly to a PNG so header design can be
 * iterated without launching the client. Never shipped (test source set).
 *
 *   ./gradlew --init-script preview.init.gradle previewHeader -Pout=/tmp/h.png
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
			public void onBuildPlan(long capital, int slots, int accounts) { }
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
			s.winRatePct = 94;
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
			s.winRatePct = 71;
			s.checks = 155;
			s.pendingSync = 37;
		}
		s.openLots = Collections.emptyList();
		s.recent = Collections.emptyList();

		holder[0].setFlipLog(s, !"fresh".equals(variant));
		EventQueue.invokeAndWait(() -> { });

		EventQueue.invokeAndWait(() ->
		{
			// Paint the header in isolation at the true side-panel inner width
			// (RuneLite panel ~226px). The whole scrollpane can't lay out
			// headless without a shown window, but the header can.
			final javax.swing.JComponent head = holder[0].logHeaderForPreview();
			final int w = 226;
			invalidateAll(head);
			final int h = head.getPreferredSize().height;
			head.setSize(w, h);
			layoutAll(head);
			dump(head, 0);
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

	private static void dump(Component c, int d)
	{
		if (c instanceof javax.swing.JLabel)
		{
			final javax.swing.JLabel l = (javax.swing.JLabel) c;
			System.err.println("LABEL w=" + c.getWidth() + " pref=" + c.getPreferredSize().width
				+ " max=" + c.getMaximumSize().width + " text=[" + l.getText() + "]");
		}
		if (c instanceof Container)
		{
			for (final Component k : ((Container) c).getComponents()) { dump(k, d + 1); }
		}
	}

	private static void layoutAll(Component c)
	{
		// BoxLayout caches child size requirements at build time (text was
		// blank then); invalidate drops that cache so doLayout recomputes
		// from the now-populated labels. Without a shown window nothing
		// else triggers it.
		invalidateAll(c);
		layoutTree(c);
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

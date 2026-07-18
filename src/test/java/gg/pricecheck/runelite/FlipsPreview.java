package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * Dev-only: renders Flips-tab board rows headlessly to a PNG — a confirmed
 * row, risk-tier rows (Stale Prints / Big), and a plain row — so the row
 * design can be iterated without a live feed. Args: [outputPath]. Never
 * shipped (test source set).
 */
public final class FlipsPreview
{
	private FlipsPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/flips.png";

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

		final JPanel[] holder = new JPanel[1];
		EventQueue.invokeAndWait(() ->
		{
			final PriceCheckPanel panel = new PriceCheckPanel(stub, null, null, new PriceCheckConfig() { });
			final JPanel list = new JPanel();
			list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
			list.setBackground(new Color(30, 30, 30));
			list.add(panel.flipRow(flip("Uncharged toxic trident", 1_410_000, 1_495_000, 86_100, 1_240_000, true, null), false));
			list.add(gap(list));
			list.add(panel.flipRow(flip("Ranger boots", 32_850_000, 33_600_000, 385_240, 890_000, true, null), true));
			list.add(gap(list));
			list.add(panel.flipRow(flip("Tonalztics of ralos (uncharged)", 39_970_000, 40_390_000, 23_800, 310_000, false, "age"), false));
			list.add(gap(list));
			list.add(panel.flipRow(flip("Tumeken's shadow (uncharged)", 818_800_000, 824_500_000, 752_000, 1_710_000, false, "big"), false));
			holder[0] = list;
		});

		EventQueue.invokeAndWait(() ->
		{
			final int w = 226;
			holder[0].setSize(w, 2000);
			layoutTree(holder[0]);
			final int h = holder[0].getPreferredSize().height;
			holder[0].setSize(w, h);
			layoutTree(holder[0]);
			final BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
			final Graphics2D g = img.createGraphics();
			g.scale(2, 2);
			g.setColor(new Color(30, 30, 30));
			g.fillRect(0, 0, w, h);
			holder[0].paint(g);
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

	private static FlipData flip(String name, long buy, long sell, long profit, long evPerHr, boolean confirmed, String risk)
	{
		final FlipData f = new FlipData();
		f.setGeId(4151);
		f.setName(name);
		f.setBuy(buy);
		f.setSell(sell);
		f.setProfit(profit);
		f.setEvPerHr(evPerHr);
		f.setConfirmed(confirmed);
		f.setRisk(risk);
		return f;
	}

	private static Component gap(JPanel parent)
	{
		return javax.swing.Box.createRigidArea(new java.awt.Dimension(0, 5));
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

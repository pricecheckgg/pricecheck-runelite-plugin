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
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/** Dev-only: render the Setup Discord card headless to a PNG to verify wrapping. */
public final class CardPreview
{
	private CardPreview() { }

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/card.png";
		final PriceCheckPanel.Listener stub = new PriceCheckPanel.Listener()
		{
			public void onTrack(FlipData f) { }
			public void onUntrack(int g) { }
			public void onSearch(String q) { }
			public void onFetchAccount() { }
			public void onBuildPlan(long c, int s, int a, int h) { }
			public void onDeleteFlip(String id) { }
			public void onDeleteLot(int i, int q, long c, long o) { }
		};
		final javax.swing.JComponent[] card = new javax.swing.JComponent[1];
		EventQueue.invokeAndWait(() ->
		{
			final PriceCheckPanel p = new PriceCheckPanel(stub, null, null, new PriceCheckConfig() { });
			final MaterialTabGroup g = (MaterialTabGroup) p.getComponent(0);
			g.select((MaterialTab) g.getComponent(3));   // Setup tab
			card[0] = p.discordCardForPreview();
		});
		EventQueue.invokeAndWait(() ->
		{
			final javax.swing.JComponent c = card[0];
			final int w = 226;
			invalidateAll(c);
			c.setSize(w, 2000);
			layout(c);
			fixHeights(c, w);
			invalidateAll(c);
			final int h = c.getPreferredSize().height;
			c.setSize(w, h);
			layout(c);
			final BufferedImage img = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
			final Graphics2D gr = img.createGraphics();
			gr.scale(2, 2);
			gr.setColor(new Color(34, 34, 34));
			gr.fillRect(0, 0, w, h);
			c.paint(gr);
			gr.dispose();
			try { ImageIO.write(img, "png", new File(out)); }
			catch (Exception e) { throw new RuntimeException(e); }
		});
		System.out.println("wrote " + out);
	}

	private static void invalidateAll(Component c)
	{
		if (c instanceof Container) { for (Component k : ((Container) c).getComponents()) { invalidateAll(k); } }
		c.invalidate();
	}

	private static void layout(Component c)
	{
		c.doLayout();
		if (c instanceof Container) { for (Component k : ((Container) c).getComponents()) { layout(k); } }
	}

	private static int fixHeights(Component c, int w)
	{
		final boolean vbox = c instanceof Container && ((Container) c).getLayout() instanceof BoxLayout;
		if (!vbox) { return c.getPreferredSize().height; }
		int sum = 0;
		for (Component k : ((Container) c).getComponents()) { sum += fixHeights(k, w); }
		final java.awt.Insets in = ((javax.swing.JComponent) c).getInsets();
		final int total = sum + in.top + in.bottom;
		c.setPreferredSize(new java.awt.Dimension(w, total));
		c.setSize(w, total);
		return total;
	}
}

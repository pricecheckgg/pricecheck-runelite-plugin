package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;

/**
 * The per-item evidence chart: 24h of 5-minute windows drawn through the
 * shared ChartKit bucketing (volume-weighted corridor, gold where the
 * after-tax spread paid, wick envelope, price grid, volume strip), with the
 * engine's current quotes as labeled price lines, your own logged fills as
 * exact-price markers, manipulation windows ticked, and the forecast band
 * noted.
 */
class ItemChart extends JComponent
{
	/** One item's series + the engine's live read of it, as plain arrays. */
	static final class Series
	{
		long[] ts;          // window start, epoch seconds, ascending
		long[] high;        // avg insta-buy print (what sellers received)
		long[] low;         // avg insta-sell print (what buyers paid)
		int[] hvol;
		int[] lvol;
		boolean[] manip;    // volume-surge manipulation flag per window
		long quoteBuy;      // engine bid (0 = none)
		long quoteSell;     // engine ask (0 = none)
		int fillPct = -1;   // measured odds both sides cross in 4h, -1 = unknown
		int fcFrom = -1;    // forecast band UTC hours, -1 = none
		int fcTo = -1;
		String fcNote;      // e.g. "held 14/18 days"
		// Your own logged fills on this item, from the flip log: exact prices,
		// not approximations. Painted as buy/sell triangles on the series.
		long[] markTs;
		long[] markPrice;
		boolean[] markBuy;
	}

	private static final Color QUOTE = new Color(0xe6, 0xc6, 0x67, 190);
	private static final int PAD_L = 4;
	private static final int PAD_R = 54;   // price labels live here
	private static final int AXIS_H = 13;
	private static final int FILL_H = 8;

	private Series s;

	ItemChart(Series s, int width, int height)
	{
		this.s = s;
		setPreferredSize(new Dimension(width, height));
		setMinimumSize(getPreferredSize());
		setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
	}

	void setSeries(Series s)
	{
		this.s = s;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		final Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		final Series ser = s;
		final int w = getWidth();
		final int h = getHeight();
		g2.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g2.getFontMetrics();

		final int chartW = w - PAD_L - PAD_R;
		final int chartH = h - AXIS_H - FILL_H - 4;
		final int chartY = 2;

		final ChartKit.Display d = ChartKit.build(ser, chartW,
			ser != null ? ser.quoteBuy : 0, ser != null ? ser.quoteSell : 0);
		if (d == null)
		{
			g2.setColor(Palette.SUBTLE);
			g2.drawString("No trade history yet", PAD_L + 4, h / 2);
			return;
		}

		// Hour grid + labels every 6h, painted first so everything sits on it.
		for (long t = ((d.tMin / 21600) + 1) * 21600; t < d.tMax; t += 21600)
		{
			final int x = Math.round(ChartKit.x(d, t, PAD_L, chartW));
			g2.setColor(ChartKit.GRID);
			g2.drawLine(x, chartY, x, chartY + chartH + FILL_H + 2);
			g2.setColor(Palette.SUBTLE);
			final String lbl = String.format("%02d:00", (t / 3600) % 24);
			final int lx = Math.max(PAD_L, Math.min(x - fm.stringWidth(lbl) / 2, w - PAD_R - fm.stringWidth(lbl)));
			g2.drawString(lbl, lx, h - 2);
		}

		ChartKit.paintPriceGrid(g2, d, fm, PAD_L, chartY, chartW, chartH, Palette.LIGHT);
		ChartKit.paintCorridor(g2, d, PAD_L, chartY, chartW, chartH);
		ChartKit.paintLevelGuides(g2, d, PAD_L, chartY, chartW, chartH);
		ChartKit.paintFillStrip(g2, d, PAD_L, chartY + chartH + 2, chartW, FILL_H - 2);

		// Manipulation flags: a red tick at the top of the flagged window.
		if (ser.manip != null && ser.ts != null)
		{
			g2.setColor(Palette.RED);
			for (int i = 0; i < ser.ts.length; i++)
			{
				if (ser.manip[i])
				{
					final int x = Math.round(ChartKit.x(d, ser.ts[i], PAD_L, chartW));
					g2.fillRect(x - 1, chartY, 3, 3);
				}
			}
		}

		// Your logged fills: exact buy/sell prices from the flip log, painted
		// where they happened. Triangle up = your buy, down = your sell.
		if (ser.markTs != null)
		{
			for (int i = 0; i < ser.markTs.length; i++)
			{
				if (ser.markTs[i] < d.tMin || ser.markTs[i] > d.tMax || ser.markPrice[i] <= 0)
				{
					continue;
				}
				final float mx = ChartKit.x(d, ser.markTs[i], PAD_L, chartW);
				final float my = ChartKit.y(d, ser.markPrice[i], chartY, chartH);
				final Path2D tri = new Path2D.Float();
				if (ser.markBuy[i])
				{
					tri.moveTo(mx - 4, my + 3);
					tri.lineTo(mx + 4, my + 3);
					tri.lineTo(mx, my - 4);
				}
				else
				{
					tri.moveTo(mx - 4, my - 3);
					tri.lineTo(mx + 4, my - 3);
					tri.lineTo(mx, my + 4);
				}
				tri.closePath();
				g2.setColor(new Color(0, 0, 0, 170));
				g2.translate(1, 1);
				g2.fill(tri);
				g2.translate(-1, -1);
				g2.setColor(ser.markBuy[i] ? Palette.GREEN : Palette.RED);
				g2.fill(tri);
			}
		}

		// Engine quotes: dotted price lines with right-edge labels. These are
		// the exact numbers the board quotes right now.
		final java.awt.Stroke dotted = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 3f}, 0f);
		if (ser.quoteSell > 0)
		{
			quoteLine(g2, d, dotted, ser.quoteSell, "ask " + Fmt.compact(ser.quoteSell), chartY, chartH, w, fm);
		}
		if (ser.quoteBuy > 0)
		{
			quoteLine(g2, d, dotted, ser.quoteBuy, "bid " + Fmt.compact(ser.quoteBuy), chartY, chartH, w, fm);
		}

		// Forecast band note when the band opens within the coming hours.
		if (ser.fcFrom >= 0 && ser.fcTo > ser.fcFrom)
		{
			final long dayStart = (d.tMax / 86400) * 86400;
			long bandStart = dayStart + ser.fcFrom * 3600L;
			if (bandStart < d.tMax)
			{
				bandStart += 86400;
			}
			if (bandStart - d.tMax < 21600)
			{
				g2.setColor(QUOTE);
				g2.fillRect(w - PAD_R + 1, chartY, 2, chartH);
				g2.setColor(Palette.GOLD);
				final String note = String.format("%02d:00", ser.fcFrom) + (ser.fcNote != null ? " " + ser.fcNote : "");
				g2.drawString(note, w - PAD_R - fm.stringWidth(note) - 4, chartY + 10);
			}
		}
	}

	private void quoteLine(Graphics2D g2, ChartKit.Display d, java.awt.Stroke dotted, long price, String label, int y0, int ch, int w, FontMetrics fm)
	{
		final int yy = Math.round(ChartKit.y(d, price, y0, ch));
		g2.setStroke(dotted);
		g2.setColor(QUOTE);
		g2.drawLine(PAD_L, yy, w - PAD_R, yy);
		g2.setStroke(new BasicStroke(1f));
		g2.setColor(Palette.GOLD);
		g2.drawString(label, w - PAD_R + 3, yy + 4);
	}
}

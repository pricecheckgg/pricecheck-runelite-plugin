package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;

/**
 * The per-item evidence chart: 24h of 5-minute windows painted as the traded
 * corridor (volume-weighted high over low), gold-washed only where the
 * after-tax spread was actually positive, with the engine's current quotes as
 * price ticks, a per-window fill strip so liquidity is visible instead of
 * claimed, and manipulation windows flagged. Everything on it is measured;
 * nothing is a model's opinion. Painted by hand like the advisor overlay so
 * it matches the plugin's look and needs no chart library.
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

	private static final Color CORRIDOR = new Color(255, 255, 255, 22);
	private static final Color CORRIDOR_PAID = new Color(0xe6, 0xc6, 0x67, 58);
	private static final Color LINE_HIGH = new Color(0x5d, 0xf2, 0x9a, 170);
	private static final Color LINE_LOW = new Color(0xf2, 0x6b, 0x6d, 170);
	private static final Color QUOTE = new Color(0xe6, 0xc6, 0x67, 190);
	private static final Color GRID = new Color(255, 255, 255, 16);
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
		final Series d = s;
		final int w = getWidth();
		final int h = getHeight();
		if (d == null || d.ts == null || d.ts.length < 2)
		{
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(Palette.SUBTLE);
			g2.drawString("No trade history yet", PAD_L + 4, h / 2);
			return;
		}

		final int n = d.ts.length;
		final int chartW = w - PAD_L - PAD_R;
		final int chartH = h - AXIS_H - FILL_H - 4;
		final int chartY = 2;

		// Price scale over everything drawn, quotes included.
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (int i = 0; i < n; i++)
		{
			if (d.low[i] > 0) { min = Math.min(min, d.low[i]); }
			if (d.high[i] > 0) { max = Math.max(max, d.high[i]); }
		}
		if (d.quoteBuy > 0) { min = Math.min(min, d.quoteBuy); }
		if (d.quoteSell > 0) { max = Math.max(max, d.quoteSell); }
		if (min >= max) { min = max - 1; }
		final long span = max - min;
		min -= span / 12;
		max += span / 12;

		final float dx = chartW / (float) (n - 1);
		final long tMin = d.ts[0];
		final long tMax = d.ts[n - 1];

		// Hour grid + labels every 6h, painted first so everything sits on it.
		g2.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g2.getFontMetrics();
		for (long t = ((tMin / 21600) + 1) * 21600; t < tMax; t += 21600)
		{
			final int x = PAD_L + Math.round((t - tMin) / (float) (tMax - tMin) * chartW);
			g2.setColor(GRID);
			g2.drawLine(x, chartY, x, chartY + chartH + FILL_H + 2);
			g2.setColor(Palette.SUBTLE);
			final String lbl = String.format("%02d:00", (t / 3600) % 24);
			final int lx = Math.max(PAD_L, Math.min(x - fm.stringWidth(lbl) / 2, w - PAD_R - fm.stringWidth(lbl)));
			g2.drawString(lbl, lx, h - 2);
		}

		// The corridor: filled between the high and low series. Gold where the
		// after-tax spread was positive, near-invisible where it was not. Gaps
		// (untraded windows) stay empty; honesty beats smoothness.
		for (int i = 0; i < n - 1; i++)
		{
			if (d.high[i] <= 0 || d.low[i] <= 0 || d.high[i + 1] <= 0 || d.low[i + 1] <= 0)
			{
				continue;
			}
			final Path2D p = new Path2D.Float();
			p.moveTo(PAD_L + i * dx, y(d.high[i], min, max, chartY, chartH));
			p.lineTo(PAD_L + (i + 1) * dx, y(d.high[i + 1], min, max, chartY, chartH));
			p.lineTo(PAD_L + (i + 1) * dx, y(d.low[i + 1], min, max, chartY, chartH));
			p.lineTo(PAD_L + i * dx, y(d.low[i], min, max, chartY, chartH));
			p.closePath();
			final boolean paid = GeTax.net(d.low[i] + 1, d.high[i] - 1) > 0;
			g2.setColor(paid ? CORRIDOR_PAID : CORRIDOR);
			g2.fill(p);
		}

		// Corridor edges: what sellers got on top, what buyers paid below.
		g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		paintLine(g2, d, d.high, LINE_HIGH, min, max, chartY, chartH, dx);
		paintLine(g2, d, d.low, LINE_LOW, min, max, chartY, chartH, dx);

		// Manipulation flags: a red tick at the top of the window column.
		g2.setColor(Palette.RED);
		for (int i = 0; i < n; i++)
		{
			if (d.manip != null && d.manip[i])
			{
				final int x = PAD_L + Math.round(i * dx);
				g2.fillRect(x - 1, chartY, 3, 3);
			}
		}

		// Your logged fills: exact buy/sell prices from the flip log, painted
		// where they happened. Triangle up = your buy, down = your sell.
		if (d.markTs != null)
		{
			for (int i = 0; i < d.markTs.length; i++)
			{
				if (d.markTs[i] < tMin || d.markTs[i] > tMax || d.markPrice[i] <= 0)
				{
					continue;
				}
				final float mx = PAD_L + (d.markTs[i] - tMin) / (float) (tMax - tMin) * chartW;
				final float my = y(d.markPrice[i], min, max, chartY, chartH);
				final Path2D tri = new Path2D.Float();
				if (d.markBuy[i])
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
				g2.setColor(d.markBuy[i] ? Palette.GREEN : Palette.RED);
				g2.fill(tri);
			}
		}

		// Engine quotes: dotted price lines with right-edge labels. These are
		// the exact numbers the board quotes right now.
		final java.awt.Stroke dotted = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 3f}, 0f);
		if (d.quoteSell > 0)
		{
			quoteLine(g2, dotted, d.quoteSell, "ask " + Fmt.compact(d.quoteSell), min, max, chartY, chartH, w, fm);
		}
		if (d.quoteBuy > 0)
		{
			quoteLine(g2, dotted, d.quoteBuy, "bid " + Fmt.compact(d.quoteBuy), min, max, chartY, chartH, w, fm);
		}

		// Fill strip: one cell per window. Both sides printed = solid gold,
		// one side = dim gold, none = empty. Liquidity you can count.
		final int fy = chartY + chartH + 2;
		for (int i = 0; i < n; i++)
		{
			final boolean hi = d.hvol != null && d.hvol[i] > 0;
			final boolean lo = d.lvol != null && d.lvol[i] > 0;
			if (!hi && !lo)
			{
				continue;
			}
			g2.setColor(hi && lo ? new Color(0xe6, 0xc6, 0x67, 165) : new Color(0xe6, 0xc6, 0x67, 60));
			g2.fillRect(PAD_L + Math.round(i * dx), fy, Math.max(1, Math.round(dx)), FILL_H - 2);
		}

		// Forecast band: hatch the hours ahead where the margin usually opens.
		if (d.fcFrom >= 0 && d.fcTo > d.fcFrom)
		{
			final long dayStart = (tMax / 86400) * 86400;
			long bandStart = dayStart + d.fcFrom * 3600L;
			if (bandStart < tMax) { bandStart += 86400; }
			if (bandStart - tMax < 21600)
			{
				// Band opens within the visible margin of the future: pin a
				// gold marker at the right edge with the note.
				g2.setColor(QUOTE);
				g2.fillRect(w - PAD_R + 1, chartY, 2, chartH);
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(Palette.GOLD);
				final String note = String.format("%02d:00", d.fcFrom) + (d.fcNote != null ? " " + d.fcNote : "");
				g2.drawString(note, w - PAD_R - fm.stringWidth(note) - 4, chartY + 10);
			}
		}
	}

	private static void paintLine(Graphics2D g2, Series d, long[] v, Color c, long min, long max, int y0, int ch, float dx)
	{
		g2.setColor(c);
		Path2D p = null;
		for (int i = 0; i < v.length; i++)
		{
			if (v[i] <= 0)
			{
				if (p != null) { g2.draw(p); p = null; }
				continue;
			}
			final float x = PAD_L + i * dx;
			final float yy = y(v[i], min, max, y0, ch);
			if (p == null) { p = new Path2D.Float(); p.moveTo(x, yy); }
			else { p.lineTo(x, yy); }
		}
		if (p != null) { g2.draw(p); }
	}

	private void quoteLine(Graphics2D g2, java.awt.Stroke dotted, long price, String label, long min, long max, int y0, int ch, int w, FontMetrics fm)
	{
		final int yy = Math.round(y(price, min, max, y0, ch));
		g2.setStroke(dotted);
		g2.setColor(QUOTE);
		g2.drawLine(PAD_L, yy, w - PAD_R, yy);
		g2.setStroke(new BasicStroke(1f));
		g2.setFont(FontManager.getRunescapeSmallFont());
		g2.setColor(Palette.GOLD);
		g2.drawString(label, w - PAD_R + 3, yy + 4);
	}

	private static float y(long v, long min, long max, int y0, int ch)
	{
		return y0 + ch - ((v - min) / (float) (max - min)) * ch;
	}
}

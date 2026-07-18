package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.Arrays;

/**
 * Shared display pipeline for the evidence charts. Raw 5m windows on a busy
 * commodity render as a picket fence, so everything drawn goes through
 * volume-weighted BUCKETS first: each bucket averages its windows' high and
 * low with their side volumes as weights, keeps its extremes for the wick
 * envelope, and remembers whether the average after-tax spread paid. The
 * painters stay dumb; this owns aggregation, scale, and the corridor.
 */
final class ChartKit
{
	static final Color CORRIDOR = new Color(255, 255, 255, 26);
	static final Color CORRIDOR_PAID = new Color(0xe6, 0xc6, 0x67, 78);
	static final Color ENVELOPE = new Color(255, 255, 255, 12);
	static final Color LINE_HIGH = new Color(0x5d, 0xf2, 0x9a, 185);
	static final Color LINE_LOW = new Color(0xf2, 0x6b, 0x6d, 185);
	static final Color GRID = new Color(255, 255, 255, 14);
	static final Color FILL_CELL = new Color(0xe6, 0xc6, 0x67, 150);
	static final Color FILL_BUY = new Color(0x5d, 0xf2, 0x9a, 150);
	static final Color FILL_SELL = new Color(0xf2, 0x6b, 0x6d, 150);
	static final Color GUIDE_HIGH = new Color(0x5d, 0xf2, 0x9a, 64);
	static final Color GUIDE_LOW = new Color(0xf2, 0x6b, 0x6d, 64);

	/** Bucketed, paint-ready view of a Series. */
	static final class Display
	{
		int n;
		long[] ts;        // bucket midpoint, epoch seconds
		double[] hi;      // volume-weighted avg insta-buy print
		double[] lo;      // volume-weighted avg insta-sell print
		double[] hiMax;   // wick extremes
		double[] loMin;
		long[] vol;       // traded units in the bucket, both sides
		long[] volHi;     // insta-buy side units (aggressive buyers)
		long[] volLo;     // insta-sell side units (aggressive sellers)
		boolean[] paid;   // avg after-tax spread positive
		long tMin;
		long tMax;
		long pMin;        // robust drawn price range
		long pMax;
		long volMax;
		// The newest print on each side, straight from the raw windows: the
		// live market edges a trader adjusts against.
		long lastHigh;
		long lastHighTs;
		long lastLow;
		long lastLowTs;
	}

	private ChartKit()
	{
	}

	/** Bucket a series for a plot of the given pixel width (one bucket ~3px).
	 * extraPrices (your offer, quotes) are folded into the scale. */
	static Display build(ItemChart.Series s, int plotW, long... extraPrices)
	{
		if (s == null || s.ts == null || s.ts.length < 2)
		{
			return null;
		}
		final int nB = Math.max(24, Math.min(96, plotW / 3));
		final int len = s.ts.length;
		final Display d = new Display();
		d.n = nB;
		d.ts = new long[nB];
		d.hi = new double[nB];
		d.lo = new double[nB];
		d.hiMax = new double[nB];
		d.loMin = new double[nB];
		d.vol = new long[nB];
		d.volHi = new long[nB];
		d.volLo = new long[nB];
		d.paid = new boolean[nB];
		d.tMin = s.ts[0];
		d.tMax = s.ts[len - 1];
		final double span = Math.max(1, d.tMax - d.tMin);

		final double[] wHi = new double[nB];
		final double[] wLo = new double[nB];
		for (int i = 0; i < len; i++)
		{
			final int b = (int) Math.min(nB - 1, (s.ts[i] - d.tMin) / span * nB);
			final long hv = s.hvol != null ? Math.max(s.hvol[i], 0) : 0;
			final long lv = s.lvol != null ? Math.max(s.lvol[i], 0) : 0;
			if (s.high[i] > 0)
			{
				final double w = hv + 1;
				d.hi[b] += s.high[i] * w;
				wHi[b] += w;
				d.hiMax[b] = Math.max(d.hiMax[b], s.high[i]);
			}
			if (s.low[i] > 0)
			{
				final double w = lv + 1;
				d.lo[b] += s.low[i] * w;
				wLo[b] += w;
				d.loMin[b] = d.loMin[b] == 0 ? s.low[i] : Math.min(d.loMin[b], s.low[i]);
			}
			d.vol[b] += hv + lv;
			d.volHi[b] += hv;
			d.volLo[b] += lv;
		}
		for (int b = 0; b < nB; b++)
		{
			d.ts[b] = d.tMin + (long) ((b + 0.5) / nB * span);
			d.hi[b] = wHi[b] > 0 ? d.hi[b] / wHi[b] : 0;
			d.lo[b] = wLo[b] > 0 ? d.lo[b] / wLo[b] : 0;
			d.paid[b] = d.hi[b] > 0 && d.lo[b] > 0
				&& GeTax.net((long) d.lo[b] + 1, (long) d.hi[b] - 1) > 0;
			d.volMax = Math.max(d.volMax, d.vol[b]);
		}
		for (int i = len - 1; i >= 0; i--)
		{
			if (d.lastHigh == 0 && s.high[i] > 0)
			{
				d.lastHigh = s.high[i];
				d.lastHighTs = s.ts[i];
			}
			if (d.lastLow == 0 && s.low[i] > 0)
			{
				d.lastLow = s.low[i];
				d.lastLowTs = s.ts[i];
			}
			if (d.lastHigh != 0 && d.lastLow != 0)
			{
				break;
			}
		}

		// Robust scale: 2nd..98th percentile of drawn values, then widened to
		// admit the caller's price lines. One spiked window cannot crush the
		// whole day into a ribbon.
		final double[] vals = new double[nB * 2];
		int nv = 0;
		for (int b = 0; b < nB; b++)
		{
			if (d.hi[b] > 0) { vals[nv++] = d.hi[b]; }
			if (d.lo[b] > 0) { vals[nv++] = d.lo[b]; }
		}
		if (nv < 2)
		{
			return null;
		}
		final double[] used = Arrays.copyOf(vals, nv);
		Arrays.sort(used);
		long lo = (long) used[(int) (nv * 0.02)];
		long hi = (long) used[Math.min(nv - 1, (int) (nv * 0.98))];
		for (final long p : extraPrices)
		{
			if (p > 0)
			{
				lo = Math.min(lo, p);
				hi = Math.max(hi, p);
			}
		}
		if (lo >= hi)
		{
			lo = hi - 1;
		}
		final long pad = Math.max(1, (hi - lo) / 10);
		d.pMin = lo - pad;
		d.pMax = hi + pad;
		return d;
	}

	static float x(Display d, long ts, int x0, int w)
	{
		return x0 + (ts - d.tMin) / (float) Math.max(1, d.tMax - d.tMin) * w;
	}

	static float y(Display d, double price, int y0, int h)
	{
		final double f = (price - d.pMin) / (double) Math.max(1, d.pMax - d.pMin);
		return (float) (y0 + h - Math.max(0, Math.min(1, f)) * h);
	}

	/** The corridor, wick envelope, and edge lines. */
	static void paintCorridor(Graphics2D g2, Display d, int x0, int y0, int w, int h)
	{
		// Wick envelope first, faintest layer.
		for (int b = 0; b < d.n - 1; b++)
		{
			if (!traded(d, b) || !traded(d, b + 1))
			{
				continue;
			}
			final Path2D env = new Path2D.Float();
			env.moveTo(x(d, d.ts[b], x0, w), y(d, d.hiMax[b], y0, h));
			env.lineTo(x(d, d.ts[b + 1], x0, w), y(d, d.hiMax[b + 1], y0, h));
			env.lineTo(x(d, d.ts[b + 1], x0, w), y(d, d.loMin[b + 1], y0, h));
			env.lineTo(x(d, d.ts[b], x0, w), y(d, d.loMin[b], y0, h));
			env.closePath();
			g2.setColor(ENVELOPE);
			g2.fill(env);
		}
		// The corridor between the weighted averages, gold where it paid.
		for (int b = 0; b < d.n - 1; b++)
		{
			if (!traded(d, b) || !traded(d, b + 1))
			{
				continue;
			}
			final Path2D p = new Path2D.Float();
			p.moveTo(x(d, d.ts[b], x0, w), y(d, d.hi[b], y0, h));
			p.lineTo(x(d, d.ts[b + 1], x0, w), y(d, d.hi[b + 1], y0, h));
			p.lineTo(x(d, d.ts[b + 1], x0, w), y(d, d.lo[b + 1], y0, h));
			p.lineTo(x(d, d.ts[b], x0, w), y(d, d.lo[b], y0, h));
			p.closePath();
			g2.setColor(d.paid[b] && d.paid[b + 1] ? CORRIDOR_PAID : CORRIDOR);
			g2.fill(p);
		}
		g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		paintEdge(g2, d, true, LINE_HIGH, x0, y0, w, h);
		paintEdge(g2, d, false, LINE_LOW, x0, y0, w, h);
	}

	private static boolean traded(Display d, int b)
	{
		return d.hi[b] > 0 && d.lo[b] > 0;
	}

	private static void paintEdge(Graphics2D g2, Display d, boolean high, Color c, int x0, int y0, int w, int h)
	{
		g2.setColor(c);
		Path2D p = null;
		for (int b = 0; b < d.n; b++)
		{
			final double v = high ? d.hi[b] : d.lo[b];
			if (v <= 0)
			{
				if (p != null) { g2.draw(p); p = null; }
				continue;
			}
			final float px = x(d, d.ts[b], x0, w);
			final float py = y(d, v, y0, h);
			if (p == null) { p = new Path2D.Float(); p.moveTo(px, py); }
			else { p.lineTo(px, py); }
		}
		if (p != null)
		{
			g2.draw(p);
		}
	}

	/** Three labeled price gridlines in the right gutter. */
	static void paintPriceGrid(Graphics2D g2, Display d, FontMetrics fm, int x0, int y0, int w, int h, Color labelColor)
	{
		for (int i = 0; i < 3; i++)
		{
			// i: 0 = bottom, 1 = middle, 2 = top of the range.
			final long p = d.pMin + (d.pMax - d.pMin) * i / 2;
			final int yy = Math.round(y(d, p, y0, h));
			g2.setColor(GRID);
			g2.drawLine(x0, yy, x0 + w, yy);
			g2.setColor(labelColor);
			// Clamp so the top label never clips above the chart.
			g2.drawString(Fmt.compact(p), x0 + w + 4, Math.max(y0 + fm.getAscent() - 2, yy + 4));
		}
	}

	/** Per-bucket traded-volume strip: brightness is relative volume, colour is
	 * who was aggressing. Green = insta-buys dominated the bucket, red =
	 * insta-sells did, gold = balanced. The day's pressure reads left to right. */
	static void paintFillStrip(Graphics2D g2, Display d, int x0, int y, int w, int cellH)
	{
		if (d.volMax <= 0)
		{
			return;
		}
		final float bw = w / (float) d.n;
		for (int b = 0; b < d.n; b++)
		{
			if (d.vol[b] <= 0)
			{
				continue;
			}
			final Color base = d.volHi[b] > d.volLo[b] * 1.5 ? FILL_BUY
				: d.volLo[b] > d.volHi[b] * 1.5 ? FILL_SELL : FILL_CELL;
			final int a = 40 + (int) (115 * Math.min(1.0, d.vol[b] / (double) d.volMax));
			g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), a));
			g2.fillRect(Math.round(x0 + b * bw), y, Math.max(1, Math.round(bw) - 1), cellH);
		}
	}

	/** Dotted full-width guides at the live market edges (the newest print on
	 * each side), so the whole day reads against where the market is NOW. */
	static void paintLevelGuides(Graphics2D g2, Display d, int x0, int y0, int w, int h)
	{
		final java.awt.Stroke prev = g2.getStroke();
		g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{1f, 3f}, 0f));
		if (d.lastHigh > d.pMin && d.lastHigh < d.pMax)
		{
			final int yy = Math.round(y(d, d.lastHigh, y0, h));
			g2.setColor(GUIDE_HIGH);
			g2.drawLine(x0, yy, x0 + w, yy);
		}
		if (d.lastLow > d.pMin && d.lastLow < d.pMax)
		{
			final int yy = Math.round(y(d, d.lastLow, y0, h));
			g2.setColor(GUIDE_LOW);
			g2.drawLine(x0, yy, x0 + w, yy);
		}
		g2.setStroke(prev);
	}

	/** Vertical rhythm lines at the six-hour UTC boundaries: time structure
	 * without labels, for surfaces too tight to carry an hour axis. */
	static void paintTimeGrid(Graphics2D g2, Display d, int x0, int y0, int w, int h)
	{
		g2.setColor(GRID);
		for (long t = ((d.tMin / 21600) + 1) * 21600; t < d.tMax; t += 21600)
		{
			final int xx = Math.round(x(d, t, x0, w));
			g2.drawLine(xx, y0, xx, y0 + h);
		}
	}
}

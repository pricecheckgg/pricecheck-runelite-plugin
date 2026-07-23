package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Dev-only design mockup: the GE item card re-skinned in a Bloomberg-terminal
 * language (amber-on-black, monospace tabular figures, a dense quote grid, a
 * time-and-sales tape, tick-flash). Standalone (no plugin painter dep) so the
 * real GeItemInfoPainter stays untouched until this look is approved.
 * Args: [outputPath]. Never shipped (test source set).
 */
public final class BloombergCardPreview
{
	private static final Color BG      = new Color(0x08, 0x08, 0x0a);
	private static final Color PANEL   = new Color(0x0d, 0x0d, 0x10);
	private static final Color BORDER  = new Color(0x39, 0x33, 0x1c);
	private static final Color AMBER   = new Color(0xe8, 0xb8, 0x4b);
	private static final Color AMBERHI = new Color(0xf6, 0xc9, 0x52);
	private static final Color LABEL   = new Color(0x8f, 0x88, 0x66);
	private static final Color DIM     = new Color(0x55, 0x51, 0x3d);
	private static final Color GREEN   = new Color(0x55, 0xc4, 0x6a);
	private static final Color RED     = new Color(0xf0, 0x56, 0x3f);
	private static final Color GRID    = new Color(0x1b, 0x1b, 0x14);
	private static final Color FLASH   = new Color(0x1e, 0x3a, 0x22);

	private BloombergCardPreview() { }

	private static Font mono(int s)  { return new Font("Monospaced", Font.PLAIN, s); }
	private static Font monoB(int s) { return new Font("Monospaced", Font.BOLD, s); }

	// right-align a string so it ENDS at xr
	private static void rt(Graphics2D g, String s, int xr, int y)
	{
		g.drawString(s, xr - g.getFontMetrics().stringWidth(s), y);
	}

	// A quote-grid cell: dim uppercase label on top, value below (right-aligned to the column).
	private static void cell(Graphics2D g, int x, int w, int y, String label, String value, Color vc)
	{
		g.setFont(mono(9));
		g.setColor(LABEL);
		g.drawString(label, x, y);
		g.setFont(monoB(13));
		g.setColor(vc);
		rt(g, value, x + w, y + 15);
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/bbg.png";
		final int W = 476, H = 660, S = 2;
		final BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(S, S);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.setColor(BG);
		g.fillRect(0, 0, W, H);
		g.setColor(PANEL);
		g.fillRect(6, 6, W - 12, H - 12);
		g.setColor(BORDER);
		g.setStroke(new BasicStroke(1f));
		g.drawRect(6, 6, W - 12, H - 12);

		final int L = 18, R = W - 18;
		int y = 26;

		// ── 1 · STATUS HEADER ──
		g.setFont(monoB(13));
		g.setColor(AMBERHI);
		g.drawString("TUMEKEN'S SHADOW (UNCHARGED)", L, y);
		g.setFont(mono(10));
		g.setColor(DIM);
		g.drawString("27277", L + 268, y);
		g.setFont(mono(10));
		final String liveStr = "LIVE 1.5s   20:14:07";
		final int liveW = g.getFontMetrics().stringWidth(liveStr);
		g.setColor(GREEN);
		g.fillOval(R - liveW - 13, y - 8, 6, 6);
		g.setColor(LABEL);
		g.drawString(liveStr, R - liveW, y);
		y += 8;
		g.setColor(GRID);
		g.drawLine(L, y, R, y);
		y += 16;

		// ── 2 · QUOTE GRID ── three columns
		final int cw = (R - L - 16) / 3, c0 = L, c1 = L + cw + 8, c2 = L + 2 * cw + 16;
		final int rowH = 30;
		cell(g, c0, cw, y, "BID",   "1,022,500", AMBER);
		cell(g, c1, cw, y, "ASK",   "1,050,000", AMBER);
		cell(g, c2, cw, y, "SPRD",  "27,500", AMBER);
		y += rowH;
		cell(g, c0, cw, y, "NET/EA", "+6,500", GREEN);
		cell(g, c1, cw, y, "ROI",    "+0.64%", GREEN);
		cell(g, c2, cw, y, "TAX",    "21,000", DIM.brighter());
		y += rowH;
		cell(g, c0, cw, y, "Δ1H",  "+0.8%", GREEN);
		cell(g, c1, cw, y, "Δ24H", "+3.4%", GREEN);
		cell(g, c2, cw, y, "VOL 24H",  "1,240", AMBER);
		y += rowH;
		cell(g, c0, cw, y, "HI",     "1,072,000", AMBER);
		cell(g, c1, cw, y, "LO",     "1,012,000", AMBER);
		cell(g, c2, cw, y, "OFI",    "+62% SELL", RED);
		y += rowH;
		cell(g, c0, cw, y, "LIMIT",  "4 / 8", AMBER);
		cell(g, c1, cw, y, "RESET",  "2h14m", AMBER);
		cell(g, c2, cw, y, "FILL",   "~6m", AMBER);
		y += rowH - 4;

		// verdict cells (two, full width)
		g.setFont(monoB(12));
		g.setColor(new Color(0x2a, 0x1e, 0x14));
		g.fillRect(L, y, (R - L) / 2 - 4, 20);
		g.fillRect(L + (R - L) / 2 + 4, y, (R - L) / 2 - 4, 20);
		g.setColor(AMBER);
		g.drawString("B  WAIT", L + 8, y + 14);
		g.setColor(GREEN);
		g.drawString("S  OK  +6.5k", L + (R - L) / 2 + 12, y + 14);
		y += 30;

		// ── 3 · CHART (band, amber grid) ──
		final int cx = L, cy = y, cW = R - L, cH = 96;
		g.setColor(GRID);
		for (int i = 0; i <= 3; i++)
		{
			int gy = cy + i * cH / 3;
			g.drawLine(cx, gy, cx + cW - 46, gy);
		}
		// price axis labels
		g.setFont(mono(9));
		g.setColor(LABEL);
		final String[] px = { "1.07m", "1.05m", "1.03m", "1.01m" };
		for (int i = 0; i < 4; i++)
		{
			g.drawString(px[i], cx + cW - 40, cy + i * cH / 3 + 3);
		}
		// series
		final int n = 60;
		final GeneralPath hi = new GeneralPath(), lo = new GeneralPath();
		final double loP = 1_012_000, hiP = 1_072_000;
		int lastHx = 0, lastHy = 0, lastLy = 0;
		for (int i = 0; i < n; i++)
		{
			double wave = Math.sin(i / 9.0) * 6500;
			double mid = 1_020_000 + i * 620 + wave;
			double sp = 22_000 + Math.abs(wave);
			double h = mid + sp / 2, lo2 = mid - sp / 2;
			int xx = cx + (int) (i * (cW - 48.0) / (n - 1));
			int hy = cy + (int) ((hiP - h) / (hiP - loP) * cH);
			int ly = cy + (int) ((hiP - lo2) / (hiP - loP) * cH);
			if (i == 0) { hi.moveTo(xx, hy); lo.moveTo(xx, ly); }
			else { hi.lineTo(xx, hy); lo.lineTo(xx, ly); }
			lastHx = xx; lastHy = hy; lastLy = ly;
		}
		// amber fill between
		final GeneralPath fill = new GeneralPath(hi);
		fill.lineTo(lastHx, lastLy);
		final double[] rev = new double[0];
		// build reverse of lo by re-walking
		final GeneralPath band = new GeneralPath();
		band.append(hi, false);
		for (int i = n - 1; i >= 0; i--)
		{
			double wave = Math.sin(i / 9.0) * 6500;
			double mid = 1_020_000 + i * 620 + wave;
			double sp = 22_000 + Math.abs(wave);
			double lo2 = mid - sp / 2;
			int xx = cx + (int) (i * (cW - 48.0) / (n - 1));
			int ly = cy + (int) ((hiP - lo2) / (hiP - loP) * cH);
			band.lineTo(xx, ly);
		}
		band.closePath();
		g.setColor(new Color(0x2a, 0x24, 0x12));
		g.fill(band);
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(GREEN);
		g.draw(hi);
		g.setColor(RED);
		g.draw(lo);
		// your-sell dashed line
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
		g.setColor(AMBER);
		int syY = cy + (int) ((hiP - 1_050_000) / (hiP - loP) * cH);
		g.drawLine(cx, syY, cx + cW - 48, syY);
		g.setStroke(new BasicStroke(1f));
		g.setColor(AMBERHI);
		g.fillOval(lastHx - 3, lastHy - 3, 6, 6);
		y = cy + cH + 6;

		// timeframe function keys
		final String[] keys = { "LATEST", "24H", "7D" };
		int kx = L;
		for (int i = 0; i < keys.length; i++)
		{
			g.setFont(monoB(10));
			int kw = g.getFontMetrics().stringWidth(keys[i]) + 14;
			g.setColor(i == 0 ? new Color(0x3a, 0x2c, 0x10) : PANEL);
			g.fillRect(kx, y, kw, 16);
			g.setColor(BORDER);
			g.drawRect(kx, y, kw, 16);
			g.setColor(i == 0 ? AMBERHI : LABEL);
			g.drawString(keys[i], kx + 7, y + 12);
			kx += kw + 6;
		}
		g.setFont(mono(9));
		g.setColor(DIM);
		rt(g, "SPREAD 24H", R, y + 12);
		y += 26;

		// ── 4 · VOLUME + PRESSURE ──
		g.setColor(GRID);
		g.drawLine(L, y + 22, R, y + 22);
		for (int i = 0; i < 58; i++)
		{
			int bh = 3 + (int) (Math.abs(Math.sin(i / 4.0) * 18) + (i % 3) * 2);
			int bx = L + i * ((R - L) / 58);
			g.setColor(i % 2 == 0 ? new Color(0x6a, 0x55, 0x22) : new Color(0x4a, 0x3a, 0x18));
			g.fillRect(bx, y + 22 - bh, 2, bh);
		}
		g.setFont(mono(9));
		g.setColor(LABEL);
		g.drawString("VOL 24H", L, y + 34);
		y += 40;
		// pressure/OFI bar
		int pbW = R - L - 96;
		g.setColor(GREEN);
		g.fillRect(L, y, (int) (pbW * 0.38), 8);
		g.setColor(RED);
		g.fillRect(L + (int) (pbW * 0.38), y, pbW - (int) (pbW * 0.38), 8);
		g.setFont(monoB(10));
		g.setColor(RED);
		rt(g, "OFI +62% SELL", R, y + 8);
		y += 22;

		// ── 5 · TIME & SALES TAPE ──
		// ▲/▼ = a market print (buy-side / sell-side). ◆ (gold) = one of YOUR
		// fills, labelled past-tense with qty so it reads as history, not advice.
		g.setColor(GRID);
		g.drawLine(L, y - 4, R, y - 4);
		g.setFont(mono(9));
		g.setColor(LABEL);
		g.drawString("TIME & SALES", L, y + 7);
		g.setColor(GREEN); rt(g, "▲5", R - 26, y + 7);
		g.setColor(RED);   rt(g, "▼4", R, y + 7);
		y += 16;
		// column sub-header anchors the numbers
		g.setFont(mono(8));
		g.setColor(DIM);
		rt(g, "PRICE", L + 118, y);
		g.drawString("Δ VS YOU", L + 134, y);
		rt(g, "AGE", R, y);
		y += 20;
		final String[][] tape = {
			// marker, price, tag, age, flag(0 market,1 you,2 hi,3 lo)
			{ "▲", "1,049,900", "+2.4k",         "12s", "0" },
			{ "◆", "1,048,200", "YOU SOLD ×1",   "1m",  "1" },
			{ "▼", "1,046,500", "-3.4k",         "2m",  "0" },
			{ "▲", "1,050,600", "day high",      "26m", "2" },
			{ "▼", "1,039,800", "day low",       "41m", "3" },
			{ "▲", "1,043,500", "-7.0k",         "55m", "0" },
			{ "◆", "1,041,000", "YOU BOUGHT ×1", "1h",  "1" },
			{ "▲", "1,047,200", "-3.3k",         "1h",  "0" },
		};
		for (int i = 0; i < tape.length; i++)
		{
			final int ry = y + i * 17;
			final String flag = tape[i][4];
			final boolean you = flag.equals("1");
			final boolean up = tape[i][0].equals("▲");
			if (i == 0 && !you) { g.setColor(FLASH); g.fillRect(L - 2, ry - 11, R - L + 4, 15); } // tick-flash on freshest print
			final Color side = you ? AMBERHI : (up ? GREEN : RED);
			g.setFont(monoB(11));
			g.setColor(side);
			g.drawString(tape[i][0], L, ry);
			g.setFont(monoB(12));
			g.setColor(side);
			rt(g, tape[i][1], L + 118, ry);
			g.setFont(mono(11));
			g.setColor(you ? AMBER : (flag.equals("2") ? GREEN : flag.equals("3") ? RED : LABEL));
			g.drawString(tape[i][2], L + 134, ry);
			g.setColor(DIM);
			rt(g, tape[i][3], R, ry);
		}
		y += tape.length * 17 + 8;

		// ── 6 · POSITION BLOTTER LINE ──
		g.setColor(GRID);
		g.drawLine(L, y - 6, R, y - 6);
		g.setFont(mono(10));
		g.setColor(LABEL);
		g.drawString("POS", L, y + 6);
		g.setFont(monoB(11));
		g.setColor(AMBER);
		g.drawString("4 @ 1.021m", L + 30, y + 6);
		g.setColor(LABEL);  g.setFont(mono(10)); g.drawString("MKT", L + 150, y + 6);
		g.setColor(AMBER);  g.setFont(monoB(11)); g.drawString("1.045m", L + 182, y + 6);
		g.setColor(LABEL);  g.setFont(mono(10)); g.drawString("uP&L", L + 250, y + 6);
		g.setColor(GREEN);  g.setFont(monoB(11)); g.drawString("+96.0k", L + 288, y + 6);
		g.setColor(GREEN);  g.setFont(monoB(11)); rt(g, "IF SOLD +26.25k", R, y + 6);
		y += 20;

		// ── 7 · FOOTER (F-keys) ──
		g.setColor(GRID);
		g.drawLine(L, y - 4, R, y - 4);
		g.setFont(mono(9));
		g.setColor(DIM);
		g.drawString("FILL 71% OF 4H WINDOWS", L, y + 8);
		g.setColor(LABEL);
		rt(g, "F1 CHART  F2 TAPE  F3 ALERT", R, y + 8);

		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

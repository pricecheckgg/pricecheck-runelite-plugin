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
 * Dev-only design mockup: the WHOLE Grand Exchange screen re-skinned as a
 * Bloomberg "trading desk" - the GE window in the centre, terminal panels docked
 * on every side (status bar, opportunity radar, held-watch strip, positions
 * blotter, session/flow strip, ticker). Uses the owner's live 4-offer scene.
 * Standalone (no plugin dep). Args: [outputPath]. Never shipped (test source).
 */
public final class BloombergDeskPreview
{
	private static final Color BG      = new Color(0x06, 0x06, 0x08);
	private static final Color PANEL   = new Color(0x0c, 0x0c, 0x0f);
	private static final Color TITLEBG = new Color(0x16, 0x13, 0x0a);
	private static final Color BORDER  = new Color(0x3a, 0x33, 0x1c);
	private static final Color AMBER   = new Color(0xe8, 0xb8, 0x4b);
	private static final Color AMBERHI = new Color(0xf6, 0xc9, 0x52);
	private static final Color LABEL   = new Color(0x93, 0x8b, 0x68);
	private static final Color DIM     = new Color(0x59, 0x54, 0x3f);
	private static final Color GREEN   = new Color(0x55, 0xc4, 0x6a);
	private static final Color RED     = new Color(0xf0, 0x56, 0x3f);
	private static final Color GRID    = new Color(0x1c, 0x1b, 0x13);
	private static final Color FLASH   = new Color(0x1e, 0x3a, 0x22);

	private BloombergDeskPreview() { }

	private static Font mono(int s)  { return new Font("Monospaced", Font.PLAIN, s); }
	private static Font monoB(int s) { return new Font("Monospaced", Font.BOLD, s); }

	private static void rt(Graphics2D g, String s, int xr, int y)
	{
		g.drawString(s, xr - g.getFontMetrics().stringWidth(s), y);
	}

	// Panel frame + amber title strip. Returns the y where content should start.
	private static int panel(Graphics2D g, int x, int y, int w, int h, String title)
	{
		g.setColor(PANEL);
		g.fillRect(x, y, w, h);
		g.setColor(BORDER);
		g.setStroke(new BasicStroke(1f));
		g.drawRect(x, y, w, h);
		g.setColor(TITLEBG);
		g.fillRect(x + 1, y + 1, w - 2, 17);
		g.setFont(monoB(10));
		g.setColor(AMBERHI);
		g.drawString(title, x + 8, y + 13);
		g.setColor(GRID);
		g.drawLine(x + 1, y + 18, x + w - 1, y + 18);
		return y + 32;
	}

	private static void spark(Graphics2D g, int x, int y, int w, int h, int seed, Color c)
	{
		final GeneralPath p = new GeneralPath();
		for (int i = 0; i <= 16; i++)
		{
			double v = Math.sin((i + seed) / 2.3) * 0.5 + Math.sin((i + seed) / 5.7) * 0.5;
			int px = x + i * w / 16;
			int py = y + (int) ((0.5 - v / 2) * h);
			if (i == 0) { p.moveTo(px, py); } else { p.lineTo(px, py); }
		}
		g.setStroke(new BasicStroke(1.2f));
		g.setColor(c);
		g.draw(p);
	}

	private static void chip(Graphics2D g, int x, int y, String s, Color fg)
	{
		g.setFont(monoB(10));
		int w = g.getFontMetrics().stringWidth(s) + 10;
		g.setColor(new Color(0x20, 0x1a, 0x10));
		g.fillRect(x, y - 10, w, 14);
		g.setColor(fg);
		g.drawString(s, x + 5, y);
	}

	public static void main(String[] args) throws Exception
	{
		System.setProperty("java.awt.headless", "true");
		final String out = args.length > 0 ? args[0] : "/tmp/desk.png";
		final int W = 1440, H = 902, S = 2;
		final BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(S, S);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(BG);
		g.fillRect(0, 0, W, H);

		// ══ TOP STATUS / COMMAND BAR ══
		{
			int x = 8, y = 8, w = W - 16, h = 38;
			g.setColor(PANEL); g.fillRect(x, y, w, h);
			g.setColor(BORDER); g.drawRect(x, y, w, h);
			g.setFont(monoB(15)); g.setColor(AMBERHI);
			g.drawString("PRICECHECK", x + 14, y + 25);
			g.setFont(mono(11)); g.setColor(DIM);
			g.drawString("ENGINE DESK", x + 132, y + 25);
			// field group
			final String[][] f = {
				{ "CASH", "4,266,644,991" }, { "SLOTS", "4/8" }, { "WORLD", "302" },
				{ "P&L TODAY", "+48.7m" }, { "LIVE", "1.5s" },
			};
			int fx = x + 250;
			for (String[] c : f)
			{
				g.setFont(mono(9)); g.setColor(LABEL); g.drawString(c[0], fx, y + 15);
				g.setFont(monoB(13));
				g.setColor(c[0].equals("P&L TODAY") ? GREEN : AMBER);
				g.drawString(c[1], fx, y + 29);
				fx += Math.max(g.getFontMetrics().stringWidth(c[1]), 60) + 34;
			}
			g.setColor(GREEN); g.fillOval(x + w - 470, y + 15, 7, 7);
			g.setFont(mono(11)); g.setColor(LABEL);
			g.drawString("LIVE  20:14:07", x + w - 456, y + 24);
			g.setFont(mono(10)); g.setColor(DIM);
			rt(g, "F1 DESK   F2 RADAR   F3 BLOTTER   F4 CARD   F5 ALERTS", x + w - 14, y + 24);
		}

		final int topY = 52, botY = 854;
		final int lX = 8, lW = 372;
		final int rX = W - 8 - 372, rW = 372;
		final int cX = lX + lW + 8, cW = rX - cX - 8;

		// ══ LEFT · OPPORTUNITY RADAR + MOVERS ══
		{
			int y0 = panel(g, lX, topY, lW, 306, "OPPORTUNITY RADAR  ·  TOP EV/HR");
			g.setFont(mono(8)); g.setColor(DIM);
			g.drawString("ITEM", lX + 10, y0 - 4);
			rt(g, "MARGIN", lX + 210, y0 - 4);
			rt(g, "EV/HR", lX + 285, y0 - 4);
			g.drawString("FLOW", lX + 300, y0 - 4);
			final String[][] radar = {
				{ "Dragon claws",   "+429k",  "1.9m",  "u" },
				{ "Twisted bow",    "+2.10m", "1.6m",  "u" },
				{ "Masori body",    "+812k",  "1.2m",  "f" },
				{ "Torva platebody", "+1.44m", "1.1m", "d" },
				{ "Avernic defender", "+190k", "980k", "u" },
				{ "Zaryte crossbow", "+1.02m", "910k", "u" },
				{ "Voidwaker",      "+640k",  "870k",  "f" },
				{ "Primordial boots", "+118k", "760k", "u" },
				{ "Ancestral hat",  "+540k",  "690k",  "d" },
				{ "Sanguinesti staff", "+930k", "640k", "u" },
				{ "Bandos chestplate", "+96k", "590k", "f" },
				{ "Elder maul",     "+305k",  "540k",  "u" },
			};
			for (int i = 0; i < radar.length; i++)
			{
				int ry = y0 + 14 + i * 21;
				g.setFont(monoB(11)); g.setColor(AMBER);
				g.drawString(radar[i][0], lX + 10, ry);
				g.setFont(monoB(11)); g.setColor(GREEN);
				rt(g, radar[i][1], lX + 210, ry);
				g.setColor(AMBERHI);
				rt(g, radar[i][2], lX + 285, ry);
				String fl = radar[i][3];
				g.setFont(monoB(11));
				g.setColor(fl.equals("u") ? GREEN : fl.equals("d") ? RED : DIM);
				g.drawString(fl.equals("u") ? "▲" : fl.equals("d") ? "▼" : "•", lX + 296, ry);
				spark(g, lX + 314, ry - 9, 48, 10, i * 3, fl.equals("d") ? RED : GREEN);
				g.setColor(GRID); g.drawLine(lX + 8, ry + 6, lX + lW - 8, ry + 6);
			}
			// FRESH DIPS sub-panel
			int my = topY + 314;
			int m0 = panel(g, lX, my, lW, 152, "FRESH DIPS  ·  DUMP CATCHER");
			final String[][] dips = {
				{ "Sanguinesti staff", "-4.1%", "12m vol" },
				{ "Kodai wand",     "-3.6%",  "8m vol" },
				{ "Harmonised orb", "-2.9%",  "5m vol" },
				{ "Dinh's bulwark", "-2.4%",  "3m vol" },
			};
			for (int i = 0; i < dips.length; i++)
			{
				int ry = m0 + 6 + i * 22;
				g.setFont(monoB(11)); g.setColor(RED); g.drawString("▼", lX + 10, ry);
				g.setColor(AMBER); g.drawString(dips[i][0], lX + 28, ry);
				g.setColor(RED); rt(g, dips[i][1], lX + 300, ry);
				g.setFont(mono(9)); g.setColor(DIM); rt(g, dips[i][2], lX + lW - 10, ry);
			}
			// TOP MOVERS (gainers) - fills the rest of the column
			int gy = my + 160;
			int g0 = panel(g, lX, gy, lW, botY - gy - 2, "TOP MOVERS  ·  GAINERS");
			final String[][] gains = {
				{ "Zaryte crossbow", "+3.2%" }, { "Masori mask", "+2.7%" },
				{ "Voidwaker", "+2.1%" }, { "Ancestral robe top", "+1.8%" },
				{ "Ultor ring", "+1.5%" }, { "Berserker ring (i)", "+1.1%" },
				{ "Osmumten's fang", "+0.9%" }, { "Ghrazi rapier", "+0.6%" },
			};
			for (int i = 0; i < gains.length; i++)
			{
				int ry = g0 + 6 + i * 22;
				g.setFont(monoB(11)); g.setColor(GREEN); g.drawString("▲", lX + 10, ry);
				g.setColor(AMBER); g.drawString(gains[i][0], lX + 28, ry);
				g.setColor(GREEN); rt(g, gains[i][1], lX + lW - 10, ry);
				g.setColor(GRID); g.drawLine(lX + 8, ry + 6, lX + lW - 8, ry + 6);
			}
		}

		// ══ CENTRE TOP · HELD WATCH STRIP ══
		final int wStripH = 150;
		{
			int y0 = panel(g, cX, topY, cW, wStripH, "HELD  ·  YOUR ACTIVE POSITIONS");
			final String[][] held = {
				{ "Tumeken's shadow", "×2", "-15.2m", "HOLD", "a", "24m" },
				{ "Scythe of vitur",  "×1", "-3.49m", "OK",   "g", "16m" },
				{ "Tumeken's shadow", "×1", "-12.2m", "HOLD", "a", "24m" },
				{ "Elysian spirit shield", "×1", "+2.82m", "OK", "g", "29m" },
			};
			int cardW = (cW - 8 * 4) / 4;
			for (int i = 0; i < held.length; i++)
			{
				int hx = cX + 8 + i * (cardW + 8);
				g.setColor(new Color(0x10, 0x10, 0x13)); g.fillRect(hx, y0, cardW, wStripH - 42);
				g.setColor(GRID); g.drawRect(hx, y0, cardW, wStripH - 42);
				g.setFont(monoB(10)); g.setColor(AMBERHI);
				g.drawString(held[i][0].length() > 18 ? held[i][0].substring(0, 17) + "." : held[i][0], hx + 6, y0 + 14);
				g.setFont(mono(9)); g.setColor(LABEL); g.drawString(held[i][1] + " SELL", hx + 6, y0 + 27);
				boolean up = held[i][2].startsWith("+");
				g.setFont(monoB(13)); g.setColor(up ? GREEN : RED);
				rt(g, held[i][2], hx + cardW - 6, y0 + 27);
				spark(g, hx + 6, y0 + 34, cardW - 12, 34, i * 5 + 1, up ? GREEN : RED);
				chip(g, hx + 6, y0 + wStripH - 50, held[i][3], held[i][4].equals("g") ? GREEN : AMBER);
				g.setFont(mono(9)); g.setColor(DIM); rt(g, held[i][5], hx + cardW - 6, y0 + wStripH - 52);
			}
		}

		// ══ CENTRE · GRAND EXCHANGE (the live game window) ══
		final int geY = topY + wStripH + 8, geH = 470;
		{
			g.setColor(new Color(0x0a, 0x0a, 0x0c)); g.fillRect(cX, geY, cW, geH);
			g.setColor(BORDER); g.drawRect(cX, geY, cW, geH);
			g.setColor(TITLEBG); g.fillRect(cX + 1, geY + 1, cW - 2, 20);
			g.setFont(monoB(12)); g.setColor(AMBERHI);
			g.drawString("GRAND EXCHANGE", cX + 10, geY + 15);
			g.setFont(mono(10)); g.setColor(LABEL);
			rt(g, "4,266,644,991 gp  ·  the live game window", cX + cW - 10, geY + 15);
			// 8 slots (2 rows x 4)
			final String[][] slots = {
				{ "Tumeken's shadow", "SELL ×2", "HOLD", "a" },
				{ "Scythe of vitur", "SELL ×1", "OK -3.49m", "g" },
				{ "Tumeken's shadow", "SELL ×1", "HOLD", "a" },
				{ "Elysian spirit shield", "SELL ×1", "OK +2.82m", "g" },
			};
			int sw = (cW - 20 - 3 * 10) / 4, sh = 150;
			int sy = geY + 40;
			for (int i = 0; i < 4; i++)
			{
				int sx = cX + 10 + i * (sw + 10);
				g.setColor(new Color(0x13, 0x11, 0x0c)); g.fillRect(sx, sy, sw, sh);
				g.setColor(slots[i][3].equals("g") ? new Color(0x2a, 0x50, 0x30) : new Color(0x50, 0x44, 0x22));
				g.drawRect(sx, sy, sw, sh);
				g.setFont(monoB(11)); g.setColor(AMBERHI); g.drawString("SELL", sx + 8, sy + 16);
				g.setFont(mono(9)); g.setColor(AMBER);
				g.drawString(slots[i][0].length() > 15 ? slots[i][0].substring(0, 14) + "." : slots[i][0], sx + 8, sy + 34);
				g.setColor(LABEL); g.drawString(slots[i][1], sx + 8, sy + 48);
				// progress bar
				g.setColor(GRID); g.fillRect(sx + 8, sy + sh - 40, sw - 16, 6);
				g.setColor(slots[i][3].equals("g") ? GREEN : AMBER);
				g.fillRect(sx + 8, sy + sh - 40, (int) ((sw - 16) * 0.35), 6);
				chip(g, sx + 8, sy + sh - 14, slots[i][2], slots[i][3].equals("g") ? GREEN : AMBER);
			}
			int ey = sy + sh + 12;
			g.setFont(mono(10)); g.setColor(DIM);
			for (int i = 0; i < 4; i++)
			{
				int sx = cX + 10 + i * (sw + 10);
				g.setColor(new Color(0x0e, 0x0e, 0x10)); g.fillRect(sx, ey, sw, 90);
				g.setColor(GRID); g.drawRect(sx, ey, sw, 90);
				g.setColor(DIM); g.setFont(monoB(11));
				String em = "EMPTY";
				rt(g, em, sx + sw / 2 + g.getFontMetrics().stringWidth(em) / 2, ey + 50);
			}
			int gfy = geY + geH - 14;
			g.setColor(GRID); g.drawLine(cX + 10, gfy - 12, cX + cW - 10, gfy - 12);
			g.setFont(mono(10)); g.setColor(DIM);
			g.drawString("4 ACTIVE  ·  4 FREE  ·  click a slot for the full DES card", cX + 12, gfy);
			g.setColor(AMBER); rt(g, "AVG DRIFT 1.5%", cX + cW - 12, gfy);
		}

		// ══ CENTRE BOTTOM · SESSION / FLOW ══
		{
			int y0 = panel(g, cX, geY + geH + 8, cW, botY - (geY + geH + 8) - 2, "SESSION  ·  FLOW");
			final String[][] tiles = {
				{ "REALIZED TODAY", "+48.7m", "g" }, { "GP / HR", "+12.3m", "g" },
				{ "FLIPS", "34", "a" }, { "WIN", "88%", "g" }, { "TAX PAID", "9.4m", "d" },
				{ "BANK", "6.31b", "a" },
			};
			int tw = cW / 6;
			for (int i = 0; i < tiles.length; i++)
			{
				int tx = cX + 8 + i * tw;
				g.setFont(mono(9)); g.setColor(LABEL); g.drawString(tiles[i][0], tx, y0);
				g.setFont(monoB(16));
				g.setColor(tiles[i][2].equals("g") ? GREEN : tiles[i][2].equals("d") ? LABEL : AMBERHI);
				g.drawString(tiles[i][1], tx, y0 + 22);
			}
			// mini ticker line
			int ty = y0 + 44;
			g.setColor(GRID); g.drawLine(cX + 8, ty - 6, cX + cW - 8, ty - 6);
			g.setFont(mono(10));
			g.setColor(GREEN); g.drawString("▲ Zaryte cbow +3.2%", cX + 8, ty + 6);
			g.setColor(RED);   g.drawString("▼ Sang staff -4.1%", cX + 190, ty + 6);
			g.setColor(GREEN); g.drawString("▲ Masori +1.4%", cX + 360, ty + 6);
			g.setColor(AMBER); rt(g, "IDLE CASH 4.27b  →  DEPLOY", cX + cW - 8, ty + 6);
		}

		// ══ RIGHT · POSITIONS BLOTTER + FILLS ══
		{
			int y0 = panel(g, rX, topY, rW, 300, "POSITIONS BLOTTER  ·  4 OFFERS");
			final String[][] off = {
				{ "S", "Tumeken's shadow", "×2 @805.0m", "▼drift 12m", "-15.2m", "HOLD", "a", "24m" },
				{ "S", "Scythe of vitur",  "×1 @1.28b",  "▲drift 8.6m", "-3.49m", "OK",  "g", "16m" },
				{ "S", "Tumeken's shadow", "×1 @805.0m", "▼drift 12.2m", "-12.2m", "HOLD", "a", "24m" },
				{ "S", "Elysian spirit shield", "×1 @519.8m", "▼drift 2.0m", "+2.82m", "OK", "g", "29m" },
			};
			int ry = y0;
			for (int i = 0; i < off.length; i++)
			{
				g.setFont(monoB(11)); g.setColor(RED); g.drawString(off[i][0], rX + 10, ry);
				g.setColor(AMBERHI); g.drawString(off[i][1], rX + 26, ry);
				chip(g, rX + rW - 78, ry, off[i][5], off[i][6].equals("g") ? GREEN : AMBER);
				int r2 = ry + 16;
				g.setFont(mono(10)); g.setColor(LABEL); g.drawString(off[i][2], rX + 26, r2);
				g.setColor(DIM); g.drawString(off[i][3], rX + 128, r2);
				boolean up = off[i][4].startsWith("+");
				g.setFont(monoB(11)); g.setColor(up ? GREEN : RED); rt(g, off[i][4], rX + rW - 46, r2);
				g.setFont(mono(9)); g.setColor(DIM); rt(g, off[i][7], rX + rW - 10, r2);
				g.setColor(GRID); g.drawLine(rX + 8, r2 + 8, rX + rW - 8, r2 + 8);
				ry += 44;
			}
			// blotter footer
			g.setColor(TITLEBG); g.fillRect(rX + 1, topY + 300 - 20, rW - 2, 19);
			g.setFont(monoB(11)); g.setColor(RED);
			g.drawString("0/4 SEATED", rX + 10, topY + 300 - 6);
			rt(g, "NET -23.47m", rX + rW - 10, topY + 300 - 6);

			// RECENT FILLS (all items)
			int fy = topY + 312;
			int f0 = panel(g, rX, fy, rW, botY - fy - 2, "RECENT FILLS  ·  ALL ITEMS");
			g.setFont(mono(8)); g.setColor(DIM);
			g.drawString("ITEM", rX + 10, f0 - 4);
			rt(g, "PRICE", rX + 250, f0 - 4);
			rt(g, "AGE", rX + rW - 10, f0 - 4);
			final String[][] fills = {
				{ "◆", "Elysian spirit shield", "SOLD 519.8m", "1m", "y" },
				{ "▲", "Twisted bow", "1.204b", "3m", "u" },
				{ "◆", "Scythe of vitur", "BOUGHT 1.28b", "6m", "y" },
				{ "▼", "Torva platebody", "512.4m", "9m", "d" },
				{ "▲", "Masori body", "108.9m", "12m", "u" },
				{ "◆", "Tumeken's shadow", "BOUGHT 805.0m", "18m", "y" },
				{ "▼", "Avernic defender", "62.1m", "22m", "d" },
				{ "▲", "Dragon claws", "41.0m", "26m", "u" },
			};
			for (int i = 0; i < fills.length; i++)
			{
				int ry2 = f0 + 10 + i * 24;
				boolean you = fills[i][4].equals("y");
				boolean up = fills[i][0].equals("▲");
				g.setFont(monoB(11)); g.setColor(you ? AMBERHI : up ? GREEN : RED);
				g.drawString(fills[i][0], rX + 10, ry2);
				g.setColor(AMBER); g.drawString(fills[i][1], rX + 26, ry2);
				g.setFont(mono(10)); g.setColor(you ? AMBERHI : up ? GREEN : RED);
				rt(g, fills[i][2], rX + rW - 46, ry2);
				g.setFont(mono(9)); g.setColor(DIM); rt(g, fills[i][3], rX + rW - 10, ry2);
				g.setColor(GRID); g.drawLine(rX + 8, ry2 + 7, rX + rW - 8, ry2 + 7);
			}
		}

		// ══ BOTTOM TICKER BAR ══
		{
			int x = 8, y = botY, w = W - 16, h = 40;
			g.setColor(PANEL); g.fillRect(x, y, w, h);
			g.setColor(BORDER); g.drawRect(x, y, w, h);
			g.setFont(monoB(12));
			String[] tick = {
				"TBOW", "1.204b", "u", "SCYTHE", "1.29b", "u", "SHADOW", "805m", "d",
				"ELY", "519.8m", "u", "TORVA", "512m", "d", "MASORI", "108.9m", "u",
				"CLAWS", "41.0m", "u", "ZCB", "38.4m", "u", "SANG", "78.9m", "d",
			};
			int tx = x + 16;
			for (int i = 0; i < tick.length; i += 3)
			{
				g.setColor(AMBER); g.drawString(tick[i], tx, y + 26);
				tx += g.getFontMetrics().stringWidth(tick[i]) + 8;
				boolean up = tick[i + 2].equals("u");
				g.setColor(up ? GREEN : RED);
				g.drawString((up ? "▲" : "▼") + tick[i + 1], tx, y + 26);
				tx += g.getFontMetrics().stringWidth((up ? "▲" : "▼") + tick[i + 1]) + 26;
			}
		}

		g.dispose();
		ImageIO.write(img, "png", new File(out));
		System.out.println("wrote " + out);
	}
}

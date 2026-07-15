package gg.pricecheck.runelite;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;

/**
 * Reads the in-game Grand Exchange History tab: the ground truth for trades
 * that completed and were collected while logged out (the one case offer
 * events can never cover). Rows are groups of six dynamic children on the
 * list widget; the direction text starts with "Bought"/"Sold", the item child
 * carries id + quantity, and the price child's text holds the PRE-tax total
 * (matching our gross fills, so no double-taxing on import).
 *
 * Client thread only (widget + item composition reads).
 */
final class GeHistoryReader
{
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	private static final Pattern PAREN_TOTAL = Pattern.compile("\\(([\\d,]+) -");
	private static final Pattern EACH = Pattern.compile("([\\d,]+) each");
	private static final Pattern COINS = Pattern.compile("([\\d,]+) coin");

	private GeHistoryReader()
	{
	}

	static List<FlipLogEngine.HistEntry> read(Client client, ItemManager itemManager)
	{
		final List<FlipLogEngine.HistEntry> out = new ArrayList<>();
		final Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null)
		{
			return out;
		}
		final Widget[] kids = list.getDynamicChildren();
		if (kids == null)
		{
			return out;
		}
		for (int i = 0; i + 5 < kids.length; i += 6)
		{
			final Widget dirW = kids[i + 2];
			final Widget itemW = kids[i + 4];
			final Widget priceW = kids[i + 5];
			if (dirW == null || itemW == null || priceW == null)
			{
				continue;
			}
			final String dir = strip(dirW.getText());
			final boolean buy = dir.startsWith("Bought");
			if (!buy && !dir.startsWith("Sold"))
			{
				continue;
			}
			final int itemId = itemW.getItemId();
			final int qty = Math.max(1, itemW.getItemQuantity());
			if (itemId <= 0)
			{
				continue;
			}
			final long gross = parseGross(strip(priceW.getText()), qty);
			if (gross <= 0)
			{
				continue;
			}
			final FlipLogEngine.HistEntry h = new FlipLogEngine.HistEntry();
			h.itemId = itemId;
			h.buy = buy;
			h.qty = qty;
			h.gross = gross;
			try
			{
				h.name = itemManager.getItemComposition(itemId).getName();
			}
			catch (RuntimeException ignored)
			{
			}
			out.add(h);
		}
		return out;
	}

	private static String strip(String s)
	{
		return s == null ? "" : TAGS.matcher(s).replaceAll("");
	}

	private static long parseGross(String text, int qty)
	{
		Matcher m = PAREN_TOTAL.matcher(text);
		if (m.find())
		{
			return num(m.group(1));           // "(1,234,567 - ..." = pre-tax total
		}
		m = EACH.matcher(text);
		if (m.find())
		{
			return num(m.group(1)) * qty;     // "... 1,234 each"
		}
		m = COINS.matcher(text);
		if (m.find())
		{
			return num(m.group(1));           // "... 1,234 coins"
		}
		return -1;
	}

	private static long num(String s)
	{
		try
		{
			return Long.parseLong(s.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}

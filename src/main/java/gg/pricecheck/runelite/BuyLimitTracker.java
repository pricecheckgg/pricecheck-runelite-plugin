package gg.pricecheck.runelite;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;

/**
 * Tracks the player's own GE buy-fill quantity per item over the trailing 4h
 * window. OSRS buy limits reset 4h after your FIRST buy in a window, so this lets
 * the terminal card show LIMIT remaining + a RESET countdown (and later, cap an
 * autofilled buy to what's left). Client-side only: fed the live GE offers, it
 * records the increase in bought quantity as a buy fill. Persisted per account via
 * ConfigManager so the 4h window survives a client restart.
 */
class BuyLimitTracker
{
	static final long WINDOW_MS = 4L * 60 * 60 * 1000;
	private static final int SLOTS = 8;
	private static final String KEY = "buylimit";
	private static final Type MAP_TYPE = new TypeToken<Map<Integer, List<long[]>>>() { }.getType();

	private final Gson gson;
	private final ConfigManager configManager;
	// geId -> list of {qty, atMs} buy fills within (or recently within) the window.
	private final Map<Integer, List<long[]>> byItem = new HashMap<>();
	private final long[] lastBought = new long[SLOTS];   // per slot: last quantitySold seen on a buy
	private final int[] lastItem = new int[SLOTS];
	private long account = -1;

	BuyLimitTracker(Gson gson, ConfigManager configManager)
	{
		this.gson = gson;
		this.configManager = configManager;
	}

	private static boolean isBuy(GrandExchangeOfferState s)
	{
		return s == GrandExchangeOfferState.BUYING
			|| s == GrandExchangeOfferState.BOUGHT
			|| s == GrandExchangeOfferState.CANCELLED_BUY;
	}

	synchronized void setAccount(long acc)
	{
		if (acc == account || acc == -1)
		{
			return;
		}
		account = acc;
		byItem.clear();
		for (int i = 0; i < SLOTS; i++)
		{
			lastBought[i] = 0;
			lastItem[i] = 0;
		}
		load();
	}

	/** Feed a live GE offer change; the growth in bought quantity is a buy fill. */
	synchronized void onOffer(int slot, GrandExchangeOffer o, long nowMs)
	{
		if (slot < 0 || slot >= SLOTS || o == null)
		{
			return;
		}
		final int itemId = o.getItemId();
		if (!isBuy(o.getState()) || itemId <= 0)
		{
			lastItem[slot] = 0;
			lastBought[slot] = 0;
			return;
		}
		if (lastItem[slot] != itemId)
		{
			lastItem[slot] = itemId;
			lastBought[slot] = 0;
		}
		final long sold = o.getQuantitySold();
		final long delta = sold - lastBought[slot];
		lastBought[slot] = sold;
		if (delta > 0)
		{
			byItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new long[]{delta, nowMs});
			save();
		}
	}

	private void prune(List<long[]> l, long nowMs)
	{
		l.removeIf(e -> nowMs - e[1] > WINDOW_MS);
	}

	/** Units of this item bought within the last 4h. */
	synchronized long boughtIn4h(int geId, long nowMs)
	{
		final List<long[]> l = byItem.get(geId);
		if (l == null)
		{
			return 0;
		}
		prune(l, nowMs);
		long s = 0;
		for (final long[] e : l)
		{
			s += e[0];
		}
		return s;
	}

	/** Epoch ms when the window's earliest buy rolls off (limit starts freeing), 0 if none. */
	synchronized long resetAt(int geId, long nowMs)
	{
		final List<long[]> l = byItem.get(geId);
		if (l == null)
		{
			return 0;
		}
		prune(l, nowMs);
		long earliest = Long.MAX_VALUE;
		for (final long[] e : l)
		{
			earliest = Math.min(earliest, e[1]);
		}
		return earliest == Long.MAX_VALUE ? 0 : earliest + WINDOW_MS;
	}

	private void load()
	{
		try
		{
			final String json = configManager.getConfiguration(PriceCheckConfig.GROUP, KEY + "_" + account);
			if (json != null && !json.isEmpty())
			{
				final Map<Integer, List<long[]>> m = gson.fromJson(json, MAP_TYPE);
				if (m != null)
				{
					final long now = System.currentTimeMillis();
					m.forEach((k, v) -> prune(v, now));
					m.entrySet().removeIf(e -> e.getValue().isEmpty());
					byItem.putAll(m);
				}
			}
		}
		catch (RuntimeException ignored)
		{
		}
	}

	private void save()
	{
		try
		{
			final long now = System.currentTimeMillis();
			byItem.values().forEach(l -> prune(l, now));
			byItem.entrySet().removeIf(e -> e.getValue().isEmpty());
			configManager.setConfiguration(PriceCheckConfig.GROUP, KEY + "_" + account, gson.toJson(byItem, MAP_TYPE));
		}
		catch (RuntimeException ignored)
		{
		}
	}
}

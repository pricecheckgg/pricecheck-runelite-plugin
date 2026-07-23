package gg.pricecheck.runelite;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;

/**
 * Tracks the player's own GE buy-fill quantity per item over the current 4h buy
 * window, so the terminal card can show LIMIT remaining + a RESET countdown and
 * the quantity autofill can cap a buy to what's left.
 *
 * Window model (matches the game, and RuneLite's own GE limit tracker): the 4h
 * timer starts at your FIRST buy of an item and every buy inside that window
 * counts; once 4h pass from that first buy the counter resets fully to zero and
 * the next buy opens a fresh window. A fixed window anchored at the first buy,
 * NOT a rolling per-fill window.
 *
 * Counting: a GE offer's quantitySold is cumulative for that offer, so a fill is
 * the GROWTH in quantitySold. We keep a per-slot cursor (the item + last counted
 * quantitySold) and only add the growth to the item's window.
 *
 * Login / world-hop / restart safety: the client blanks every slot to EMPTY and
 * replays the live offers after a login or hop. We must not re-count a replayed
 * offer. So the cursor is PERSISTED and is NOT cleared on an EMPTY/non-buy event
 * - a replayed offer arrives with the same item and the same quantitySold, so its
 * growth is zero. A genuinely new offer is recognised by its item changing or its
 * quantitySold dropping below the cursor (a fresh offer restarts at zero), which
 * reseeds the cursor and counts nothing on first sight. All state is per account.
 */
class BuyLimitTracker
{
	static final long WINDOW_MS = 4L * 60 * 60 * 1000;
	private static final int SLOTS = 8;
	private static final String KEY = "buylimit";

	/** Persisted snapshot: windows + per-slot cursor, all for one account. */
	private static final class Snapshot
	{
		Map<Integer, long[]> byItem;   // geId -> {windowStartMs, boughtInWindow}
		int[] slotItem;                // per slot: item the cursor is bound to
		long[] slotSold;               // per slot: last counted quantitySold
	}

	private final Gson gson;
	private final ConfigManager configManager;
	private final Map<Integer, long[]> byItem = new HashMap<>();
	private final long[] slotSold = new long[SLOTS];
	private final int[] slotItem = new int[SLOTS];
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

	private static boolean expired(long[] w, long nowMs)
	{
		return w == null || nowMs >= w[0] + WINDOW_MS;
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
			slotSold[i] = 0;
			slotItem[i] = 0;
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
			// EMPTY blank, a sell, or a collected slot. Leave the cursor alone: a
			// login/hop replays live offers right after blanking every slot, and
			// clearing here would make the replay look like fresh fills.
			return;
		}
		final long sold = o.getQuantitySold();
		if (slotItem[slot] != itemId || sold < slotSold[slot])
		{
			// A different item now occupies this slot, or quantitySold dropped (the
			// old offer was collected/cancelled and a new one of the same item
			// started): a new offer. Seed the cursor to its current progress and
			// count nothing on first sight - a fresh offer starts at zero, and any
			// pre-existing fill was already counted in the session that saw it.
			slotItem[slot] = itemId;
			slotSold[slot] = sold;
			save();
			return;
		}
		final long delta = sold - slotSold[slot];
		slotSold[slot] = sold;
		if (delta <= 0)
		{
			return;
		}
		long[] w = byItem.get(itemId);
		if (expired(w, nowMs))
		{
			// First buy of a new window: anchor it here and start counting.
			w = new long[]{nowMs, 0};
			byItem.put(itemId, w);
		}
		w[1] += delta;
		save();
	}

	/** Units of this item bought within the current 4h window (0 once it resets). */
	synchronized long boughtIn4h(int geId, long nowMs)
	{
		final long[] w = byItem.get(geId);
		return expired(w, nowMs) ? 0 : w[1];
	}

	/** Epoch ms when the current window resets the limit to zero, 0 if none active. */
	synchronized long resetAt(int geId, long nowMs)
	{
		final long[] w = byItem.get(geId);
		return expired(w, nowMs) ? 0 : w[0] + WINDOW_MS;
	}

	private void load()
	{
		try
		{
			final String json = configManager.getConfiguration(PriceCheckConfig.GROUP, KEY + "_" + account);
			if (json == null || json.isEmpty())
			{
				return;
			}
			final Snapshot snap = gson.fromJson(json, Snapshot.class);
			if (snap == null)
			{
				return;
			}
			final long now = System.currentTimeMillis();
			if (snap.byItem != null)
			{
				snap.byItem.forEach((k, v) ->
				{
					if (v != null && v.length == 2 && !expired(v, now))
					{
						byItem.put(k, v);
					}
				});
			}
			if (snap.slotItem != null && snap.slotSold != null
				&& snap.slotItem.length == SLOTS && snap.slotSold.length == SLOTS)
			{
				System.arraycopy(snap.slotItem, 0, slotItem, 0, SLOTS);
				System.arraycopy(snap.slotSold, 0, slotSold, 0, SLOTS);
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
			byItem.entrySet().removeIf(e -> expired(e.getValue(), now));
			final Snapshot snap = new Snapshot();
			snap.byItem = byItem;
			snap.slotItem = slotItem;
			snap.slotSold = slotSold;
			configManager.setConfiguration(PriceCheckConfig.GROUP, KEY + "_" + account, gson.toJson(snap));
		}
		catch (RuntimeException ignored)
		{
		}
	}
}

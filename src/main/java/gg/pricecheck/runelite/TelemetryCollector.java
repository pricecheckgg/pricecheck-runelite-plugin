package gg.pricecheck.runelite;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Collects the player's OWN Grand Exchange offer lifecycle events (placed,
 * partial fill, completed, cancelled) for batched reporting to PriceCheck.
 * These fills are ground truth the public price aggregates can't see — how
 * long offers at a given price actually take to fill — and they sharpen the
 * measured fill model behind everyone's board.
 *
 * Opt-out via the "Contribute market data" config toggle. Only offer state is
 * queued: item, price, quantities, GE slot. Never the RSN, skills, wealth, or
 * anything else about the account.
 *
 * Thread-safe: offer() is called from the client thread (offer-changed events),
 * drain() from the background poller.
 */
class TelemetryCollector
{
	private static final int SLOTS = 8;
	private static final int MAX_QUEUE = 240;   // oldest events drop past this (long offline stretches)
	static final int MAX_BATCH = 60;            // matches the server's per-request cap

	private final Object lock = new Object();
	private final ArrayDeque<Map<String, Object>> queue = new ArrayDeque<>();
	// Last queued fingerprint per slot — login replays and duplicate change
	// events for the same offer state dedupe here.
	private final String[] lastFp = new String[SLOTS];

	void offer(int slot, GrandExchangeOffer o)
	{
		if (slot < 0 || slot >= SLOTS || o == null)
		{
			return;
		}
		final String state = mapState(o.getState());
		if (state == null)
		{
			if (o.getState() == GrandExchangeOfferState.EMPTY)
			{
				synchronized (lock)
				{
					lastFp[slot] = null;   // slot cleared; next offer is genuinely new
				}
			}
			return;
		}
		if (o.getItemId() <= 0 || o.getPrice() <= 0 || o.getTotalQuantity() <= 0)
		{
			return;
		}
		final String fp = state + ':' + o.getItemId() + ':' + o.getPrice()
			+ ':' + o.getTotalQuantity() + ':' + o.getQuantitySold();
		synchronized (lock)
		{
			if (fp.equals(lastFp[slot]))
			{
				return;
			}
			lastFp[slot] = fp;
			final Map<String, Object> e = new HashMap<>(8);
			e.put("slot", slot);
			e.put("itemId", o.getItemId());
			e.put("state", state);
			e.put("price", o.getPrice());
			e.put("totalQty", o.getTotalQuantity());
			e.put("filledQty", o.getQuantitySold());
			e.put("spent", o.getSpent());
			e.put("ts", System.currentTimeMillis());
			queue.addLast(e);
			while (queue.size() > MAX_QUEUE)
			{
				queue.removeFirst();
			}
		}
	}

	/** Up to one batch of queued events, or an empty list. Drained events are
	 *  gone — a failed send drops them (telemetry is best-effort by design). */
	List<Map<String, Object>> drain()
	{
		synchronized (lock)
		{
			if (queue.isEmpty())
			{
				return new ArrayList<>(0);
			}
			final List<Map<String, Object>> out = new ArrayList<>(Math.min(queue.size(), MAX_BATCH));
			while (out.size() < MAX_BATCH && !queue.isEmpty())
			{
				out.add(queue.removeFirst());
			}
			return out;
		}
	}

	void clear()
	{
		synchronized (lock)
		{
			queue.clear();
			for (int i = 0; i < SLOTS; i++)
			{
				lastFp[i] = null;
			}
		}
	}

	private static String mapState(GrandExchangeOfferState st)
	{
		if (st == null)
		{
			return null;
		}
		switch (st)
		{
			case BUYING:
				return "BUYING";
			case BOUGHT:
				return "BOUGHT";
			case CANCELLED_BUY:
				return "CANCELLED_BUY";
			case SELLING:
				return "SELLING";
			case SOLD:
				return "SOLD";
			case CANCELLED_SELL:
				return "CANCELLED_SELL";
			default:
				return null;
		}
	}
}

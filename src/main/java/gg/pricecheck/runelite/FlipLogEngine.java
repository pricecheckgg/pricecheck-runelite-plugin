package gg.pricecheck.runelite;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * The FREE flip log: an exact, crash-proof ledger of every Grand Exchange fill,
 * matched into flips. Design goals, each aimed at a documented weakness in the
 * incumbent trackers:
 *
 *  - EXACT gp. Fills are deltas of the client's cumulative spent/quantity
 *    counters against a persisted per-slot snapshot (the same method RuneLite
 *    itself uses for its trade submissions), so partial fills and price
 *    improvement are real coins, never an averaged int. All math is long.
 *  - NEVER lose a flip. State is written atomically (temp file + move) after
 *    every mutating event, not on shutdown.
 *  - DETERMINISTIC matching. Buys become lots; sells consume lots FIFO with
 *    proportional cost. Open positions are first-class (visible, with cost
 *    basis), margin checks (qty-1 probes completing within 2 ticks) are tagged
 *    and kept out of the win rate, and sells with no tracked cost are counted
 *    separately instead of inventing profit.
 *  - Keyed by account hash, so a display-name change never orphans history.
 *
 * Runs on the client thread (offer events); the sync drain runs on the poller.
 * All cross-thread access goes through synchronized methods on this object.
 */
@Slf4j
class FlipLogEngine
{
	private static final int SLOTS = 8;
	private static final int LOGIN_BURST_TICKS = 2;   // events this close to login aggregate offline fills
	// A qty-1 offer completing this fast after placement is a spread probe, not
	// a flip. Wall-clock, so it survives client restarts (game ticks do not).
	private static final long MARGIN_CHECK_MS = 3000;
	private static final long ACTIVE_WINDOW_MS = 10 * 60_000L;   // GE activity gap that still counts as flipping
	private static final int MAX_FLIPS_KEPT = 5000;
	private static final int MAX_PENDING_FILLS = 2000;
	private static final int MAX_OPEN_LOTS = 400;

	static class SlotSnap
	{
		int itemId;
		int qtySold;
		int total;
		int price;
		long spent;
		String state;
		// Wall-clock placement time; 0 = placement never seen (first sight
		// mid-trade), which can never qualify as a margin check.
		long placedMs;
	}

	static class Fill
	{
		String id;
		int itemId;
		boolean buy;
		int qty;
		long gross;
		long tax;
		long ts;
		boolean agg;      // login-burst aggregate: real coins, unreliable timing
		transient boolean check;
		transient String name;
	}

	static class Lot
	{
		int itemId;
		String name;
		int qty;
		long cost;
		long openedAt;
		boolean check;
	}

	static class Flip
	{
		String id;
		int itemId;
		String name;
		int qty;
		long buyGross;
		long sellGross;
		long tax;
		long profit;
		long openedAt;
		long closedAt;
		boolean check;
		boolean synced;
	}

	static class Data
	{
		int v = 1;
		SlotSnap[] slots = new SlotSnap[SLOTS];
		List<Lot> openLots = new ArrayList<>();
		List<Flip> flips = new ArrayList<>();           // oldest first
		List<Fill> pendingFills = new ArrayList<>();    // awaiting server sync
		long allProfit;
		long allTax;
		int allFlips;
		int allWins;
		int checks;
		long untrackedSells;                            // sold qty with no tracked cost basis
	}

	/** Immutable snapshot for the panel. */
	static class Summary
	{
		long todayProfit;
		long weekProfit;
		long allProfit;
		long allTax;
		int allFlips;
		int winRatePct = -1;
		int checks;
		long sessionProfit;
		long sessionGpHr = Long.MIN_VALUE;   // MIN_VALUE = not enough active time yet
		List<Lot> openLots;
		List<Flip> recent;                   // newest first
		int pendingSync;
		long untrackedSells;
	}

	private final Gson gson;
	private final File dir;

	private long accountHash = -1;
	private Data data = new Data();

	// Session (runtime only): honest active time, not wall clock.
	private long sessionStartMs;
	private long sessionProfit;
	private long activeMs;
	private long lastEventMs;

	FlipLogEngine(Gson gson, File runeliteDir)
	{
		this.gson = gson;
		this.dir = new File(runeliteDir, "pricecheck");
	}

	synchronized void setAccount(long hash)
	{
		if (hash == -1 || hash == accountHash)
		{
			return;
		}
		accountHash = hash;
		data = load();
		sessionStartMs = System.currentTimeMillis();
		sessionProfit = 0;
		activeMs = 0;
		lastEventMs = 0;
	}

	synchronized long getAccountHash()
	{
		return accountHash;
	}

	// ── event intake (client thread) ──

	synchronized void onOffer(int slot, GrandExchangeOffer o, GameState gameState, int tick, int lastLoginTick, String itemName)
	{
		if (accountHash == -1 || slot < 0 || slot >= SLOTS || o == null)
		{
			return;
		}
		final GrandExchangeOfferState st = o.getState();
		if (st == GrandExchangeOfferState.EMPTY)
		{
			// Real collection only while logged in; the client blanks all slots
			// during login/hopping and honoring those would wipe good snapshots.
			if (gameState == GameState.LOGGED_IN && data.slots[slot] != null)
			{
				data.slots[slot] = null;
				save();
			}
			return;
		}

		final boolean isBuy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
		SlotSnap snap = data.slots[slot];
		final long now = System.currentTimeMillis();

		// New offer placement: remember when, for margin-check detection.
		if (o.getQuantitySold() == 0)
		{
			snap = new SlotSnap();
			snap.itemId = o.getItemId();
			snap.qtySold = 0;
			snap.total = o.getTotalQuantity();
			snap.price = o.getPrice();
			snap.spent = 0;
			snap.state = st.name();
			snap.placedMs = now;
			data.slots[slot] = snap;
			save();
			return;
		}

		// Desync: the slot was changed from another client — resync, no delta.
		if (snap != null && (snap.itemId != o.getItemId() || snap.price != o.getPrice() || snap.total != o.getTotalQuantity()))
		{
			snap = null;
		}

		// Duplicate event (RuneLite fires most events twice) or the redundant
		// full-quantity BUYING/SELLING that precedes BOUGHT/SOLD: no new fill.
		final int prevQty = snap == null ? 0 : snap.qtySold;
		final long prevSpent = snap == null ? 0 : snap.spent;
		final int dqty = o.getQuantitySold() - prevQty;
		final long dspent = o.getSpent() - prevSpent;

		if (snap == null)
		{
			// First sight mid-trade (placed on another machine or before install):
			// treat the whole progress as one aggregate fill.
			snap = new SlotSnap();
			snap.itemId = o.getItemId();
			snap.total = o.getTotalQuantity();
			snap.price = o.getPrice();
			snap.placedMs = 0;   // never margin-check an unseen placement
			data.slots[slot] = snap;
		}

		if (dqty > 0 && dspent >= 0)
		{
			final Fill f = new Fill();
			f.id = accountHash + ":" + slot + ":" + now + ":" + o.getQuantitySold();
			f.itemId = o.getItemId();
			f.buy = isBuy;
			f.qty = dqty;
			f.gross = dspent;
			f.tax = isBuy ? 0 : fillTax(dspent, dqty);
			f.ts = now;
			f.agg = tick <= lastLoginTick + LOGIN_BURST_TICKS;
			// The completing fill usually arrives on the redundant full-quantity
			// BUYING/SELLING event, so key on completion, not terminal state.
			f.check = o.getQuantitySold() == o.getTotalQuantity() && o.getTotalQuantity() == 1
				&& snap.placedMs > 0 && now - snap.placedMs >= 0 && now - snap.placedMs <= MARGIN_CHECK_MS
				&& !f.agg;
			f.name = itemName;
			ingest(f);
		}

		snap.qtySold = o.getQuantitySold();
		snap.spent = o.getSpent();
		snap.state = st.name();
		save();
	}

	// Tax the way the game does per item (2% floored, 5m cap), applied to the
	// exact average price of THIS fill; within qty gp of the true charge.
	private static long fillTax(long gross, int qty)
	{
		if (qty <= 0)
		{
			return 0;
		}
		return GeTax.tax(gross / qty) * qty;
	}

	private void ingest(Fill f)
	{
		final long now = f.ts;
		// Login aggregates are real coins but not live activity: keep them out
		// of the session clock and session profit so gp/hr stays honest.
		if (!f.agg)
		{
			if (lastEventMs > 0)
			{
				activeMs += Math.min(now - lastEventMs, ACTIVE_WINDOW_MS);
			}
			lastEventMs = now;
		}

		data.pendingFills.add(f);
		while (data.pendingFills.size() > MAX_PENDING_FILLS)
		{
			data.pendingFills.remove(0);
		}

		if (f.buy)
		{
			final Lot lot = new Lot();
			lot.itemId = f.itemId;
			lot.name = f.name;
			lot.qty = f.qty;
			lot.cost = f.gross;
			lot.openedAt = f.ts;
			lot.check = f.check;
			data.openLots.add(lot);
			while (data.openLots.size() > MAX_OPEN_LOTS)
			{
				data.openLots.remove(0);
			}
			return;
		}

		// Sell: consume lots FIFO with proportional cost. Totals are conserved:
		// each consumed share is subtracted from the lot, so rounding never
		// creates or destroys gp.
		final boolean liveFill = !f.agg;
		int remaining = f.qty;
		long buyShare = 0;
		long openedAt = Long.MAX_VALUE;
		boolean checkLot = false;
		String name = f.name;
		final Iterator<Lot> it = data.openLots.iterator();
		while (it.hasNext() && remaining > 0)
		{
			final Lot lot = it.next();
			if (lot.itemId != f.itemId)
			{
				continue;
			}
			final int take = Math.min(lot.qty, remaining);
			final long share = lot.qty == take ? lot.cost : lot.cost * take / lot.qty;
			lot.cost -= share;
			lot.qty -= take;
			buyShare += share;
			openedAt = Math.min(openedAt, lot.openedAt);
			checkLot |= lot.check;
			if (name == null)
			{
				name = lot.name;
			}
			if (lot.qty == 0)
			{
				it.remove();
			}
			remaining -= take;
		}
		final int matched = f.qty - remaining;
		if (remaining > 0)
		{
			data.untrackedSells += remaining;
		}
		if (matched <= 0)
		{
			return;
		}
		// Proportional slice of the sell for the matched quantity.
		final long sellGross = matched == f.qty ? f.gross : f.gross * matched / f.qty;
		final long tax = matched == f.qty ? f.tax : f.tax * matched / f.qty;

		final Flip flip = new Flip();
		flip.id = f.id + ":fl";
		flip.itemId = f.itemId;
		flip.name = name;
		flip.qty = matched;
		flip.buyGross = buyShare;
		flip.sellGross = sellGross;
		flip.tax = tax;
		flip.profit = sellGross - tax - buyShare;
		flip.openedAt = openedAt == Long.MAX_VALUE ? f.ts : openedAt;
		flip.closedAt = f.ts;
		flip.check = f.check || checkLot;
		data.flips.add(flip);
		while (data.flips.size() > MAX_FLIPS_KEPT)
		{
			data.flips.remove(0);
		}

		data.allProfit += flip.profit;
		data.allTax += flip.tax;
		if (flip.check)
		{
			data.checks++;
		}
		else
		{
			data.allFlips++;
			if (flip.profit > 0)
			{
				data.allWins++;
			}
		}
		if (liveFill)
		{
			sessionProfit += flip.profit;
		}
	}

	// ── panel snapshot ──

	synchronized Summary summary()
	{
		final Summary s = new Summary();
		final long now = System.currentTimeMillis();
		long dayStart = now - (now % 86_400_000L);
		long weekStart = now - 7 * 86_400_000L;
		for (int i = data.flips.size() - 1; i >= 0; i--)
		{
			final Flip f = data.flips.get(i);
			if (f.closedAt >= weekStart)
			{
				s.weekProfit += f.profit;
				if (f.closedAt >= dayStart)
				{
					s.todayProfit += f.profit;
				}
			}
			else
			{
				break;   // flips are time-ordered
			}
		}
		s.allProfit = data.allProfit;
		s.allTax = data.allTax;
		s.allFlips = data.allFlips;
		s.checks = data.checks;
		s.untrackedSells = data.untrackedSells;
		if (data.allFlips > 0)
		{
			s.winRatePct = Math.round(100f * data.allWins / data.allFlips);
		}
		s.sessionProfit = sessionProfit;
		final long active = activeMs + (lastEventMs > 0 ? Math.min(now - lastEventMs, ACTIVE_WINDOW_MS) : 0);
		if (active >= 5 * 60_000L)
		{
			s.sessionGpHr = sessionProfit * 3_600_000L / active;
		}
		s.openLots = copyLots(data.openLots);
		final int n = data.flips.size();
		s.recent = new ArrayList<>();
		for (int i = n - 1; i >= 0 && s.recent.size() < 50; i--)
		{
			s.recent.add(data.flips.get(i));
		}
		s.pendingSync = data.pendingFills.size();
		return s;
	}

	// ── sync drain (poller thread) ──

	static class SyncBatch
	{
		long accountHash;
		List<Fill> fills;
		List<Flip> flips;
		List<Lot> lots;
	}

	synchronized SyncBatch syncBatch()
	{
		if (accountHash == -1)
		{
			return null;
		}
		final List<Fill> fills = new ArrayList<>();
		for (int i = 0; i < data.pendingFills.size() && fills.size() < 120; i++)
		{
			fills.add(data.pendingFills.get(i));
		}
		final List<Flip> flips = new ArrayList<>();
		for (int i = data.flips.size() - 1; i >= 0 && flips.size() < 60; i--)
		{
			final Flip f = data.flips.get(i);
			if (!f.synced)
			{
				flips.add(f);
			}
		}
		if (fills.isEmpty() && flips.isEmpty())
		{
			return null;
		}
		final SyncBatch b = new SyncBatch();
		b.accountHash = accountHash;
		b.fills = fills;
		b.flips = flips;
		b.lots = copyLots(data.openLots);
		return b;
	}

	// Lots are mutated in place as sells consume them; hand out value copies so
	// the panel and the sync serializer never observe a torn qty/cost pair.
	private static List<Lot> copyLots(List<Lot> src)
	{
		final List<Lot> out = new ArrayList<>(src.size());
		for (final Lot l : src)
		{
			final Lot c = new Lot();
			c.itemId = l.itemId;
			c.name = l.name;
			c.qty = l.qty;
			c.cost = l.cost;
			c.openedAt = l.openedAt;
			c.check = l.check;
			out.add(c);
		}
		return out;
	}

	synchronized void onSyncSuccess(SyncBatch b)
	{
		if (b == null)
		{
			return;
		}
		for (final Fill f : b.fills)
		{
			data.pendingFills.remove(f);
		}
		for (final Flip f : b.flips)
		{
			f.synced = true;
		}
		save();
	}

	// ── persistence: atomic write on every mutation ──

	private File fileFor(long hash)
	{
		return new File(dir, "flips-" + hash + ".json");
	}

	private Data load()
	{
		try
		{
			final File f = fileFor(accountHash);
			if (f.exists())
			{
				final Data d = gson.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
				if (d != null)
				{
					if (d.slots == null || d.slots.length != SLOTS)
					{
						d.slots = new SlotSnap[SLOTS];
					}
					if (d.openLots == null)
					{
						d.openLots = new ArrayList<>();
					}
					if (d.flips == null)
					{
						d.flips = new ArrayList<>();
					}
					if (d.pendingFills == null)
					{
						d.pendingFills = new ArrayList<>();
					}
					return d;
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("flip log load failed; starting fresh (old file kept as .bad)", e);
			try
			{
				Files.move(fileFor(accountHash).toPath(), new File(dir, "flips-" + accountHash + ".bad.json").toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException ignored)
			{
			}
		}
		return new Data();
	}

	private void save()
	{
		try
		{
			if (!dir.exists() && !dir.mkdirs())
			{
				return;
			}
			final Path tmp = new File(dir, "flips-" + accountHash + ".tmp").toPath();
			Files.write(tmp, gson.toJson(data).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, fileFor(accountHash).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("flip log save failed", e);
		}
	}
}

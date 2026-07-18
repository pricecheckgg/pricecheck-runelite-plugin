package gg.pricecheck.runelite;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
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
		// Last mutation time: the multi-machine handoff picks whichever copy
		// of a slot (local file vs server) is fresher.
		long updatedMs;
	}

	/** GET /slots response: the freshest state another machine uploaded. */
	static class RemoteState
	{
		List<SlotExport> slots;
		long slotsUpdatedAt;
		List<Lot> lots;
		long lotsUpdatedAt;
		// Flips deleted on the web or on another machine; applied on adoption so
		// a delete anywhere is a delete everywhere.
		List<String> deletions;
	}

	/** Slot snapshot as it travels to/from the server. */
	static class SlotExport
	{
		int slot;
		int itemId;
		int qtySold;
		int total;
		int price;
		long spent;
		String state;
		long placedMs;
		long updatedMs;
	}

	/** One GE offer event captured as primitives (safe to replay off-thread). */
	private static class HeldEvent
	{
		int slot;
		int itemId;
		int qtySold;
		int total;
		int price;
		long spent;
		GrandExchangeOfferState st;
		boolean loggedIn;
		int tick;
		int lastLoginTick;
		String name;
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
		// The server ships lots as "itemName"; the local file has always said
		// "name". Without the alternate, every multi-machine adoption nulled the
		// name and the panel degraded to "#itemId".
		@SerializedName(value = "name", alternate = {"itemName"})
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
		// Dedupe keys ("item:side:qty:gross") for the GE-history import: every
		// live fill and every import records one, so re-opening the history tab
		// can never import the same trade twice.
		List<String> fillKeys = new ArrayList<>();
		// User-deleted flips. deletedFlipIds is the local tombstone set (a sync
		// or adoption can never resurrect one); pendingDeletes are ids still
		// awaiting server acknowledgement.
		List<String> deletedFlipIds = new ArrayList<>();
		List<String> pendingDeletes = new ArrayList<>();
		long lastBackupMs;
		// When the local open-lot list last changed (multi-machine adoption
		// only replaces lots with a server copy that is strictly newer).
		long lotsMutMs;
		// Slot snapshots changed since the last successful sync.
		boolean slotsDirty;
	}

	/** Immutable snapshot for the panel. */
	static class Summary
	{
		long todayProfit;
		long weekProfit;
		long allProfit;
		long allTax;
		int allFlips;
		int allWins;
		int winRatePct = -1;
		// Capital-weighted: total profit vs total gp spent across the retained
		// flip history, checks excluded. NaN until a flip with a cost exists.
		double avgRoiPct = Double.NaN;
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

	// Login hold: while another machine's fresher state is being fetched,
	// incoming offer events queue here instead of diffing against stale local
	// snapshots (which would re-report fills the other machine recorded).
	private boolean holdActive;
	private long holdStartedMs;
	private final List<HeldEvent> held = new ArrayList<>();
	private static final long HOLD_MAX_MS = 6000;
	private static final int HOLD_MAX_EVENTS = 64;

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

	// ── login hold + multi-machine adoption ──

	synchronized void beginLoginHold()
	{
		holdActive = true;
		holdStartedMs = System.currentTimeMillis();
		held.clear();
	}

	/** Adopt whatever the server has that is fresher than us, then replay the
	 *  held events against the adopted snapshots. remote == null (fetch failed,
	 *  no key) just releases the hold. */
	synchronized void adoptRemote(RemoteState remote)
	{
		if (remote != null && accountHash != -1)
		{
			boolean changed = false;
			if (remote.slots != null)
			{
				for (final SlotExport rs : remote.slots)
				{
					if (rs == null || rs.slot < 0 || rs.slot >= SLOTS)
					{
						continue;
					}
					final SlotSnap local = data.slots[rs.slot];
					if (local == null || rs.updatedMs > local.updatedMs)
					{
						final SlotSnap s = new SlotSnap();
						s.itemId = rs.itemId;
						s.qtySold = rs.qtySold;
						s.total = rs.total;
						s.price = rs.price;
						s.spent = rs.spent;
						s.state = rs.state;
						s.placedMs = rs.placedMs;
						s.updatedMs = rs.updatedMs;
						data.slots[rs.slot] = s;
						changed = true;
					}
				}
			}
			// Lots follow the same rule: only a strictly-newer server copy wins,
			// so unsynced local mutations are never thrown away.
			if (remote.lots != null && remote.lotsUpdatedAt > data.lotsMutMs)
			{
				data.openLots = new ArrayList<>(remote.lots);
				data.lotsMutMs = remote.lotsUpdatedAt;
				changed = true;
			}
			// Deletes made on the web or another machine reach this one here.
			if (remote.deletions != null)
			{
				for (final String id : remote.deletions)
				{
					if (id != null && !data.deletedFlipIds.contains(id) && removeFlip(id, false))
					{
						changed = true;
					}
				}
			}
			if (changed)
			{
				save();
			}
		}
		releaseHold();
	}

	// ── deletion (import mistakes, or the user just wants a trade gone) ──

	/** Delete one flip everywhere: out of the list and totals, tombstoned so no
	 *  sync or adoption resurrects it, queued for server deletion. The fills
	 *  behind it stay in fillKeys, so a GE-history import won't re-offer the
	 *  same trade. Consumed lots are NOT restored (delete, not undo). */
	synchronized boolean deleteFlip(String id)
	{
		if (id == null || !removeFlip(id, true))
		{
			return false;
		}
		save();
		return true;
	}

	private boolean removeFlip(String id, boolean tellServer)
	{
		final Iterator<Flip> it = data.flips.iterator();
		while (it.hasNext())
		{
			final Flip f = it.next();
			if (!id.equals(f.id))
			{
				continue;
			}
			it.remove();
			data.allProfit -= f.profit;
			data.allTax -= f.tax;
			if (f.check)
			{
				data.checks--;
			}
			else
			{
				data.allFlips--;
				if (f.profit > 0)
				{
					data.allWins--;
				}
			}
			tombstone(data.deletedFlipIds, id);
			if (tellServer)
			{
				tombstone(data.pendingDeletes, id);
			}
			return true;
		}
		// Not held locally (evicted long ago): still tombstone, so a stale
		// machine can't push it back after the server forgot it.
		tombstone(data.deletedFlipIds, id);
		return false;
	}

	private static void tombstone(List<String> list, String id)
	{
		if (!list.contains(id))
		{
			list.add(id);
			while (list.size() > 500)
			{
				list.remove(0);
			}
		}
	}

	/** Remove one open position by exact identity. Sells of that item later
	 *  become untracked instead of matching a lot the user disowned. */
	synchronized boolean deleteLot(int itemId, int qty, long cost, long openedAt)
	{
		final Iterator<Lot> it = data.openLots.iterator();
		while (it.hasNext())
		{
			final Lot l = it.next();
			if (l.itemId == itemId && l.qty == qty && l.cost == cost && l.openedAt == openedAt)
			{
				it.remove();
				data.lotsMutMs = System.currentTimeMillis();
				data.slotsDirty = true;   // force a push so the server copy updates too
				save();
				return true;
			}
		}
		return false;
	}

	// ── name healing ──
	// Names can be missing on old records (a serialization gap in early builds
	// nulled them on multi-machine adoption). The plugin resolves ids on the
	// client thread and hands the answers back here.

	synchronized java.util.Set<Integer> idsMissingNames()
	{
		final java.util.Set<Integer> out = new java.util.HashSet<>();
		for (final Lot l : data.openLots)
		{
			if (l.name == null || l.name.isEmpty())
			{
				out.add(l.itemId);
			}
		}
		for (final Flip f : data.flips)
		{
			if (f.name == null || f.name.isEmpty())
			{
				out.add(f.itemId);
			}
		}
		return out;
	}

	synchronized void applyNames(java.util.Map<Integer, String> names)
	{
		if (names == null || names.isEmpty())
		{
			return;
		}
		boolean changed = false;
		for (final Lot l : data.openLots)
		{
			final String n = names.get(l.itemId);
			if ((l.name == null || l.name.isEmpty()) && n != null)
			{
				l.name = n;
				changed = true;
			}
		}
		for (final Flip f : data.flips)
		{
			final String n = names.get(f.itemId);
			if ((f.name == null || f.name.isEmpty()) && n != null)
			{
				f.name = n;
				changed = true;
			}
		}
		if (changed)
		{
			// Healed lots must win the next adoption and reach the server.
			data.lotsMutMs = System.currentTimeMillis();
			data.slotsDirty = true;
			save();
		}
	}

	synchronized void releaseHold()
	{
		if (!holdActive)
		{
			return;
		}
		holdActive = false;
		final List<HeldEvent> q = new ArrayList<>(held);
		held.clear();
		for (final HeldEvent h : q)
		{
			process(h.slot, h.itemId, h.qtySold, h.total, h.price, h.spent, h.st, h.loggedIn, h.tick, h.lastLoginTick, h.name);
		}
	}

	// ── event intake (client thread) ──

	synchronized void onOffer(int slot, GrandExchangeOffer o, GameState gameState, int tick, int lastLoginTick, String itemName)
	{
		if (accountHash == -1 || slot < 0 || slot >= SLOTS || o == null)
		{
			return;
		}
		if (holdActive)
		{
			// Safety valve: a hung fetch must never dam events forever.
			if (System.currentTimeMillis() - holdStartedMs > HOLD_MAX_MS)
			{
				releaseHold();
			}
			else
			{
				if (held.size() < HOLD_MAX_EVENTS)
				{
					final HeldEvent h = new HeldEvent();
					h.slot = slot;
					h.itemId = o.getItemId();
					h.qtySold = o.getQuantitySold();
					h.total = o.getTotalQuantity();
					h.price = o.getPrice();
					h.spent = o.getSpent();
					h.st = o.getState();
					h.loggedIn = gameState == GameState.LOGGED_IN;
					h.tick = tick;
					h.lastLoginTick = lastLoginTick;
					h.name = itemName;
					held.add(h);
				}
				return;
			}
		}
		process(slot, o.getItemId(), o.getQuantitySold(), o.getTotalQuantity(), o.getPrice(), o.getSpent(),
			o.getState(), gameState == GameState.LOGGED_IN, tick, lastLoginTick, itemName);
	}

	private void process(int slot, int itemId, int qtySold, int totalQty, int price, long spent,
		GrandExchangeOfferState st, boolean loggedIn, int tick, int lastLoginTick, String itemName)
	{
		if (st == GrandExchangeOfferState.EMPTY)
		{
			// Real collection only while logged in; the client blanks all slots
			// during login/hopping and honoring those would wipe good snapshots.
			if (loggedIn && data.slots[slot] != null)
			{
				data.slots[slot] = null;
				data.slotsDirty = true;
				save();
			}
			return;
		}

		final boolean isBuy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
		SlotSnap snap = data.slots[slot];
		final long now = System.currentTimeMillis();

		// New offer placement: remember when, for margin-check detection.
		if (qtySold == 0)
		{
			snap = new SlotSnap();
			snap.itemId = itemId;
			snap.qtySold = 0;
			snap.total = totalQty;
			snap.price = price;
			snap.spent = 0;
			snap.state = st.name();
			snap.placedMs = now;
			snap.updatedMs = now;
			data.slots[slot] = snap;
			data.slotsDirty = true;
			save();
			return;
		}

		// Desync: the slot was changed from another client: resync, no delta.
		if (snap != null && (snap.itemId != itemId || snap.price != price || snap.total != totalQty))
		{
			snap = null;
		}

		// Duplicate event (RuneLite fires most events twice) or the redundant
		// full-quantity BUYING/SELLING that precedes BOUGHT/SOLD: no new fill.
		final int prevQty = snap == null ? 0 : snap.qtySold;
		final long prevSpent = snap == null ? 0 : snap.spent;
		final int dqty = qtySold - prevQty;
		final long dspent = spent - prevSpent;

		if (snap == null)
		{
			// First sight mid-trade (placed on another machine or before install):
			// treat the whole progress as one aggregate fill.
			snap = new SlotSnap();
			snap.itemId = itemId;
			snap.total = totalQty;
			snap.price = price;
			snap.placedMs = 0;   // never margin-check an unseen placement
			data.slots[slot] = snap;
		}

		if (dqty > 0 && dspent >= 0)
		{
			final Fill f = new Fill();
			f.id = accountHash + ":" + slot + ":" + now + ":" + qtySold;
			f.itemId = itemId;
			f.buy = isBuy;
			f.qty = dqty;
			f.gross = dspent;
			f.tax = isBuy ? 0 : fillTax(dspent, dqty);
			f.ts = now;
			f.agg = tick <= lastLoginTick + LOGIN_BURST_TICKS;
			// The completing fill usually arrives on the redundant full-quantity
			// BUYING/SELLING event, so key on completion, not terminal state.
			f.check = qtySold == totalQty && totalQty == 1
				&& snap.placedMs > 0 && now - snap.placedMs >= 0 && now - snap.placedMs <= MARGIN_CHECK_MS
				&& !f.agg;
			f.name = itemName;
			ingest(f);
		}

		snap.qtySold = qtySold;
		snap.spent = spent;
		snap.state = st.name();
		snap.updatedMs = now;
		data.slotsDirty = true;
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

	// Live fill events, in memory only: {itemId, unit price, ts ms, buy 1/0}.
	// The card's tape matches wiki prints against THESE, because lot and flip
	// aggregates anchor to their first fill and a progressing offer's later
	// fills drift out of any window anchored there.
	private final java.util.ArrayDeque<long[]> fillEvents = new java.util.ArrayDeque<>();

	synchronized List<long[]> recentFillEvents()
	{
		return new ArrayList<>(fillEvents);
	}

	private void ingest(Fill f)
	{
		if (f.qty > 0)
		{
			fillEvents.addLast(new long[]{f.itemId, f.gross / f.qty, f.ts, f.buy ? 1 : 0});
			while (fillEvents.size() > 48)
			{
				fillEvents.removeFirst();
			}
		}
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
		// Remember the tuple so a GE-history import can tell this trade is
		// already logged.
		data.fillKeys.add(f.itemId + ":" + (f.buy ? "b" : "s") + ":" + f.qty + ":" + f.gross);
		while (data.fillKeys.size() > 800)
		{
			data.fillKeys.remove(0);
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
			data.lotsMutMs = now;
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
		data.lotsMutMs = now;
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
		s.allWins = data.allWins;
		s.checks = data.checks;
		s.untrackedSells = data.untrackedSells;
		if (data.allFlips > 0)
		{
			s.winRatePct = Math.round(100f * data.allWins / data.allFlips);
		}
		long roiNum = 0;
		long roiDen = 0;
		for (final Flip f : data.flips)
		{
			if (!f.check && f.buyGross > 0)
			{
				roiNum += f.profit;
				roiDen += f.buyGross;
			}
		}
		if (roiDen > 0)
		{
			s.avgRoiPct = 100.0 * roiNum / roiDen;
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
		List<SlotExport> slots;
		List<String> deletes;
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
		// Slot state syncs even with nothing else pending: placements and
		// collections must reach the server for the next machine's handoff.
		if (fills.isEmpty() && flips.isEmpty() && !data.slotsDirty && data.pendingDeletes.isEmpty())
		{
			return null;
		}
		final SyncBatch b = new SyncBatch();
		b.accountHash = accountHash;
		b.fills = fills;
		b.flips = flips;
		b.deletes = new ArrayList<>(data.pendingDeletes);
		b.lots = copyLots(data.openLots);
		b.slots = new ArrayList<>();
		for (int i = 0; i < SLOTS; i++)
		{
			final SlotSnap s = data.slots[i];
			if (s == null)
			{
				continue;
			}
			final SlotExport e = new SlotExport();
			e.slot = i;
			e.itemId = s.itemId;
			e.qtySold = s.qtySold;
			e.total = s.total;
			e.price = s.price;
			e.spent = s.spent;
			e.state = s.state;
			e.placedMs = s.placedMs;
			e.updatedMs = s.updatedMs;
			b.slots.add(e);
		}
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
		if (b.deletes != null)
		{
			data.pendingDeletes.removeAll(b.deletes);
		}
		data.slotsDirty = false;
		save();
	}

	// ── persistence: atomic write on every mutation ──

	private File fileFor(long hash)
	{
		return new File(dir, "flips-" + hash + ".json");
	}

	private Data load()
	{
		Data d = read(fileFor(accountHash));
		if (d == null)
		{
			// Main file unreadable: quarantine it and fall back to the daily
			// backup before ever starting fresh.
			try
			{
				Files.move(fileFor(accountHash).toPath(), new File(dir, "flips-" + accountHash + ".bad.json").toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException ignored)
			{
			}
			d = read(backupFor(accountHash));
			if (d != null)
			{
				log.warn("flip log restored from daily backup");
			}
		}
		return d != null ? d : new Data();
	}

	private Data read(File f)
	{
		try
		{
			if (!f.exists())
			{
				return null;
			}
			final Data d = gson.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
			if (d == null)
			{
				return null;
			}
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
			if (d.fillKeys == null)
			{
				d.fillKeys = new ArrayList<>();
			}
			if (d.deletedFlipIds == null)
			{
				d.deletedFlipIds = new ArrayList<>();
			}
			if (d.pendingDeletes == null)
			{
				d.pendingDeletes = new ArrayList<>();
			}
			return d;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("flip log read failed: {}", f.getName(), e);
			return null;
		}
	}

	private File backupFor(long hash)
	{
		return new File(dir, "flips-" + hash + ".bak.json");
	}

	private void save()
	{
		try
		{
			if (!dir.exists() && !dir.mkdirs())
			{
				return;
			}
			final byte[] json = gson.toJson(data).getBytes(StandardCharsets.UTF_8);
			final Path tmp = new File(dir, "flips-" + accountHash + ".tmp").toPath();
			Files.write(tmp, json);
			Files.move(tmp, fileFor(accountHash).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			// Rolling daily backup: a logic bug that corrupts state can only
			// cost a day, not the ledger.
			final long now = System.currentTimeMillis();
			if (now - data.lastBackupMs > 86_400_000L)
			{
				data.lastBackupMs = now;
				Files.write(backupFor(accountHash).toPath(), json);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("flip log save failed", e);
		}
	}
}

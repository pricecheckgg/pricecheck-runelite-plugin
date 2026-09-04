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
 * The free flip log: an exact, crash-proof ledger of every Grand Exchange fill,
 * matched into flips. Design goals, each aimed at a documented weakness in the
 * incumbent trackers:
 *
 *  - Exact gp. Fills are deltas of the client's cumulative spent/quantity
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
	private static final long FLIP_MERGE_MS = 5 * 60_000L;   // fold a nibbling sell offer's fills into one flip
	// A margin check is a PAIR of probes: a qty-1 buy that crossed at once and
	// a qty-1 sell that crossed at once, minutes apart. One fast side alone is
	// how a real 1-item position (a bow, an orb) is normally opened or closed.
	private static final long CHECK_PAIR_MS = 10 * 60_000L;
	private static final int MAX_UNTRACKED = 200;

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
		// The uploading client's own lotsMutMs (its clock), 0 when the server
		// has no stamp for the row yet.
		long lotsMutMs;
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
		// Was the offer resting when this fill landed (1), crossed instantly
		// (0), or unknown (-1)? Resting fills surface on the OPPOSITE wiki
		// side, which the card's own-trade matcher scores on.
		transient int resting = -1;
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

	/** A sell that found no open lot of the item: real coins, unknown cost.
	 *  Kept visible in the log so a sale never silently disappears; never
	 *  counted as profit and never sent to the server as a flip. */
	static class Untracked
	{
		int itemId;
		String name;
		int qty;
		long gross;
		long tax;
		long ts;
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
		// One-time heal flag for the merge-resync fix. Absent in all pre-fix
		// saves, so it defaults false and the re-sync runs exactly once per
		// account, then stays true.
		boolean resyncFlipsV2;
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
		List<Untracked> untracked = new ArrayList<>();  // the sells behind that count, oldest first
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
		// The lotsMutMs the server last acknowledged; equal to lotsMutMs when
		// nothing local is unsynced.
		long lotsSyncedMutMs;
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
		List<Untracked> untracked;           // newest first
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
		// One-time heal: older builds synced each flip once and never re-sent it,
		// so any flip that grew via a merge left a stale, smaller total on the
		// server. Re-push every flip once (the server now upserts by clientFlipId)
		// so the web portfolio and leaderboard match the local log.
		if (!data.resyncFlipsV2)
		{
			for (final Flip f : data.flips)
			{
				f.synced = false;
			}
			data.resyncFlipsV2 = true;
			save();
		}
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
		// Re-arming (CONNECTION_LOST, then LOGGING_IN) keeps whatever is already
		// queued: those are real slot states and replay on release. Clearing
		// them here dropped fills on the floor.
		holdActive = true;
		holdStartedMs = System.currentTimeMillis();
	}

	/** Adopt the server's open lots only when they are provably newer than
	 *  ours. The stamp is the uploading client's own lotsMutMs, so it is a
	 *  client clock against a client clock. A row with no stamp yet (server
	 *  or jar from before the stamp) falls back to the row's server write
	 *  time, and then only while nothing local is unsynced: comparing the
	 *  server's clock to this machine's let a relog seconds after a fill
	 *  replace the fresh lot list with the copy uploaded just before it (a
	 *  buy's lot vanished, a sell's came back), and the next sell of that item
	 *  was logged as untracked instead of a flip. */
	private boolean adoptLots(RemoteState remote)
	{
		if (remote.lotsMutMs > 0)
		{
			return remote.lotsMutMs > data.lotsMutMs;
		}
		return remote.lotsUpdatedAt > data.lotsMutMs && data.lotsMutMs <= data.lotsSyncedMutMs;
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
			if (remote.lots != null && adoptLots(remote))
			{
				data.openLots = new ArrayList<>(remote.lots);
				data.lotsMutMs = remote.lotsMutMs > 0 ? remote.lotsMutMs : remote.lotsUpdatedAt;
				data.lotsSyncedMutMs = data.lotsMutMs;
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
			// Safety valves: a hung fetch must never dam events forever, and a
			// busy login must never overflow the queue and lose a fill. Either
			// way the queue replays now and this event goes straight through.
			if (System.currentTimeMillis() - holdStartedMs > HOLD_MAX_MS || held.size() >= HOLD_MAX_EVENTS)
			{
				releaseHold();
			}
			else
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
			// The client replays every live offer on login and after a hop, and
			// a replay looks exactly like a placement. Keep the placement time
			// already on record for the same offer, and never invent one inside
			// the login burst: a qty-1 sale that completed a couple of seconds
			// after a hop was being tagged as a margin check and kept out of
			// the totals.
			final boolean sameOffer = snap != null && snap.itemId == itemId && snap.price == price && snap.total == totalQty;
			final long placed = sameOffer ? snap.placedMs : (tick <= lastLoginTick + LOGIN_BURST_TICKS ? 0 : now);
			snap = new SlotSnap();
			snap.itemId = itemId;
			snap.qtySold = 0;
			snap.total = totalQty;
			snap.price = price;
			snap.spent = 0;
			snap.state = st.name();
			snap.placedMs = placed;
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
			f.resting = snap.placedMs > 0 ? (now - snap.placedMs > 30_000 ? 1 : 0) : -1;
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

	// Live fill events, in memory only: {itemId, unit price, ts ms, buy 1/0,
	// resting 1/0/-1}. The card's tape matches wiki prints against THESE,
	// because lot and flip aggregates anchor to their first fill and a
	// progressing offer's later fills drift out of any window anchored there.
	private final java.util.ArrayDeque<long[]> fillEvents = new java.util.ArrayDeque<>();

	synchronized List<long[]> recentFillEvents()
	{
		return new ArrayList<>(fillEvents);
	}

	private void ingest(Fill f)
	{
		if (f.qty > 0)
		{
			fillEvents.addLast(new long[]{f.itemId, f.gross / f.qty, f.ts, f.buy ? 1 : 0, f.resting});
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
			// Merge consecutive fills of the same item at the same unit price into
			// ONE open position. A buy that fills a nibble at a time was minting a
			// new lot per nibble (dozens of "open positions" for a single offer);
			// now it grows one lot instead. Each fill is still recorded the moment
			// it lands, so a crash loses nothing, and the earliest openedAt is kept
			// so the held time stays true. Match on item + exact unit price, which
			// is fixed for the life of one offer.
			final long unit = f.gross / f.qty;
			Lot merge = null;
			for (int i = data.openLots.size() - 1; i >= 0; i--)
			{
				final Lot l = data.openLots.get(i);
				if (l.itemId == f.itemId && l.qty > 0 && l.cost / l.qty == unit)
				{
					merge = l;
					break;
				}
			}
			if (merge != null)
			{
				merge.qty += f.qty;
				merge.cost += f.gross;
				merge.name = merge.name != null ? merge.name : f.name;
				merge.check = false;   // a multi-fill lot is not a clean 1-item margin check
			}
			else
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
		// Proportional slice of the sell for the matched quantity.
		final long sellGross = matched <= 0 ? 0 : (matched == f.qty ? f.gross : f.gross * matched / f.qty);
		final long tax = matched <= 0 ? 0 : (matched == f.qty ? f.tax : f.tax * matched / f.qty);
		if (remaining > 0)
		{
			data.untrackedSells += remaining;
			// Keep the sale itself on the log. Whatever lost the buy (a lot removed
			// by hand, a position opened before the plugin, a handoff that went
			// wrong), the coins were real and the user must be able to see them.
			final Untracked u = new Untracked();
			u.itemId = f.itemId;
			u.name = name;
			u.qty = remaining;
			u.gross = f.gross - sellGross;
			u.tax = f.tax - tax;
			u.ts = f.ts;
			data.untracked.add(u);
			while (data.untracked.size() > MAX_UNTRACKED)
			{
				data.untracked.remove(0);
			}
		}
		if (matched <= 0)
		{
			return;
		}
		final long profit = sellGross - tax - buyShare;
		// A margin check needs BOTH probes fast and close together; a real
		// position that merely closed on an instant sell is a flip.
		final boolean isCheck = f.check && checkLot && openedAt != Long.MAX_VALUE && f.ts - openedAt <= CHECK_PAIR_MS;

		// Fold a nibbling sell offer into ONE flip: if the newest flip is the same
		// item, closed moments ago, at the same sell unit price and on the same
		// side of profit, grow it rather than add a new +Xk row per unit. That flip
		// was already counted, so only the running gp totals move here; no total
		// changes, the log just stops spamming one line per fill.
		final Flip prev = data.flips.isEmpty() ? null : data.flips.get(data.flips.size() - 1);
		final boolean mergeInto = prev != null && prev.itemId == f.itemId && prev.qty > 0
			&& !prev.check && !isCheck
			&& f.ts - prev.closedAt <= FLIP_MERGE_MS
			&& prev.sellGross / prev.qty == sellGross / matched
			&& (prev.profit > 0) == (profit > 0);
		if (mergeInto)
		{
			prev.qty += matched;
			prev.buyGross += buyShare;
			prev.sellGross += sellGross;
			prev.tax += tax;
			prev.profit += profit;
			prev.closedAt = f.ts;
			// The flip grew after its last sync. Mark it unsynced so syncBatch
			// re-sends it: the server upserts by (userId, clientFlipId), so the
			// merged totals replace the stale pre-merge row instead of being
			// dropped. Without this the web portfolio/leaderboard undercount.
			prev.synced = false;
			final long op = openedAt == Long.MAX_VALUE ? f.ts : openedAt;
			if (op < prev.openedAt)
			{
				prev.openedAt = op;
			}
			data.allProfit += profit;
			data.allTax += tax;
			if (liveFill)
			{
				sessionProfit += profit;
			}
			return;
		}

		final Flip flip = new Flip();
		flip.id = f.id + ":fl";
		flip.itemId = f.itemId;
		flip.name = name;
		flip.qty = matched;
		flip.buyGross = buyShare;
		flip.sellGross = sellGross;
		flip.tax = tax;
		flip.profit = profit;
		flip.openedAt = openedAt == Long.MAX_VALUE ? f.ts : openedAt;
		flip.closedAt = f.ts;
		flip.check = isCheck;
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
		s.untracked = new ArrayList<>();
		for (int i = data.untracked.size() - 1; i >= 0 && s.untracked.size() < 20; i--)
		{
			s.untracked.add(data.untracked.get(i));
		}
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
		long lotsMutMs;   // stamp of the lots in this batch (this client's clock)
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
		b.lotsMutMs = data.lotsMutMs;
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
		if (b.lotsMutMs > data.lotsSyncedMutMs)
		{
			data.lotsSyncedMutMs = b.lotsMutMs;
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
			if (d.untracked == null)
			{
				d.untracked = new ArrayList<>();
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

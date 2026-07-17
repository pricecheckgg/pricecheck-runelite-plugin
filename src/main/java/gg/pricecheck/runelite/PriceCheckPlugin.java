package gg.pricecheck.runelite;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "PriceCheck",
	description = "Free exact flip tracker (profit, open positions, GE history import) plus PriceCheck's live flip board, offer advisor and GE click-to-fill for subscribers.",
	tags = {"flip", "margin", "grand exchange", "ge", "money", "pricecheck"}
)
public class PriceCheckPlugin extends Plugin
{
	private static final int SLOTS = 8;
	private static final int PANEL_REFRESH_SECONDS = 6;
	private static final int ADVISOR_REFRESH_SECONDS = 3;
	private static final int FLIP_LIMIT = 40;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private PriceCheckConfig config;

	@Inject
	private PriceCheckApiClient api;

	// Our OWN single poller thread. Blocking HTTP must never run on RuneLite's
	// shared ScheduledExecutorService — that would stall every other plugin.
	private ScheduledExecutorService poller;

	private PriceCheckPanel panel;
	private NavigationButton navButton;
	private OfferAdvisorOverlay advisorOverlay;
	// Shift-click on the advisor's [-]/[+] button collapses/expands it.
	private final net.runelite.client.input.MouseAdapter advisorMouse = new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
		{
			if (advisorOverlay != null
				&& advisorOverlay.handleClick(e.getPoint(), client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT)))
			{
				e.consume();
			}
			return e;
		}
	};
	private OfferAdvisorSlotOverlay slotOverlay;
	private OfferSetupOverlay setupOverlay;
	private ScheduledFuture<?> panelTask;
	private ScheduledFuture<?> advisorTask;
	private ScheduledFuture<?> telemetryTask;

	// The player's own offer fills, batched for the measured fill model
	// (opt-out via "Contribute market data").
	private final TelemetryCollector telemetry = new TelemetryCollector();

	@Inject
	private com.google.gson.Gson gson;

	// The FREE flip log: exact fills -> FIFO flips, local-first, synced when a
	// key is present. lastLoginTick marks the login burst window so aggregated
	// offline fills are flagged instead of trusted as instant.
	private FlipLogEngine flipLog;
	private ScheduledFuture<?> flipSyncTask;
	private volatile int lastLoginTick = -10;
	// GE chatbox integrations: click-to-fill prices + search suggestions.
	private GeChatboxHelper geHelper;

	// Liquid capital (coins + platinum tokens) snapshotted from container events
	// on the client thread; reported to the planner when it changes. The bank is
	// only readable once opened, so nothing is sent before bankSeen — inventory
	// pocket change alone would overwrite the real number on the server.
	private static final int COINS_ID = 995;
	private static final int PLAT_ID = 13204;
	private volatile long bankCoins, bankPlat, invCoins, invPlat;
	private volatile boolean bankSeen, capitalDirty;
	private long lastSentCapital = -1;

	// GE slots, snapshotted on the client thread (offer events) so the background
	// poller and the overlay render can read them without touching the client.
	private final AtomicReferenceArray<TrackedOffer> tracked = new AtomicReferenceArray<>(SLOTS);
	// Live market data for the items you have offers on. Replaced whole on refresh.
	private volatile Map<Integer, FlipData> liveByItem = Collections.emptyMap();
	// The advice the overlay renders.
	private volatile List<OfferAdvice> advice = Collections.emptyList();

	@Provides
	PriceCheckConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PriceCheckConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new PriceCheckPanel(new PriceCheckPanel.Listener()
		{
			@Override
			public void onTrack(FlipData f)
			{
				poller.execute(() ->
				{
					api.addTracked(config.apiKey(), f.getGeId(), f.getBuy(), f.getName(), f.getProfit());
					refreshPanel();
				});
			}

			@Override
			public void onUntrack(int geId)
			{
				poller.execute(() ->
				{
					api.removeTracked(config.apiKey(), geId);
					refreshPanel();
				});
			}

			@Override
			public void onSearch(String query)
			{
				poller.execute(() ->
				{
					final PriceCheckApiClient.FlipsResult r = api.searchItems(config.apiKey(), query);
					final PriceCheckPanel p = panel;
					if (p != null)
					{
						p.setSearchResults(query, r);
					}
				});
			}

			@Override
			public void onFetchAccount()
			{
				poller.execute(() ->
				{
					final AccountInfo acct = api.getAccount(config.apiKey());
					final PriceCheckPanel p = panel;
					if (p != null)
					{
						p.setAccount(acct);
					}
				});
			}

			@Override
			public void onBuildPlan(long capital, int slots, int accounts)
			{
				poller.execute(() ->
				{
					final PriceCheckApiClient.PlanResult r = api.getPlan(config.apiKey(), capital, slots, accounts);
					final PriceCheckPanel p = panel;
					if (p != null)
					{
						p.setPlan(r);
					}
				});
			}


			@Override
			public void onDeleteFlip(String flipId)
			{
				deleteFlip(flipId);
			}

			@Override
			public void onDeleteLot(int itemId, int qty, long cost, long openedAt)
			{
				deleteLot(itemId, qty, cost, openedAt);
			}
		}, itemManager, configManager, config);
		navButton = NavigationButton.builder()
			.tooltip("PriceCheck")
			.icon(loadIcon())
			.priority(7)
			.panel(panel)
			.build();
		if (config.showPanel())
		{
			clientToolbar.addNavigation(navButton);
		}

		geHelper = new GeChatboxHelper(client, clientThread, config, this);

		advisorOverlay = new OfferAdvisorOverlay(client, this, config, configManager);
		overlayManager.add(advisorOverlay);
		mouseManager.registerMouseListener(advisorMouse);
		slotOverlay = new OfferAdvisorSlotOverlay(client, this, config);
		overlayManager.add(slotOverlay);
		setupOverlay = new OfferSetupOverlay(client, this, config);
		overlayManager.add(setupOverlay);

		poller = Executors.newSingleThreadScheduledExecutor(r ->
		{
			final Thread t = new Thread(r, "pricecheck-poller");
			t.setDaemon(true);
			return t;
		});
		flipLog = new FlipLogEngine(gson, net.runelite.client.RuneLite.RUNELITE_DIR);
		clientThread.invokeLater(() ->
		{
			flipLog.setAccount(client.getAccountHash());
			// With a key, hold the seeded events until the server handoff state
			// is adopted: a fresh install on a second machine must not treat
			// offers another machine already recorded as new progress.
			if (syncEnabled() && client.getAccountHash() != -1)
			{
				flipLog.beginLoginHold();
				final FlipLogEngine engine = flipLog;
				poller.execute(() -> engine.adoptRemote(api.getSlots(config.apiKey(), engine.getAccountHash())));
			}
			seedOffers();
			// Feed current slot states into the log too: a plugin enabled
			// mid-session must capture offers that filled before it existed
			// (the first-sight aggregate path), or their collection would
			// arrive as an EMPTY the engine has no snapshot for.
			final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null && client.getAccountHash() != -1)
			{
				for (int slot = 0; slot < offers.length && slot < SLOTS; slot++)
				{
					final GrandExchangeOffer o = offers[slot];
					if (o == null || o.getItemId() <= 0)
					{
						continue;
					}
					String name = null;
					try
					{
						name = itemManager.getItemComposition(o.getItemId()).getName();
					}
					catch (RuntimeException ignored)
					{
					}
					flipLog.onOffer(slot, o, client.getGameState(), client.getTickCount(), client.getTickCount(), name);
				}
			}
		});
		panelTask = poller.scheduleWithFixedDelay(this::refreshPanel, 0, PANEL_REFRESH_SECONDS, TimeUnit.SECONDS);
		advisorTask = poller.scheduleWithFixedDelay(this::refreshAdvisor, 1, ADVISOR_REFRESH_SECONDS, TimeUnit.SECONDS);
		telemetryTask = poller.scheduleWithFixedDelay(this::flushTelemetry, 30, 30, TimeUnit.SECONDS);
		flipSyncTask = poller.scheduleWithFixedDelay(this::syncFlipLog, 20, 30, TimeUnit.SECONDS);
	}

	// The flip log only talks to the server with a key present AND the sync
	// toggle on (Hub rule: third-party submission is opt-in). Off = fully
	// local: no push, no handoff fetch.
	private boolean syncEnabled()
	{
		final String key = config.apiKey();
		return key != null && !key.trim().isEmpty() && config.syncFlipLog();
	}

	private void syncFlipLog()
	{
		final FlipLogEngine engine = flipLog;
		if (engine == null || !syncEnabled())
		{
			return;
		}
		final FlipLogEngine.SyncBatch batch = engine.syncBatch();
		if (batch != null && api.postFills(config.apiKey(), batch))
		{
			engine.onSyncSuccess(batch);
		}
	}

	private void flushTelemetry()
	{
		if (config.contributeData())
		{
			final java.util.List<java.util.Map<String, Object>> batch = telemetry.drain();
			if (!batch.isEmpty())
			{
				api.postTelemetry(config.apiKey(), batch);
			}
		}
		flushCapital();
	}

	private void flushCapital()
	{
		if (!config.autoCapital() || !bankSeen || !capitalDirty)
		{
			return;
		}
		final long coins = bankCoins + invCoins;
		final long plat = bankPlat + invPlat;
		final long total = coins + plat * 1000L;
		if (total == lastSentCapital)
		{
			capitalDirty = false;
			return;
		}
		if (api.postCapital(config.apiKey(), coins, plat))
		{
			lastSentCapital = total;
			capitalDirty = false;
		}
	}

	@Subscribe
	public void onVarClientIntChanged(net.runelite.api.events.VarClientIntChanged event)
	{
		final GeChatboxHelper g = geHelper;
		if (g != null)
		{
			g.onVarClientIntChanged(event);
		}
	}

	@Subscribe
	public void onScriptPostFired(net.runelite.api.events.ScriptPostFired event)
	{
		final GeChatboxHelper g = geHelper;
		if (g != null)
		{
			g.onScriptPostFired(event);
		}
	}

	@Subscribe
	public void onGrandExchangeSearched(net.runelite.api.events.GrandExchangeSearched event)
	{
		final GeChatboxHelper g = geHelper;
		if (g != null)
		{
			g.onGrandExchangeSearched(event);
		}
	}

	@Subscribe
	public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event)
	{
		if (!config.autoCapital() || event.getItemContainer() == null)
		{
			return;
		}
		final int id = event.getContainerId();
		final boolean bank = id == net.runelite.api.InventoryID.BANK.getId();
		if (!bank && id != net.runelite.api.InventoryID.INVENTORY.getId())
		{
			return;
		}
		long coins = 0, plat = 0;
		for (final net.runelite.api.Item it : event.getItemContainer().getItems())
		{
			if (it == null)
			{
				continue;
			}
			if (it.getId() == COINS_ID)
			{
				coins += it.getQuantity();
			}
			else if (it.getId() == PLAT_ID)
			{
				plat += it.getQuantity();
			}
		}
		if (bank)
		{
			bankCoins = coins;
			bankPlat = plat;
			bankSeen = true;
		}
		else
		{
			invCoins = coins;
			invPlat = plat;
		}
		capitalDirty = true;
		// Prefill the Plan tab live (only meaningful once the bank is readable).
		final PriceCheckPanel p = panel;
		if (p != null && bankSeen)
		{
			p.setDetectedCapital((bankCoins + invCoins) + (bankPlat + invPlat) * 1000L);
		}
	}

	@Override
	protected void shutDown()
	{
		if (panelTask != null)
		{
			panelTask.cancel(true);
			panelTask = null;
		}
		if (advisorTask != null)
		{
			advisorTask.cancel(true);
			advisorTask = null;
		}
		if (telemetryTask != null)
		{
			telemetryTask.cancel(true);
			telemetryTask = null;
		}
		if (flipSyncTask != null)
		{
			flipSyncTask.cancel(true);
			flipSyncTask = null;
		}
		if (geHelper != null)
		{
			// Restores the GE search chatbox if suggestions were active; a
			// hidden echo and the sentinel must not outlive the plugin.
			geHelper.shutdown();
			geHelper = null;
		}
		flipLog = null;
		telemetry.clear();
		if (poller != null)
		{
			poller.shutdownNow();
			poller = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		if (advisorOverlay != null)
		{
			overlayManager.remove(advisorOverlay);
			mouseManager.unregisterMouseListener(advisorMouse);
			advisorOverlay = null;
		}
		if (slotOverlay != null)
		{
			overlayManager.remove(slotOverlay);
			slotOverlay = null;
		}
		if (setupOverlay != null)
		{
			overlayManager.remove(setupOverlay);
			setupOverlay = null;
		}
		for (int i = 0; i < SLOTS; i++)
		{
			tracked.set(i, null);
		}
		liveByItem = Collections.emptyMap();
		advice = Collections.emptyList();
		panel = null;
	}

	// ── side panel: the whole best-flips board ──
	// Resolve any log entries that only carry an item id (old records from
	// before the adoption serialization fix). Ids resolve on the client thread;
	// the answers heal the engine, persist, and push on the next sync.
	private void healItemNames()
	{
		final FlipLogEngine engine = flipLog;
		if (engine == null)
		{
			return;
		}
		final java.util.Set<Integer> ids = engine.idsMissingNames();
		if (ids.isEmpty())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			final java.util.Map<Integer, String> names = new java.util.HashMap<>();
			for (final int id : ids)
			{
				try
				{
					final String n = itemManager.getItemComposition(id).getName();
					if (n != null && !n.isEmpty() && !"null".equals(n))
					{
						names.put(id, n);
					}
				}
				catch (RuntimeException ignored)
				{
				}
			}
			if (!names.isEmpty())
			{
				poller.execute(() -> engine.applyNames(names));
			}
		});
	}

	/** Panel right-click: delete one flip from the log (and the server). */
	void deleteFlip(String flipId)
	{
		poller.execute(() ->
		{
			final FlipLogEngine engine = flipLog;
			if (engine != null && engine.deleteFlip(flipId))
			{
				syncFlipLog();
				refreshPanel();
			}
		});
	}

	/** Panel right-click: remove one open position from tracking. */
	void deleteLot(int itemId, int qty, long cost, long openedAt)
	{
		poller.execute(() ->
		{
			final FlipLogEngine engine = flipLog;
			if (engine != null && engine.deleteLot(itemId, qty, cost, openedAt))
			{
				syncFlipLog();
				refreshPanel();
			}
		});
	}

	private void refreshPanel()
	{
		final PriceCheckPanel p = panel;
		if (p == null)
		{
			return;
		}
		healItemNames();
		final String key = config.apiKey();
		final PriceCheckApiClient.FlipsResult flips = api.getFlips(key, FLIP_LIMIT);
		final PriceCheckApiClient.TrackedResult tracked = api.getTracked(key);
		p.update(flips, tracked, config.minEvPerHrK());
		final FlipLogEngine engine = flipLog;
		if (engine != null)
		{
			p.setFlipLog(engine.summary(), syncEnabled());
		}
		final GeChatboxHelper g = geHelper;
		if (g != null)
		{
			g.update(
				flips.state == PriceCheckApiClient.AuthState.OK ? flips.flips : null,
				tracked != null && tracked.state == PriceCheckApiClient.AuthState.OK ? tracked.tracked : null);
		}
	}

	// ── offer advisor ──
	private void seedOffers()
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (int slot = 0; slot < offers.length && slot < SLOTS; slot++)
			{
				if (offers[slot] != null)
				{
					tracked.set(slot, TrackedOffer.of(slot, offers[slot]));
				}
			}
		}
		poller.execute(this::refreshAdvisor);
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged event)
	{
		final net.runelite.api.GameState gs = event.getGameState();
		final boolean syncs = syncEnabled();
		if (gs == net.runelite.api.GameState.LOGGING_IN || gs == net.runelite.api.GameState.HOPPING
			|| gs == net.runelite.api.GameState.CONNECTION_LOST)
		{
			lastLoginTick = client.getTickCount();
			if (flipLog != null && syncs)
			{
				// Hold the coming login burst until the freshest snapshots from
				// the machine that traded last have been adopted.
				flipLog.beginLoginHold();
			}
		}
		else if (gs == net.runelite.api.GameState.LOGIN_SCREEN && syncs)
		{
			// Flush immediately at logout so the next machine's handoff fetch
			// sees this session's final slot state, not a 30s-stale one.
			poller.execute(this::syncFlipLog);
		}
		else if (gs == net.runelite.api.GameState.LOGGED_IN && flipLog != null)
		{
			flipLog.setAccount(client.getAccountHash());
			final FlipLogEngine engine = flipLog;
			if (syncs)
			{
				poller.execute(() ->
				{
					// Adopt fresher server state, then replay the held events.
					// getSlots failing (offline etc.) still releases the hold.
					engine.adoptRemote(api.getSlots(config.apiKey(), engine.getAccountHash()));
				});
			}
			else
			{
				engine.releaseHold();
			}
		}
	}

	@Subscribe
	public void onAccountHashChanged(net.runelite.api.events.AccountHashChanged event)
	{
		if (flipLog != null)
		{
			flipLog.setAccount(client.getAccountHash());
		}
	}


	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		// Fires on the client thread; snapshot immediately, then recompute off-thread.
		tracked.set(event.getSlot(), TrackedOffer.of(event.getSlot(), event.getOffer()));
		if (flipLog != null)
		{
			flipLog.setAccount(client.getAccountHash());
			String name = null;
			try
			{
				if (event.getOffer() != null && event.getOffer().getItemId() > 0)
				{
					name = itemManager.getItemComposition(event.getOffer().getItemId()).getName();
				}
			}
			catch (RuntimeException ignored)
			{
			}
			flipLog.onOffer(event.getSlot(), event.getOffer(), client.getGameState(),
				client.getTickCount(), lastLoginTick, name);
		}
		if (config.contributeData())
		{
			// Relog/world-hop burst: the client blanks every slot with EMPTY and
			// replays the real offers after login. Passing those EMPTYs through
			// would wipe the collector's dedupe fingerprints and re-queue every
			// live offer as a phantom event on each hop (the same narrow filter
			// RuneLite's core GE plugin uses). A logged-in EMPTY — a genuinely
			// cleared slot — still flows.
			final boolean hopBlank = event.getOffer() != null
				&& event.getOffer().getState() == net.runelite.api.GrandExchangeOfferState.EMPTY
				&& client.getGameState() != net.runelite.api.GameState.LOGGED_IN;
			if (!hopBlank)
			{
				telemetry.offer(event.getSlot(), event.getOffer());
			}
		}
		poller.execute(this::recomputeAdvice);
	}

	// True while the Grand Exchange offer grid (465:0) is on screen. Called from the
	// overlay render (client thread), so touching the widget tree here is safe.
	boolean isGrandExchangeOpen()
	{
		final net.runelite.api.widgets.Widget w = client.getWidget(465, 0);
		return w != null && !w.isHidden();
	}

	// The item currently open in the GE "Set up offer" screen — the setup overlay
	// notes it here so the poller pulls its live price for the "type X" hint.
	private volatile int setupItemId = 0;

	void noteSetupItem(int geId)
	{
		if (geId != setupItemId)
		{
			setupItemId = geId;
			if (poller != null && geId > 0)
			{
				poller.execute(this::refreshAdvisor);   // fetch its price now, no 3s wait
			}
		}
	}

	// Live market row for one item (from the poller's cache), or null if not loaded.
	FlipData liveFor(int geId)
	{
		return liveByItem.get(geId);
	}

	// Fetch live data for the items you have offers on, then recompute advice.
	private void refreshAdvisor()
	{
		final Set<Integer> ids = new LinkedHashSet<>();
		for (int slot = 0; slot < SLOTS; slot++)
		{
			final TrackedOffer t = tracked.get(slot);
			if (t != null && t.isRelevant())
			{
				ids.add(t.getItemId());
			}
		}
		final int setup = setupItemId;   // the item open in the Set-up-offer screen
		if (setup > 0)
		{
			ids.add(setup);
		}
		if (ids.isEmpty())
		{
			liveByItem = Collections.emptyMap();
		}
		else
		{
			final Map<Integer, FlipData> fresh = api.getItems(config.apiKey(), ids);
			// Keep the last good data on a transient blip so advice doesn't flicker.
			if (!fresh.isEmpty())
			{
				liveByItem = fresh;
			}
		}
		recomputeAdvice();
	}

	private void recomputeAdvice()
	{
		final Map<Integer, FlipData> live = liveByItem;
		final List<OfferAdvice> out = new ArrayList<>();
		for (int slot = 0; slot < SLOTS; slot++)
		{
			final TrackedOffer t = tracked.get(slot);
			if (t == null || !t.isRelevant())
			{
				continue;
			}
			final OfferAdvice a = OfferAdvisor.advise(t, live.get(t.getItemId()));
			if (a != null)
			{
				out.add(a);
			}
		}
		advice = Collections.unmodifiableList(out);
	}

	List<OfferAdvice> getAdvice()
	{
		return advice;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!PriceCheckConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		final String key = e.getKey();
		final PriceCheckPanel p = panel;
		if ("apiKey".equals(key))
		{
			poller.execute(() ->
			{
				refreshPanel();
				refreshAdvisor();
				if (p != null) { p.setAccount(api.getAccount(config.apiKey())); }
			});
		}
		else if ("minEvPerHrK".equals(key))
		{
			if (p != null) { p.syncSettings(); }
			poller.execute(this::refreshPanel);
		}
		else if ("showAdvisor".equals(key))
		{
			if (p != null) { p.syncSettings(); }
		}
		else if ("showPanel".equals(key) && navButton != null)
		{
			if (config.showPanel())
			{
				clientToolbar.addNavigation(navButton);
			}
			else
			{
				clientToolbar.removeNavigation(navButton);
			}
		}
	}

	private BufferedImage loadIcon()
	{
		try
		{
			final BufferedImage img = ImageUtil.loadImageResource(getClass(), "/icon.png");
			if (img != null)
			{
				return img;
			}
		}
		catch (RuntimeException ignored)
		{
			// icon.png not bundled yet — fall through to a blank placeholder.
		}
		return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
	}
}

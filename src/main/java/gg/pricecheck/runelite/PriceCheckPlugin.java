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
	description = "Free exact flip tracker (profit, open positions, web portfolio sync) plus PriceCheck's live flip board, offer advisor and GE click-to-fill for subscribers.",
	tags = {"flip", "margin", "grand exchange", "ge", "money", "pricecheck"}
)
public class PriceCheckPlugin extends Plugin
{
	// Displayed version. Source of truth for what the panel shows: the Hub
	// rebuilds with its own build.gradle, so a gradle-only version never
	// reaches users. Keep build.gradle's version in sync for the dev jar name.
	public static final String VERSION = "0.5.1";

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
	private net.runelite.client.input.KeyManager keyManager;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private PriceCheckConfig config;

	@Inject
	private PriceCheckApiClient api;

	// Our OWN single poller thread. Blocking HTTP must never run on RuneLite's
	// shared ScheduledExecutorService: that would stall every other plugin.
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
			final boolean shift = client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT);
			if (advisorOverlay != null && advisorOverlay.handleClick(e.getPoint(), shift))
			{
				e.consume();
			}
			else if (geCardOverlay != null && geCardOverlay.handleClick(e.getPoint(), shift))
			{
				e.consume();
			}
			return e;
		}
	};
	// Hotkey: pre-fill our recommended price into an open GE buy/sell price box
	// (the player still presses Enter, exactly like clicking the price line).
	// Unbound by default; reads config.geAutofillHotkey() live, so it stays
	// inert until the user sets a key and does nothing off-context.
	private final net.runelite.client.util.HotkeyListener autofillHotkey =
		new net.runelite.client.util.HotkeyListener(() -> config.geAutofillHotkey())
		{
			@Override
			public void hotkeyPressed()
			{
				final GeChatboxHelper g = geHelper;
				if (g == null)
				{
					return;
				}
				// Block-body Runnable (not the BooleanSupplier overload): fire
				// once, never retry-until-true when no price box is open.
				clientThread.invoke(() -> { g.fillRecommendedPrice(); });
			}
		};
	private OfferAdvisorSlotOverlay slotOverlay;
	private OfferSetupOverlay setupOverlay;
	private GeItemCardOverlay geCardOverlay;
	private GeOffersPanelOverlay offersPanelOverlay;
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
	// only readable once opened, so nothing is sent before bankSeen: inventory
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
	// The tracked list keyed by item, refreshed with the panel, so overlays can
	// look up a position's break-even floor without another request.
	private volatile Map<Integer, TrackedItem> trackedById = Collections.emptyMap();
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
					trialActive = acct != null && acct.isTrial();
					final PriceCheckPanel p = panel;
					if (p != null)
					{
						p.setAccount(acct);
					}
					// A trial learned after login still binds the account.
					if (trialActive)
					{
						maybeBindTrial();
					}
				});
			}

			@Override
			public void onBuildPlan(long capital, int slots, int accounts, int hours)
			{
				poller.execute(() ->
				{
					final PriceCheckApiClient.PlanResult r = api.getPlan(config.apiKey(), capital, slots, accounts, hours);
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

			@Override
			public void onOpenPluginConfig()
			{
				openPluginConfig();
			}
		}, itemManager, configManager, config);
		panel.setSeriesSupplier(this::cardSeriesFor);
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
		keyManager.registerKeyListener(autofillHotkey);
		slotOverlay = new OfferAdvisorSlotOverlay(client, this, config);
		overlayManager.add(slotOverlay);
		setupOverlay = new OfferSetupOverlay(client, this, config);
		overlayManager.add(setupOverlay);
		geCardOverlay = new GeItemCardOverlay(client, this, config, configManager);
		overlayManager.add(geCardOverlay);
		offersPanelOverlay = new GeOffersPanelOverlay(client, this);
		overlayManager.add(offersPanelOverlay);

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
		pushAlertMode();   // sync the chosen cadence for an already-set key
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

	/** Opens RuneLite's own config panel at this plugin, the same way the
	 * overlay right-click "Configure" entry does: ConfigPlugin listens for
	 * this event and needs only an overlay that knows its owning plugin. */
	void openPluginConfig()
	{
		final OfferAdvisorOverlay target = advisorOverlay;
		if (target == null)
		{
			return;
		}
		eventBus.post(new net.runelite.client.events.OverlayMenuClicked(
			new net.runelite.client.ui.overlay.OverlayMenuEntry(
				net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "PriceCheck"),
			target));
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
			keyManager.unregisterKeyListener(autofillHotkey);
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
		if (geCardOverlay != null)
		{
			overlayManager.remove(geCardOverlay);
			geCardOverlay = null;
		}
		if (offersPanelOverlay != null)
		{
			overlayManager.remove(offersPanelOverlay);
			offersPanelOverlay = null;
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
		marketDataOk = flips.state == PriceCheckApiClient.AuthState.OK;
		final PriceCheckApiClient.TrackedResult tracked = api.getTracked(key);
		if (tracked != null && tracked.state == PriceCheckApiClient.AuthState.OK && tracked.tracked != null)
		{
			final java.util.Map<Integer, TrackedItem> byId = new java.util.HashMap<>();
			for (final TrackedItem t : tracked.tracked)
			{
				byId.put(t.getGeId(), t);
			}
			trackedById = byId;
		}
		else
		{
			// Key lapsed / logged out / server error: drop the cache so a stale
			// floor from the previous session can never colour the setup ring.
			trackedById = Collections.emptyMap();
		}
		p.update(flips, tracked, config.minEvPerHrK());
		// The Catch tab reads the movers sibling array off the same flips poll;
		// gate it on a live board so a lapsed key never shows stale dumps.
		p.setCatches(flips.state == PriceCheckApiClient.AuthState.OK ? flips.catches : Collections.emptyList());
		final FlipLogEngine engine = flipLog;
		if (engine != null)
		{
			final FlipLogEngine.Summary logSummary = engine.summary();
			p.setFlipLog(logSummary, syncEnabled());
			openLots = logSummary.openLots != null ? logSummary.openLots : Collections.emptyList();
			recentFlips = logSummary.recent != null ? logSummary.recent : Collections.emptyList();
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
			// Trial binding: ties this game account to an active trial. Sent
			// ONLY while a trial is active; the trial terms the user accepted
			// at checkout disclose the per-account binding.
			lastLoginHash = client.getAccountHash();
			if (trialActive)
			{
				poller.execute(this::maybeBindTrial);
			}
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
		noteSlotActivity(event.getSlot(), event.getOffer());
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
			// RuneLite's core GE plugin uses). A logged-in EMPTY: a genuinely
			// cleared slot: still flows.
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

	/** The GE grid window bounds (465:2, else 465:0), the anchor both the
	 *  mini-cards and the offers board dock against. Null when it isn't up. */
	java.awt.Rectangle geGridBounds()
	{
		net.runelite.api.widgets.Widget w = client.getWidget(465, 2);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(465, 0);
		}
		return w != null ? w.getBounds() : null;
	}

	/**
	 * The single, order-independent gate for the right-of-GE active-offers board.
	 * On only when the board is enabled, market data is live, the GE grid
	 * overview is on screen (no slot open, no set-up panel), at least one offer is
	 * relevant, and the fixed board width fits in the right dock. Read by
	 * GeItemCardOverlay to yield its right column, so it must stay a pure
	 * client+config state function, never a "painted this frame" flag.
	 */
	boolean geOffersPanelVisible()
	{
		if (!config.geOffersPanel() || !marketDataOk() || !isGrandExchangeOpen())
		{
			return false;
		}
		// A selected slot means a slot screen, not the overview; only count it
		// when the offer there is real (mirrors GeItemCardOverlay).
		final int slotVal = client.getVarbitValue(4439);
		if (slotVal >= 1 && slotVal <= 8)
		{
			final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers != null && offers.length >= slotVal)
			{
				final GrandExchangeOffer o = offers[slotVal - 1];
				if (o != null && o.getState() != net.runelite.api.GrandExchangeOfferState.EMPTY && o.getItemId() > 0)
				{
					return false;
				}
			}
		}
		for (final int child : new int[]{15, 26})
		{
			final net.runelite.api.widgets.Widget w = client.getWidget(465, child);
			if (w != null && !w.isHidden())
			{
				return false;
			}
		}
		boolean anyRelevant = false;
		for (int slot = 0; slot < SLOTS; slot++)
		{
			final TrackedOffer t = tracked.get(slot);
			if (t != null && t.isRelevant())
			{
				anyRelevant = true;
				break;
			}
		}
		if (!anyRelevant)
		{
			return false;
		}
		final java.awt.Rectangle b = geGridBounds();
		if (b == null)
		{
			return false;
		}
		return b.x + b.width + 8 + GeOffersPanelOverlay.W <= client.getCanvasWidth() - 4;
	}

	// The item currently open in the GE "Set up offer" screen: the setup overlay
	// notes it here so the poller pulls its live price for the "type X" hint.
	private volatile int setupItemId = 0;

	// ── GE item card state ──
	// The item whose offer screen is open right now (setup or status view),
	// its cached series, and the prints this client has watched arrive: every
	// advisor poll where the item's insta price moved is one observed trade.
	private volatile int viewedItemId = 0;
	// Small shared series cache: the GE card and the panel's expanded charts
	// both read through it. LRU by access order, tiny cap, 60s freshness.
	private static final class CachedSeries
	{
		final PriceCheckApiClient.SeriesData data;
		final long atMs;

		CachedSeries(PriceCheckApiClient.SeriesData data, long atMs)
		{
			this.data = data;
			this.atMs = atMs;
		}
	}
	private final Map<Integer, CachedSeries> seriesCache = new java.util.LinkedHashMap<Integer, CachedSeries>(8, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Integer, CachedSeries> e)
		{
			return size() > 8;
		}
	};
	private final Set<Integer> seriesFetching = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
	// Failed fetches back off so a missing series or a network flap cannot
	// queue a request per render frame on the single poller thread.
	private final Map<Integer, Long> seriesFailedAt = java.util.Collections.synchronizedMap(new java.util.HashMap<>());
	// Prints per item: every advisor poll where an item's insta price moved is
	// one observed trade. Tracks all items the poll fetches (your offers plus
	// the viewed item), bounded per item, pruned when an item leaves the set.
	private final Map<Integer, java.util.ArrayDeque<GeItemInfoPainter.Print>> cardPrints = new java.util.HashMap<>();
	private final java.util.Set<Integer> printsSeeded = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Map<Integer, long[]> printsLast = new java.util.HashMap<>();

	/** Called from the card overlay's render with whatever item the open GE
	 * screen shows (0 = none). Schedules the series fetch on change. */
	void noteViewedItem(int geId)
	{
		if (geId == viewedItemId)
		{
			return;
		}
		viewedItemId = geId;
		if (poller != null && geId > 0)
		{
			poller.execute(this::refreshCardSeries);
			poller.execute(this::refreshAdvisor);
		}
	}

	private void refreshCardSeries()
	{
		refreshSeries(viewedItemId);
	}

	private void refreshSeries(int geId)
	{
		if (geId <= 0 || !seriesFetching.add(geId))
		{
			return;
		}
		try
		{
			synchronized (seriesCache)
			{
				final CachedSeries c = seriesCache.get(geId);
				if (c != null && System.currentTimeMillis() - c.atMs < 60_000L)
				{
					return;
				}
			}
			final PriceCheckApiClient.SeriesData d = api.fetchSeries(config.apiKey(), geId);
			if (d != null)
			{
				synchronized (seriesCache)
				{
					seriesCache.put(geId, new CachedSeries(d, System.currentTimeMillis()));
				}
				seriesFailedAt.remove(geId);
			}
			else
			{
				seriesFailedAt.put(geId, System.currentTimeMillis());
			}
		}
		finally
		{
			seriesFetching.remove(geId);
		}
	}

	/** Cached series for any surface; schedules a refresh when absent or stale. */
	PriceCheckApiClient.SeriesData cardSeriesFor(int geId)
	{
		if (geId <= 0)
		{
			return null;
		}
		CachedSeries c;
		synchronized (seriesCache)
		{
			c = seriesCache.get(geId);
		}
		final Long failed = seriesFailedAt.get(geId);
		final boolean coolingDown = failed != null && System.currentTimeMillis() - failed < 45_000L;
		if (poller != null && !coolingDown && (c == null || System.currentTimeMillis() - c.atMs > 60_000L))
		{
			poller.execute(() -> refreshSeries(geId));
		}
		return c != null ? c.data : null;
	}

	java.util.List<GeItemInfoPainter.Print> cardPrintsFor(int geId)
	{
		maybeSeedPrints(geId);
		synchronized (cardPrints)
		{
			final java.util.ArrayDeque<GeItemInfoPainter.Print> q = cardPrints.get(geId);
			if (q == null)
			{
				return Collections.emptyList();
			}
			// Re-stamp ownership on every read instead of freezing it at
			// insert: after a restart the tape seeds before the flip log's
			// lists are populated, and a stamp-once tape would then call your
			// own history someone else's forever. A couple dozen prints
			// against a handful of lots and flips is nothing per frame.
			restampPrints(geId, q);
			return new ArrayList<>(q);
		}
	}

	/**
	 * A fresh card should never open on an empty tape: when no live prints have
	 * been watched for this item yet, backfill from the cached day series so
	 * "Last trades seen" is there the moment the item page opens. Live prints
	 * then stack on top. Runs once per item per session.
	 */
	private void maybeSeedPrints(int geId)
	{
		final CachedSeries c;
		synchronized (seriesCache)
		{
			c = seriesCache.get(geId);
		}
		// Short-circuit order matters: the seeded flag is only consumed once a
		// series actually exists, so an item keeps retrying until one is cached.
		if (c == null || c.data == null || c.data.ts == null || !printsSeeded.add(geId))
		{
			return;
		}
		final PriceCheckApiClient.SeriesData d = c.data;
		// Reach back a week, not 12h: a high-value item like a Harmonised orb only
		// trades a few times a day, so a short window leaves the tape near-empty.
		// The loop still runs newest-first, so a liquid item fills from the last
		// hour; an illiquid one just keeps pulling back until it has ten prints.
		final long cutoff = System.currentTimeMillis() / 1000L - 7 * 24 * 3600;
		final java.util.List<GeItemInfoPainter.Print> seeds = new ArrayList<>();
		for (int i = d.ts.length - 1; i >= 0 && seeds.size() < 10; i--)
		{
			if (d.ts[i] < cutoff)
			{
				break;
			}
			// The window's averages stand in for its trades; one entry per
			// traded side, stamped near the window's end.
			final long ts = d.ts[i] + 240;
			if (d.hv != null && d.hv[i] > 0 && d.ah[i] > 0)
			{
				seeds.add(new GeItemInfoPainter.Print(ts, d.ah[i], true));
			}
			if (seeds.size() < 10 && d.lv != null && d.lv[i] > 0 && d.al[i] > 0)
			{
				seeds.add(new GeItemInfoPainter.Print(ts, d.al[i], false));
			}
		}
		if (seeds.isEmpty())
		{
			return;
		}
		synchronized (cardPrints)
		{
			final java.util.ArrayDeque<GeItemInfoPainter.Print> q =
				cardPrints.computeIfAbsent(geId, k -> new java.util.ArrayDeque<>());
			if (!q.isEmpty())
			{
				return;   // live prints beat backfill
			}
			// seeds run newest-first; addFirst restores ascending order
			for (final GeItemInfoPainter.Print p : seeds)
			{
				q.addFirst(p);
			}
		}
	}

	/**
	 * Own-trade matching as an assignment, not a lookup: each of your fills
	 * claims AT MOST ONE print (its best by time, side plausibility as the
	 * tiebreak), so four identical-price prints with one fill behind them tag
	 * once, not four times. The claim window adapts to the tape's cadence: a
	 * busy tape makes same-price coincidence cheap, so the window tightens to
	 * roughly three median print gaps; a quiet big-ticket keeps the full six
	 * minutes. Fill sources (live events, open lots, flip legs) are deduped
	 * first so one trade recorded twice cannot claim two prints.
	 */
	private void restampPrints(int geId, java.util.ArrayDeque<GeItemInfoPainter.Print> q)
	{
		for (final GeItemInfoPainter.Print p : q)
		{
			p.yours = false;
			p.yoursBuy = false;
		}
		// Own fills for this item: {unit, tsSec, buy, resting}.
		final List<long[]> fills = new ArrayList<>();
		final FlipLogEngine engine = flipLog;
		if (engine != null)
		{
			for (final long[] ev : engine.recentFillEvents())
			{
				if (ev[0] == geId && ev[1] > 0)
				{
					fills.add(new long[]{ev[1], ev[2] / 1000L, ev[3], ev.length > 4 ? ev[4] : -1});
				}
			}
		}
		for (final FlipLogEngine.Lot l : openLots)
		{
			if (l.itemId == geId && l.qty > 0)
			{
				addFillDeduped(fills, l.cost / l.qty, l.openedAt / 1000L, 1);
			}
		}
		for (final FlipLogEngine.Flip fl : recentFlips)
		{
			if (fl.itemId != geId || fl.qty <= 0)
			{
				continue;
			}
			addFillDeduped(fills, fl.buyGross / fl.qty, fl.openedAt / 1000L, 1);
			addFillDeduped(fills, fl.sellGross / fl.qty, fl.closedAt / 1000L, 0);
		}
		if (fills.isEmpty())
		{
			return;
		}

		// Claim window from the tape's own cadence.
		final GeItemInfoPainter.Print[] prints = q.toArray(new GeItemInfoPainter.Print[0]);
		long window = 360;
		if (prints.length >= 6)
		{
			final long[] gaps = new long[prints.length - 1];
			for (int i = 1; i < prints.length; i++)
			{
				gaps[i - 1] = Math.max(0, prints[i].ts - prints[i - 1].ts);
			}
			java.util.Arrays.sort(gaps);
			window = Math.max(60, Math.min(360, 3 * gaps[gaps.length / 2]));
		}

		// All plausible (fill, print) pairs, best first. Side plausibility:
		// a resting buy is consumed by an insta-sell (low print), a resting
		// sell by an insta-buy (high print); a crossed offer prints on the
		// side it crossed to. A matching expectation shaves the effective
		// distance, an unknown changes nothing.
		final List<long[]> cand = new ArrayList<>();
		for (int fi = 0; fi < fills.size(); fi++)
		{
			final long[] f = fills.get(fi);
			for (int pi = 0; pi < prints.length; pi++)
			{
				final GeItemInfoPainter.Print p = prints[pi];
				if (p.price <= 0 || !sameFillPrice(f[0], p.price))
				{
					continue;
				}
				final long dt = Math.abs(p.ts - f[1]);
				if (dt > window)
				{
					continue;
				}
				long adj = dt;
				if (f[3] >= 0)
				{
					final boolean expectHigh = f[3] == 1 ? f[2] == 0 : f[2] == 1;
					adj = p.buySide == expectHigh ? Math.max(0, dt - 45) : dt + 45;
				}
				cand.add(new long[]{adj, fi, pi});
			}
		}
		cand.sort((a, b) -> Long.compare(a[0], b[0]));
		final boolean[] fillUsed = new boolean[fills.size()];
		final boolean[] printUsed = new boolean[prints.length];
		for (final long[] c : cand)
		{
			final int fi = (int) c[1];
			final int pi = (int) c[2];
			if (fillUsed[fi] || printUsed[pi])
			{
				continue;
			}
			fillUsed[fi] = true;
			printUsed[pi] = true;
			prints[pi].yours = true;
			prints[pi].yoursBuy = fills.get(fi)[2] == 1;
		}
	}

	/**
	 * Your trade history for one item, newest first, straight from the flip
	 * log: {tsMs, unit price, qty, buy 1/0, stillOpen 1/0, flip profit}.
	 * Open lots are your live buys; closed flips contribute both legs, with
	 * the flip's profit riding the sell leg.
	 */
	long[][] ownTradesFor(int geId, int max)
	{
		final List<long[]> out = new ArrayList<>();
		for (final FlipLogEngine.Lot l : openLots)
		{
			if (l.itemId == geId && l.qty > 0)
			{
				out.add(new long[]{l.openedAt, l.cost / l.qty, l.qty, 1, 1, 0});
			}
		}
		for (final FlipLogEngine.Flip fl : recentFlips)
		{
			if (fl.itemId != geId || fl.qty <= 0)
			{
				continue;
			}
			out.add(new long[]{fl.openedAt, fl.buyGross / fl.qty, fl.qty, 1, 0, 0});
			out.add(new long[]{fl.closedAt, fl.sellGross / fl.qty, fl.qty, 0, 0, fl.profit});
		}
		out.sort((a, b) -> Long.compare(b[0], a[0]));
		return out.subList(0, Math.min(max, out.size())).toArray(new long[0][]);
	}

	/** Adds a lot- or flip-derived fill unless a live event already covers the
	 *  same trade (same side, same price to the whisker, within seconds). */
	private static void addFillDeduped(List<long[]> fills, long unit, long tsSec, long buy)
	{
		if (unit <= 0)
		{
			return;
		}
		for (final long[] f : fills)
		{
			if (f[2] == buy && Math.abs(f[1] - tsSec) <= 5 && sameFillPrice(f[0], unit))
			{
				return;
			}
		}
		fills.add(new long[]{unit, tsSec, buy, -1});
	}

	/** The wiki can report a price-improved fill a few coins off the exact
	 *  amount the log recorded (a 97m sell printed 1 gp under its true unit),
	 *  so matching allows a whisker that scales with price: 1 gp under 10m,
	 *  ~10 gp per 100m. Still coin-exact for all practical purposes. */
	private static boolean sameFillPrice(long unit, long price)
	{
		return Math.abs(unit - price) <= Math.max(1, unit / 10_000_000L);
	}

	/** One advisor poll's worth of print detection across the fetched items. */
	private void samplePrints(Map<Integer, FlipData> live)
	{
		final long now = System.currentTimeMillis() / 1000L;
		synchronized (cardPrints)
		{
			// Tapes survive an item leaving the live set (offer collected, board
			// rotation) so reopening its page keeps the history; only bound the
			// map when it grows past a sane ceiling.
			if (cardPrints.size() > 64)
			{
				cardPrints.keySet().removeIf(k -> !live.containsKey(k));
				printsLast.keySet().removeIf(k -> !live.containsKey(k));
				printsSeeded.removeIf(k -> !live.containsKey(k));
			}
			for (final Map.Entry<Integer, FlipData> e : live.entrySet())
			{
				final FlipData f = e.getValue();
				if (f == null)
				{
					continue;
				}
				final long[] last = printsLast.get(e.getKey());
				if (last == null)
				{
					printsLast.put(e.getKey(), new long[]{f.getSell(), f.getBuy()});
					continue;
				}
				final java.util.ArrayDeque<GeItemInfoPainter.Print> q =
					cardPrints.computeIfAbsent(e.getKey(), k -> new java.util.ArrayDeque<>());
				// The high moving = someone insta-bought at the new high; the
				// low moving = someone insta-sold into the new low.
				if (f.getSell() > 0 && f.getSell() != last[0])
				{
					q.addLast(new GeItemInfoPainter.Print(now, f.getSell(), true));
					last[0] = f.getSell();
				}
				if (f.getBuy() > 0 && f.getBuy() != last[1])
				{
					q.addLast(new GeItemInfoPainter.Print(now, f.getBuy(), false));
					last[1] = f.getBuy();
				}
				while (q.size() > 24)
				{
					q.removeFirst();
				}
			}
		}
	}

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

	int setupItem()
	{
		return setupItemId;
	}

	/** The tracked position for an item, or null. Server-derived: when held,
	 *  its entryBuy/floor are the trader's real average cost from the lots. */
	TrackedItem trackedFor(int geId)
	{
		return trackedById.get(geId);
	}

	TrackedOffer trackedAt(int slot)
	{
		return slot >= 0 && slot < SLOTS ? tracked.get(slot) : null;
	}

	// The last flips poll's verdict on market-data access. Premium surfaces
	// (the GE cards) stay dark for free keys instead of rendering shells.
	private volatile boolean marketDataOk;
	// Trial state from the last /me, and the bind bookkeeping. The bind only
	// ever fires while a trial is active.
	private volatile boolean trialActive;
	private volatile long lastLoginHash;
	private volatile long trialBoundHash;

	private void maybeBindTrial()
	{
		final long acctHash = lastLoginHash;
		if (!trialActive || acctHash == 0 || acctHash == -1 || acctHash == trialBoundHash)
		{
			return;
		}
		trialBoundHash = acctHash;
		api.bindTrialAccount(config.apiKey(), acctHash);
	}

	boolean marketDataOk()
	{
		return marketDataOk;
	}

	// Open lots from the flip log, refreshed with the panel cycle: the GE
	// cards draw your cost basis from them.
	private volatile List<FlipLogEngine.Lot> openLots = Collections.emptyList();
	private volatile List<FlipLogEngine.Flip> recentFlips = Collections.emptyList();

	/** Aggregated holding for one item: {qty, totalCost, earliestOpenedAtMs},
	 * or null when nothing is held. */
	long[] holdingFor(int geId)
	{
		long qty = 0;
		long cost = 0;
		long opened = Long.MAX_VALUE;
		for (final FlipLogEngine.Lot l : openLots)
		{
			if (l.itemId == geId && l.qty > 0)
			{
				qty += l.qty;
				cost += l.cost;
				opened = Math.min(opened, l.openedAt);
			}
		}
		return qty > 0 ? new long[]{qty, cost, opened} : null;
	}

	/** Exact FIFO cost of {@code qty} units of an item, taken from the raw
	 * lots so nothing is lost to per-unit rounding; a partial lot contributes
	 * its proportional share. The first {@code skip} units are passed over:
	 * that is how units already committed to another open sell offer are kept
	 * from being costed twice. -1 when the lots cannot cover skip + qty. */
	long fifoCostFor(int geId, long skip, long qty)
	{
		if (qty <= 0)
		{
			return -1;
		}
		final List<FlipLogEngine.Lot> lots = new ArrayList<>();
		for (final FlipLogEngine.Lot l : openLots)
		{
			if (l.itemId == geId && l.qty > 0)
			{
				lots.add(l);
			}
		}
		lots.sort((a, b) -> Long.compare(a.openedAt, b.openedAt));
		long toSkip = Math.max(0, skip);
		long need = qty;
		long cost = 0;
		for (final FlipLogEngine.Lot l : lots)
		{
			final long sk = Math.min(toSkip, l.qty);
			toSkip -= sk;
			final long avail = l.qty - sk;
			if (avail <= 0)
			{
				continue;
			}
			final long take = Math.min(need, avail);
			cost += take == l.qty ? l.cost : Math.round((double) l.cost * take / l.qty);
			need -= take;
			if (need == 0)
			{
				return cost;
			}
		}
		return -1;
	}

	/** The individual open lots for an item, oldest first: {qty, unit cost,
	 *  openedAt ms} per lot, so surfaces can show what was actually paid
	 *  instead of a blended average. */
	List<long[]> lotsFor(int geId)
	{
		final List<long[]> out = new ArrayList<>();
		for (final FlipLogEngine.Lot l : openLots)
		{
			if (l.itemId == geId && l.qty > 0)
			{
				out.add(new long[]{l.qty, l.cost / l.qty, l.openedAt});
			}
		}
		out.sort((a, b) -> Long.compare(a[2], b[2]));
		return out;
	}

	// ── per-slot waiting clock ──
	// How long an offer has sat without progress: reset when the offer
	// changes identity or its filled quantity moves. Drives the slot bar's
	// "sitting 12m" nudge.
	private final long[] slotQuietSince = new long[SLOTS];
	private final long[] slotIdentity = new long[SLOTS];

	private void noteSlotActivity(int slot, net.runelite.api.GrandExchangeOffer o)
	{
		if (slot < 0 || slot >= SLOTS)
		{
			return;
		}
		final long ident = o == null || o.getItemId() <= 0 ? 0
			: (o.getItemId() * 1_000_003L) ^ (o.getPrice() * 31L) ^ (o.getQuantitySold() * 7L)
				^ (o.getState() != null ? o.getState().ordinal() : 0);
		if (ident != slotIdentity[slot])
		{
			slotIdentity[slot] = ident;
			slotQuietSince[slot] = System.currentTimeMillis();
		}
	}

	/** Millis this slot's offer has sat with no state or fill movement. */
	long slotQuietMs(int slot)
	{
		if (slot < 0 || slot >= SLOTS || slotQuietSince[slot] == 0)
		{
			return 0;
		}
		return System.currentTimeMillis() - slotQuietSince[slot];
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
		final int viewed = viewedItemId; // the item the GE card is showing
		if (viewed > 0)
		{
			ids.add(viewed);
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
				samplePrints(fresh);
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
				// Key changes go through here too; the trial gate must track
				// the new key's entitlement, not the old one's.
				final AccountInfo acct = api.getAccount(config.apiKey());
				trialActive = acct != null && acct.isTrial();
				if (trialActive)
				{
					maybeBindTrial();
				}
				if (p != null) { p.setAccount(acct); }
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
		else if ("showCatches".equals(key))
		{
			// Toggle the Catch tab's content immediately; no network round-trip.
			if (p != null) { p.syncCatches(); }
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
		else if ("discordOfferAlerts".equals(key) || "apiKey".equals(key))
		{
			pushAlertMode();
		}
	}

	// Report the chosen Discord offer-alert cadence to the server. Fire on
	// startup and whenever the dropdown or key changes; the server gates it to
	// Trader Pro and ignores it otherwise.
	private void pushAlertMode()
	{
		final String key = config.apiKey();
		if (key == null || key.trim().isEmpty() || poller == null)
		{
			return;
		}
		final String mode = config.discordOfferAlerts().wire();
		poller.execute(() -> api.postAlertMode(key, mode));
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
			// icon.png not bundled yet: fall through to a blank placeholder.
		}
		return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
	}
}

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
	description = "Live OSRS flip margins from PriceCheck Premium, ranked by EV/hr, with a real-time offer advisor. Requires a plugin key.",
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
	private OfferAdvisorSlotOverlay slotOverlay;
	private OfferSetupOverlay setupOverlay;
	private ScheduledFuture<?> panelTask;
	private ScheduledFuture<?> advisorTask;
	private ScheduledFuture<?> telemetryTask;

	// The player's own offer fills, batched for the measured fill model
	// (opt-out via "Contribute market data").
	private final TelemetryCollector telemetry = new TelemetryCollector();

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

		advisorOverlay = new OfferAdvisorOverlay(this, config);
		overlayManager.add(advisorOverlay);
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
		clientThread.invokeLater(this::seedOffers);
		panelTask = poller.scheduleWithFixedDelay(this::refreshPanel, 0, PANEL_REFRESH_SECONDS, TimeUnit.SECONDS);
		advisorTask = poller.scheduleWithFixedDelay(this::refreshAdvisor, 1, ADVISOR_REFRESH_SECONDS, TimeUnit.SECONDS);
		telemetryTask = poller.scheduleWithFixedDelay(this::flushTelemetry, 30, 30, TimeUnit.SECONDS);
	}

	private void flushTelemetry()
	{
		if (!config.contributeData())
		{
			return;
		}
		final java.util.List<java.util.Map<String, Object>> batch = telemetry.drain();
		if (!batch.isEmpty())
		{
			api.postTelemetry(config.apiKey(), batch);
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
	private void refreshPanel()
	{
		final PriceCheckPanel p = panel;
		if (p == null)
		{
			return;
		}
		final String key = config.apiKey();
		final PriceCheckApiClient.FlipsResult flips = api.getFlips(key, FLIP_LIMIT);
		final PriceCheckApiClient.TrackedResult tracked = api.getTracked(key);
		p.update(flips, tracked, config.minEvPerHrK());
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
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		// Fires on the client thread; snapshot immediately, then recompute off-thread.
		tracked.set(event.getSlot(), TrackedOffer.of(event.getSlot(), event.getOffer()));
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

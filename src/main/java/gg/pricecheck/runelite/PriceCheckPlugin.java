package gg.pricecheck.runelite;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private PriceCheckConfig config;

	@Inject
	private PriceCheckApiClient api;

	@Inject
	private ScheduledExecutorService executor;

	private PriceCheckPanel panel;
	private NavigationButton navButton;
	private OfferAdvisorOverlay advisorOverlay;
	private ScheduledFuture<?> panelTask;
	private ScheduledFuture<?> advisorTask;

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
		panel = new PriceCheckPanel();
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

		clientThread.invokeLater(this::seedOffers);
		panelTask = executor.scheduleWithFixedDelay(this::refreshPanel, 0, PANEL_REFRESH_SECONDS, TimeUnit.SECONDS);
		advisorTask = executor.scheduleWithFixedDelay(this::refreshAdvisor, 1, ADVISOR_REFRESH_SECONDS, TimeUnit.SECONDS);
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
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		if (advisorOverlay != null)
		{
			overlayManager.remove(advisorOverlay);
			advisorOverlay = null;
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
		p.update(api.getFlips(config.apiKey(), FLIP_LIMIT), config.minEvPerHrK());
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
		executor.execute(this::refreshAdvisor);
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		// Fires on the client thread; snapshot immediately, then recompute off-thread.
		tracked.set(event.getSlot(), TrackedOffer.of(event.getSlot(), event.getOffer()));
		executor.execute(this::recomputeAdvice);
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
		if ("apiKey".equals(key))
		{
			executor.execute(() ->
			{
				refreshPanel();
				refreshAdvisor();
			});
		}
		else if ("minEvPerHrK".equals(key))
		{
			executor.execute(this::refreshPanel);
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

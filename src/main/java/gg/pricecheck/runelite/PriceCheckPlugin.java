package gg.pricecheck.runelite;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "PriceCheck",
	description = "Live OSRS flip margins from PriceCheck Premium, ranked by EV/hr. Requires a plugin key.",
	tags = {"flip", "margin", "grand exchange", "ge", "money", "pricecheck"}
)
public class PriceCheckPlugin extends Plugin
{
	private static final int REFRESH_SECONDS = 6;
	private static final int FLIP_LIMIT = 40;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PriceCheckConfig config;

	@Inject
	private PriceCheckApiClient api;

	@Inject
	private ScheduledExecutorService executor;

	private PriceCheckPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refreshTask;

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
		// Poll on a background thread; the panel marshals its own UI updates to the EDT.
		refreshTask = executor.scheduleWithFixedDelay(this::refresh, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(true);
			refreshTask = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		panel = null;
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

	private void refresh()
	{
		final PriceCheckPanel p = panel;
		if (p == null)
		{
			return;
		}
		final PriceCheckApiClient.FlipsResult result = api.getFlips(config.apiKey(), FLIP_LIMIT);
		p.update(result, config.minEvPerHrK());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!PriceCheckConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if ("apiKey".equals(e.getKey()) || "minEvPerHrK".equals(e.getKey()))
		{
			executor.execute(this::refresh);
		}
		else if ("showPanel".equals(e.getKey()) && navButton != null)
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
}

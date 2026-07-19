package gg.pricecheck.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PriceCheckConfig.GROUP)
public interface PriceCheckConfig extends Config
{
	String GROUP = "pricecheck";

	/** How often the PriceCheck Discord bot may DM you about your open offers. */
	enum AlertCadence
	{
		OFF("off"),
		INSTANT("instant"),
		EVERY_5_MIN("m5"),
		EVERY_15_MIN("m15"),
		HOURLY("hourly");

		private final String wire;
		AlertCadence(String wire) { this.wire = wire; }
		String wire() { return wire; }

		@Override
		public String toString()
		{
			switch (this)
			{
				case INSTANT: return "Instant";
				case EVERY_5_MIN: return "Every 5 min";
				case EVERY_15_MIN: return "Every 15 min";
				case HOURLY: return "Hourly digest";
				default: return "Off";
			}
		}
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "Plugin key",
		description = "Your PriceCheck plugin key (starts with pck_). Generate it free at pricecheck.gg (Discord login). "
			+ "Requests made with a key send your IP address to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. "
			+ "Your RSN and game credentials are never sent.",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "syncFlipLog",
		name = "Sync flip log (backup + web portfolio)",
		description = "Back up your flip log to your PriceCheck account and show it at flipping.pricecheck.gg/portfolio. "
			+ "Also keeps the log consistent when you flip on more than one computer.",
		warning = "Enabling this submits your Grand Exchange trades (item, price, quantity, tax, profit, timestamps), open positions, "
			+ "offer-slot snapshots, an anonymous per-account identifier (never your RSN), and your IP address to PriceCheck's servers, "
			+ "which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 2
	)
	default boolean syncFlipLog()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPanel",
		name = "Show side panel",
		description = "Show the PriceCheck flip panel in the RuneLite sidebar.",
		position = 3
	)
	default boolean showPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minEvPerHrK",
		name = "Min EV/hr (k)",
		description = "Hide flips whose expected value per hour is below this many thousand gp.",
		position = 4
	)
	default int minEvPerHrK()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showAdvisor",
		name = "Offer advisor overlay",
		description = "Show a live overlay that watches your active GE offers and tells you exactly when and how much to reprice, or when a margin has died.",
		position = 5
	)
	default boolean showAdvisor()
	{
		return true;
	}

	@ConfigItem(
		keyName = "contributeData",
		name = "Contribute market data",
		description = "Report your own GE offer fills to PriceCheck toward a measured fill-time model. Only offer details (item, price, quantity, fill progress) are sent, never your RSN or anything about your account.",
		warning = "Enabling this submits your Grand Exchange offer details (item, price, quantity, fill progress) and your IP address "
			+ "to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 6
	)
	default boolean contributeData()
	{
		return false;
	}

	@ConfigItem(
		keyName = "autoCapital",
		name = "Planner: auto-detect capital",
		description = "Send your liquid gp total (coins + platinum tokens across bank and inventory) to PriceCheck so the web slot planner fills in your capital automatically. Open your bank once after logging in to refresh it.",
		warning = "Enabling this submits your total liquid wealth (coins + platinum tokens across bank and inventory) and your IP address "
			+ "to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 7
	)
	default boolean autoCapital()
	{
		return false;
	}

	@ConfigItem(
		keyName = "gePriceButtons",
		name = "GE: click-to-fill prices",
		description = "When setting an offer price, show clickable PriceCheck lines (our live buy/sell for that item, plus your break-even floor when selling a tracked position). One click fills the price; you press Enter.",
		position = 8
	)
	default boolean gePriceButtons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geSearchSuggestions",
		name = "GE: suggest flips in search",
		description = "While the GE item search is empty, show your tracked positions and the current best flips as clickable results. Start typing and normal search takes over.",
		position = 9
	)
	default boolean geSearchSuggestions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "discordOfferAlerts",
		name = "Discord offer alerts",
		description = "Get a PriceCheck Discord DM when one of your open GE offers is undercut, outbid, or probably filled while you were offline. Trader Pro only. Pick how often you want to hear from the bot.",
		position = 11
	)
	default AlertCadence discordOfferAlerts()
	{
		return AlertCadence.OFF;
	}

	@ConfigItem(
		keyName = "geItemCard",
		name = "GE: item evidence card",
		description = "Beside the open offer screen: the day's traded corridor with your offer drawn on it, the trades arriving live, measured fill odds, and the after-tax outcome. Hold Shift to peek past the single-item card; on the offers grid, Shift shows each card's expand and collapse buttons.",
		position = 10
	)
	default boolean geItemCard()
	{
		return true;
	}
}

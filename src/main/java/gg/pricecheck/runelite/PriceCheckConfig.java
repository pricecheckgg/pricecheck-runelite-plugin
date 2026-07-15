package gg.pricecheck.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PriceCheckConfig.GROUP)
public interface PriceCheckConfig extends Config
{
	String GROUP = "pricecheck";

	@ConfigItem(
		keyName = "apiKey",
		name = "Plugin key",
		description = "Your PriceCheck plugin key (starts with pck_). Generate it at premium.pricecheck.gg -> Account -> RuneLite plugin. It only works while your subscription is active.",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showPanel",
		name = "Show side panel",
		description = "Show the PriceCheck flip panel in the RuneLite sidebar.",
		position = 2
	)
	default boolean showPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minEvPerHrK",
		name = "Min EV/hr (k)",
		description = "Hide flips whose expected value per hour is below this many thousand gp.",
		position = 3
	)
	default int minEvPerHrK()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showAdvisor",
		name = "Offer advisor overlay",
		description = "Show a live overlay that watches your active GE offers and tells you exactly when and how much to reprice, or when a margin has died.",
		position = 4
	)
	default boolean showAdvisor()
	{
		return true;
	}

	@ConfigItem(
		keyName = "contributeData",
		name = "Contribute market data",
		description = "Report your own GE offer fills to PriceCheck to sharpen fill-time and margin accuracy for everyone. Only offer details (item, price, quantity) are sent — never your RSN or anything about your account.",
		position = 5
	)
	default boolean contributeData()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoCapital",
		name = "Planner: auto-detect capital",
		description = "Send your liquid gp total (coins + platinum tokens across bank and inventory) to PriceCheck so the web slot planner fills in your capital automatically. Open your bank once after logging in to refresh it.",
		position = 6
	)
	default boolean autoCapital()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gePriceButtons",
		name = "GE: click-to-fill prices",
		description = "When setting an offer price, show clickable PriceCheck lines (our live buy/sell for that item, plus your break-even floor when selling a tracked position). One click fills the price; you press Enter.",
		position = 7
	)
	default boolean gePriceButtons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geSearchSuggestions",
		name = "GE: suggest flips in search",
		description = "While the GE item search is empty, show your tracked positions and the current best flips as clickable results. Start typing and normal search takes over.",
		position = 8
	)
	default boolean geSearchSuggestions()
	{
		return true;
	}
}

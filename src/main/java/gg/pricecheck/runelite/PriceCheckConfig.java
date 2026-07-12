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
}

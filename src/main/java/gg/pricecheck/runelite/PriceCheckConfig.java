package gg.pricecheck.runelite;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

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

	/** How much the GE overlays show and how large they draw. Overnight is for
	 *  AFK watching: a deeper trade tape and larger panels you can read from
	 *  across the room. */
	enum OverlayMode
	{
		ACTIVE(10, false),
		ADVANCED(20, false),
		OVERNIGHT(30, true);

		private final int depth;
		private final boolean big;

		OverlayMode(int depth, boolean big)
		{
			this.depth = depth;
			this.big = big;
		}

		/** How many recent trades the tape and on-chart prints show. */
		int tradeDepth()
		{
			return depth;
		}

		/** Overnight draws the hand-painted panels larger for at-a-glance reading. */
		boolean big()
		{
			return big;
		}

		@Override
		public String toString()
		{
			switch (this)
			{
				case ADVANCED: return "Standard (20)";
				case OVERNIGHT: return "Large (30, big)";
				default: return "Compact (10)";
			}
		}
	}

	/** The whole GE desk in one control. AUTO measures the space around the
	 *  open Grand Exchange every frame and shows the biggest layout that fits,
	 *  so any window size gets a clean desk instead of overlapping panels. The
	 *  fixed tiers force a maximum; panels still never overlap when the window
	 *  is too small for the pick. */
	enum DeskMode
	{
		AUTO,
		FULL,
		COMPACT,
		MINIMAL,
		OFF;

		@Override
		public String toString()
		{
			switch (this)
			{
				case FULL: return "Full desk";
				case COMPACT: return "Compact (card + blotter)";
				case MINIMAL: return "Minimal (card only)";
				case OFF: return "Off";
				default: return "Auto (fit my window)";
			}
		}
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "Plugin key",
		description = "Your PriceCheck plugin key (starts with pck_). Generate it free at pricecheck.gg (Discord login). "
			+ "Requests made with a key send your IP address to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. "
			+ "While a PriceCheck trial is active on your key, an anonymous per-account identifier (never your RSN) is sent once per game account to bind the trial to it. "
			+ "Your RSN and game credentials are never sent.",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "deskMode",
		name = "Terminal desk",
		description = "The Bloomberg-style desk around the open Grand Exchange: status bar, item card with the live chart and tape, "
			+ "offers blotter, opportunity radar, held positions, session flow, fills and ticker. "
			+ "Auto fits the layout to your window size; the fixed tiers cap how much draws; Off hides the desk entirely. "
			+ "Market data needs Trader ($1/mo) or a trial; the flip log works without.",
		position = 2
	)
	default DeskMode deskMode()
	{
		return DeskMode.AUTO;
	}

	@ConfigItem(
		keyName = "overlayMode",
		name = "Detail level",
		description = "How deep the trade tape runs and how large the desk draws (size and detail, not a time window: "
			+ "the chart's own 1h/24h/7d views are picked on the card). Compact keeps the last 10 trades, "
			+ "Standard 20, Large 30 with bigger panels for at-a-glance watching.",
		position = 3
	)
	default OverlayMode overlayMode()
	{
		return OverlayMode.ACTIVE;
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
		keyName = "geAssists",
		name = "GE fill assists",
		description = "One-click helpers inside the GE: clickable price lines when setting an offer (our live buy/sell, your break-even "
			+ "when selling a tracked position), your remaining 4h buy limit on the quantity box, and tracked positions plus the "
			+ "best flips as clickable results while the item search is empty. Pre-fill only; you always press Enter yourself.",
		position = 5
	)
	default boolean geAssists()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geAutofillHotkey",
		name = "GE autofill hotkey",
		description = "Press this while a GE buy or sell price box is open to fill PriceCheck's recommended price for that item; press it on the quantity box of a buy offer to fill your remaining 4h buy limit. You still press Enter to place the offer. Unbound by default.",
		position = 6
	)
	default Keybind geAutofillHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "discordOfferAlerts",
		name = "Discord offer alerts",
		description = "Get a PriceCheck Discord DM when one of your open GE offers is undercut, outbid, or probably filled while you were offline. Trader Pro only. Pick how often you want to hear from the bot.",
		position = 7
	)
	default AlertCadence discordOfferAlerts()
	{
		return AlertCadence.OFF;
	}

	@ConfigItem(
		keyName = "syncFlipLog",
		name = "Sync flip log (backup + web portfolio)",
		description = "Back up your flip log to your PriceCheck account and show it at flipping.pricecheck.gg/portfolio. "
			+ "Also keeps the log consistent when you flip on more than one computer.",
		warning = "Enabling this submits your Grand Exchange trades (item, price, quantity, tax, profit, timestamps), open positions, "
			+ "offer-slot snapshots, an anonymous per-account identifier (never your RSN), and your IP address to PriceCheck's servers, "
			+ "which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 8
	)
	default boolean syncFlipLog()
	{
		return false;
	}

	@ConfigItem(
		keyName = "contributeData",
		name = "Contribute market data",
		description = "Report your own GE offer fills to PriceCheck toward a measured fill-time model. Only offer details (the item, your price and quantity, how much has filled, which GE slot, and the time) are sent, never your RSN or anything about your account.",
		warning = "Enabling this submits your Grand Exchange offer details (the item, your price and quantity, how much has filled, which GE slot, and the time it happened) and your IP address "
			+ "to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 9
	)
	default boolean contributeData()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPanel",
		name = "Show side panel",
		description = "Show the PriceCheck flip panel in the RuneLite sidebar.",
		position = 10
	)
	default boolean showPanel()
	{
		return true;
	}

	// ── Retired toggles ───────────────────────────────────────────────
	// The desk grew out of a pile of per-overlay switches; deskMode replaced
	// all of them and geAssists replaced the two chatbox helpers. The methods
	// stay (plain defaults, no settings entry) so old callers compile and old
	// saved values are simply ignored; behavior routes through the
	// PriceCheckPlugin desk accessors now.

	default boolean autoCapital()
	{
		return false;
	}

	default boolean showAdvisor()
	{
		return true;
	}

	default boolean gePriceButtons()
	{
		return geAssists();
	}

	default boolean geSearchSuggestions()
	{
		return geAssists();
	}

	default boolean geItemCard()
	{
		return true;
	}

	default boolean showCatches()
	{
		return true;
	}

	default boolean geOffersPanel()
	{
		return false;
	}

	default boolean terminalStatusBar()
	{
		return false;
	}

	default boolean terminalCard()
	{
		return false;
	}

	default boolean terminalOffers()
	{
		return false;
	}

	default boolean terminalDesk()
	{
		return false;
	}
}

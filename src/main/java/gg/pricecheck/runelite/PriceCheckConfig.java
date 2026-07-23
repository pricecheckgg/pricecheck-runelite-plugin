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
		description = "Report your own GE offer fills to PriceCheck toward a measured fill-time model. Only offer details (the item, your price and quantity, how much has filled, which GE slot, and the time) are sent, never your RSN or anything about your account.",
		warning = "Enabling this submits your Grand Exchange offer details (the item, your price and quantity, how much has filled, which GE slot, and the time it happened) and your IP address "
			+ "to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 6
	)
	default boolean contributeData()
	{
		return false;
	}

	// Planner: auto-detect capital. The slot planner is disabled pending testing
	// (see PriceCheckPlugin.PLANNER_ENABLED), so this toggle is hidden from settings
	// - the method stays so all callers compile and nothing submits capital. Restore
	// the @ConfigItem below to re-surface it when the planner comes back.
	/*
	@ConfigItem(
		keyName = "autoCapital",
		name = "Planner: auto-detect capital",
		description = "Send your liquid gp total (coins + platinum tokens across bank and inventory) to PriceCheck so the web slot planner fills in your capital automatically. Open your bank once after logging in to refresh it.",
		warning = "Enabling this submits your total liquid wealth (coins + platinum tokens across bank and inventory) and your IP address "
			+ "to PriceCheck's servers, which are not controlled or verified by the RuneLite Developers. Continue?",
		position = 7
	)
	*/
	default boolean autoCapital()
	{
		return false;
	}

	@ConfigItem(
		keyName = "gePriceButtons",
		name = "GE: click-to-fill prices",
		description = "When setting an offer price, show clickable PriceCheck lines (our live buy/sell for that item, plus your break-even floor when selling a tracked position). On the quantity box of a buy offer, show your remaining 4h buy limit. One click fills the value; you press Enter.",
		position = 8
	)
	default boolean gePriceButtons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geAutofillHotkey",
		name = "GE: autofill hotkey",
		description = "Press this while a GE buy or sell price box is open to fill PriceCheck's recommended price for that item; press it on the quantity box of a buy offer to fill your remaining 4h buy limit. You still press Enter to place the offer. Unbound by default.",
		position = 9
	)
	default Keybind geAutofillHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "geSearchSuggestions",
		name = "GE: suggest flips in search",
		description = "While the GE item search is empty, show your tracked positions and the current best flips as clickable results. Start typing and normal search takes over.",
		position = 10
	)
	default boolean geSearchSuggestions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "discordOfferAlerts",
		name = "Discord offer alerts",
		description = "Get a PriceCheck Discord DM when one of your open GE offers is undercut, outbid, or probably filled while you were offline. Trader Pro only. Pick how often you want to hear from the bot.",
		position = 12
	)
	default AlertCadence discordOfferAlerts()
	{
		return AlertCadence.OFF;
	}

	@ConfigItem(
		keyName = "geItemCard",
		name = "GE: item evidence card",
		description = "Beside the open offer screen: the day's traded corridor with your offer drawn on it, the trades arriving live, measured fill odds, and the after-tax outcome. Hold Shift to peek past the single-item card; on the offers grid, Shift shows each card's expand and collapse buttons.",
		position = 11
	)
	default boolean geItemCard()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCatches",
		name = "Show dump catches",
		description = "Add a Catch tab that lists live dump-reversion plays, ranked. It surfaces the board's measured base rate and a conservative, taxed, contingent target, and hides any figure it cannot back with trials (a still-falling dump reads as a skip). Trader Pro.",
		position = 13
	)
	default boolean showCatches()
	{
		return false;
	}

	@ConfigItem(
		keyName = "geOffersPanel",
		name = "GE: active offers board",
		description = "Dock a compact board to the right of the open Grand Exchange grid listing every active offer with its live verdict, how close it sits to a real fill, the last trade on your side, and the coarse pressure lean. Overview only. Trader Pro.",
		position = 16
	)
	default boolean geOffersPanel()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayMode",
		name = "GE: card size",
		description = "How deep the card's trade tape runs and how big the overlays draw (this is size and detail, "
			+ "not a time window: the chart's own 1h/24h/7d and last-trades views are picked on the card). "
			+ "Compact keeps the last 10 trades at the normal size, Standard the last 20, and Large the last 30 "
			+ "while drawing the advisor box and active-offers board larger for at-a-glance watching.",
		position = 17
	)
	default OverlayMode overlayMode()
	{
		return OverlayMode.ACTIVE;
	}

	@ConfigItem(
		keyName = "terminalStatusBar",
		name = "GE: terminal status bar",
		description = "Dock a Bloomberg-style status strip to the top of the open Grand Exchange window: your cash, used offer slots, current world, and a live clock. First piece of the terminal desk. Uses only on-screen game state, nothing is sent.",
		position = 18
	)
	default boolean terminalStatusBar()
	{
		return false;
	}

	@ConfigItem(
		keyName = "terminalCard",
		name = "GE: terminal item card",
		description = "Draw the item evidence card in the Bloomberg-terminal style: an amber-on-black quote grid (bid/ask/spread/margin/ROI/tax/change/volume/range/order-flow), the corridor chart, a time-and-sales tape, and your position. Needs room to the side of the GE for the wider card; falls back to the classic card when there isn't. Part of the terminal desk.",
		position = 19
	)
	default boolean terminalCard()
	{
		return false;
	}

	@ConfigItem(
		keyName = "terminalOffers",
		name = "GE: terminal offers blotter",
		description = "Show your active offers as a Bloomberg-terminal blotter docked to the right of the Grand Exchange (amber-on-black, one row per offer with side, quantity, price, closeness, verdict and running P&L). Works on its own. Trader Pro. Part of the terminal desk.",
		position = 20
	)
	default boolean terminalOffers()
	{
		return false;
	}

	@ConfigItem(
		keyName = "terminalDesk",
		name = "GE: terminal desk (everything)",
		description = "One switch for the whole Bloomberg-style desk around the Grand Exchange: the status bar, terminal item card and offers blotter, plus an opportunity radar, fresh dips and top movers to the left, your held positions above, and session flow, recent fills and a price ticker around it. Uses the live board (Trader Pro) plus your own flip log. Turning this on lights up every terminal piece; the individual toggles above still work on their own if you want just one. Panels quietly hide when there isn't room.",
		position = 21
	)
	default boolean terminalDesk()
	{
		return false;
	}
}

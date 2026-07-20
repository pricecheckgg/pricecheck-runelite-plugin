package gg.pricecheck.runelite;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.events.GrandExchangeSearched;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;

/**
 * The two Grand Exchange chatbox integrations:
 *
 * 1) PRICE FILL: when the "Set a price for each item:" input opens, inject
 *    clickable lines with our engine's queue-jumping price for that side (and
 *    your break-even floor when selling a tracked position). A click pre-fills
 *    the input exactly as if typed: the user still presses Enter, so it stays
 *    one input action per click (the accepted pattern from approved Hub
 *    plugins: pre-fill via the mes-layer input varcstr, never submit).
 *
 * 2) SEARCH SUGGESTIONS: while the GE item search is EMPTY, feed our tracked
 *    items + top live flips into the client's native search-result buffer
 *    (GrandExchangeSearched consume + setGeSearchResultIds). The game renders
 *    them as ordinary clickable result rows and selecting one is 100% vanilla
 *    behavior. The moment the player types, we stand down and normal search
 *    takes over; clearing the input brings the suggestions back.
 *
 * Everything here runs on the client thread (the events arrive there; widget
 * injection is deferred one tick so the client finishes building the layer).
 */
class GeChatboxHelper
{
	private static final int VARC_INPUT_TYPE = 5;        // VarClientID.MESLAYERMODE
	private static final int VARC_INPUT_TEXT = 359;      // VarClientID.MESLAYERINPUT
	private static final int INPUT_TYPE_NUMERIC = 7;     // GE qty/price input
	private static final int INPUT_TYPE_GE_SEARCH = 14;  // GE item search
	private static final int SCRIPT_GE_SEARCHBOX_BUILT = 750;
	private static final int SETUP_SIDE_CHILD = 20;      // "Buy offer" / "Sell offer"
	private static final int MAX_SUGGESTIONS = 60;

	// The GE search proc only fires its searched event with a non-empty input,
	// so suggestions ride a sentinel search string (Quest Helper's technique).
	private static final String SENTINEL = "pc-flips";

	// Chatbox parchment is light: dark ink reads, gold does not (device-tested).
	private static final int INK = 0x800000;
	private static final int INK_HOVER = 0x11128e;

	private final Client client;
	private final ClientThread clientThread;
	private final PriceCheckConfig config;
	private final PriceCheckPlugin plugin;

	// Latest board + positions, pushed by the plugin's poller.
	private volatile List<FlipData> flips = Collections.emptyList();
	private volatile List<TrackedItem> tracked = Collections.emptyList();

	GeChatboxHelper(Client client, ClientThread clientThread, PriceCheckConfig config, PriceCheckPlugin plugin)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.plugin = plugin;
	}

	void update(List<FlipData> flips, List<TrackedItem> tracked)
	{
		if (flips != null)
		{
			this.flips = flips;
		}
		if (tracked != null)
		{
			this.tracked = tracked;
		}
	}

	// ── 1) price fill ──

	void onVarClientIntChanged(VarClientIntChanged e)
	{
		if (e.getIndex() != VARC_INPUT_TYPE)
		{
			return;
		}
		// Search closed (item picked, escape, interface change): restore what the
		// suggestions mode touched. MES_TEXT and MES_TEXT2 are static widgets,
		// so a hidden flag would otherwise leak into the next thing that uses
		// the chatbox.
		if (suggestActive && client.getVarcIntValue(VARC_INPUT_TYPE) != INPUT_TYPE_GE_SEARCH)
		{
			standDown(false);
		}
		if (!config.gePriceButtons())
		{
			return;
		}
		if (client.getVarcIntValue(VARC_INPUT_TYPE) != INPUT_TYPE_NUMERIC
			|| client.getWidget(InterfaceID.Chatbox.MES_TEXT) == null
			|| client.getWidget(InterfaceID.GeOffers.SETUP_DESC) == null)
		{
			return;
		}
		// Defer one tick so the client finishes building the input layer before
		// children are added to it.
		clientThread.invokeLater(this::injectPriceLines);
	}

	private void injectPriceLines()
	{
		final Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		final Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (parent == null || prompt == null || setup == null
			|| !"Set a price for each item:".equals(prompt.getText()))
		{
			return;
		}
		final Widget side = setup.getChild(SETUP_SIDE_CHILD);
		final boolean isBuy = side != null && "Buy offer".equals(side.getText());
		final boolean isSell = side != null && "Sell offer".equals(side.getText());
		if (!isBuy && !isSell)
		{
			return;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return;
		}
		final FlipData live = plugin.liveFor(itemId);
		final long price = live == null ? -1 : (isBuy ? live.getBuy() : live.getSell());
		if (price <= 0)
		{
			return;   // no live data yet: stay silent rather than suggest something wrong
		}
		// Big-lane items carry a measured resting quote (band levels when the
		// series backs them): offer the snipe with its odds first, keep the
		// instant line for context. Everything else keeps the proven line.
		// Top-left, dark ink: gold text washes out on the parchment and
		// right-alignment clipped at the screen edge on device.
		int y = 5;
		final Long laneP = live == null ? null : (isBuy ? live.getLaneBid() : live.getLaneAsk());
		if (laneP != null && laneP > 0 && laneP != price)
		{
			String tag = "";
			if (live.getLaneOdds() != null && live.getLaneOdds() > 0)
			{
				tag = " (" + Math.round(live.getLaneOdds() * 100) + "% fill"
					+ (live.getLaneHold() != null && live.getLaneHold() > 0 ? ", ~" + hold(live.getLaneHold()) : "") + ")";
			}
			addLine(parent, y, "set to PriceCheck snipe: " + Fmt.full(laneP) + " gp" + tag, laneP);
			y += 15;
			addLine(parent, y, "set to instant " + (isBuy ? "buy" : "sell") + ": " + Fmt.full(price) + " gp", price);
			y += 15;
		}
		else
		{
			addLine(parent, y, "set to PriceCheck " + (isBuy ? "buy" : "sell") + ": " + Fmt.full(price) + " gp", price);
			y += 15;
		}
		if (isSell)
		{
			// Only a HELD position has a real break-even to protect. A watch
			// row's floor is derived from a board quote, not your cost, so
			// offering it as "your break-even" would contradict the panel.
			final TrackedItem t = trackedFor(itemId);
			if (t != null && t.isHeld() && t.getFloor() > 0)
			{
				addLine(parent, y, "set to your break-even floor: " + Fmt.full(t.getFloor()) + " gp", t.getFloor());
			}
		}
	}

	private static String hold(double hrs)
	{
		return hrs >= 1 ? (Math.round(hrs * 10) / 10.0) + "h" : Math.round(hrs * 60) + "m";
	}

	private void addLine(Widget parent, int y, String label, long price)
	{
		final Widget w = parent.createChild(-1, WidgetType.TEXT);
		w.setText(label);
		w.setTextColor(INK);
		w.setFontId(FontID.VERDANA_11_BOLD);
		w.setOriginalX(10);
		w.setOriginalY(y);
		w.setOriginalHeight(16);
		w.setXTextAlignment(WidgetTextAlignment.LEFT);
		w.setWidthMode(WidgetSizeMode.MINUS);
		w.setHasListener(true);
		w.setAction(0, "Set price");
		w.setOnOpListener((JavaScriptCallback) ev -> fillInput(price));
		w.setOnMouseRepeatListener((JavaScriptCallback) ev -> w.setTextColor(INK_HOVER));
		w.setOnMouseLeaveListener((JavaScriptCallback) ev -> w.setTextColor(INK));
		w.revalidate();
	}

	// Pre-fill only: write the digits into the input buffer + its on-screen echo
	// (the '*' is the client's fake caret). The user presses Enter to submit.
	private void fillInput(long price)
	{
		final Widget echo = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (echo != null)
		{
			echo.setText(price + "*");
		}
		client.setVarcStrValue(VARC_INPUT_TEXT, String.valueOf(price));
	}

	/**
	 * Hotkey entry: if a GE buy/sell price box is open for an item we have a
	 * live price for, pre-fill our recommended price for that side - the exact
	 * same value and pre-fill-only behaviour as clicking the top price line, so
	 * the player still presses Enter to place the offer. Client thread only.
	 * Returns true when it filled something (the key did work), false otherwise.
	 */
	boolean fillRecommendedPrice()
	{
		if (client.getVarcIntValue(VARC_INPUT_TYPE) != INPUT_TYPE_NUMERIC)
		{
			return false;   // no numeric input open: not a price box
		}
		final Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (prompt == null || setup == null
			|| !"Set a price for each item:".equals(prompt.getText()))
		{
			return false;
		}
		final Widget side = setup.getChild(SETUP_SIDE_CHILD);
		final boolean isBuy = side != null && "Buy offer".equals(side.getText());
		final boolean isSell = side != null && "Sell offer".equals(side.getText());
		if (!isBuy && !isSell)
		{
			return false;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			return false;
		}
		final FlipData live = plugin.liveFor(itemId);
		final long price = live == null ? -1 : (isBuy ? live.getBuy() : live.getSell());
		if (price <= 0)
		{
			return false;   // no live data yet: fill nothing rather than a wrong price
		}
		fillInput(price);
		return true;
	}

	private TrackedItem trackedFor(int geId)
	{
		for (TrackedItem t : tracked)
		{
			if (t.getGeId() == geId)
			{
				return t;
			}
		}
		return null;
	}

	// ── 2) search suggestions ──
	//
	// The search proc only fires GrandExchangeSearched when the input is
	// non-empty (device-confirmed), so a fresh-open auto-populate needs a
	// sentinel search string: on open we put SENTINEL in the input buffer,
	// hide the input echo (the player shouldn't see "pc-flips*"), replay the
	// box's own key listener to run the search, and consume the event to feed
	// our ids into the client's native result buffer. A dark-ink line under
	// the title hands back to normal typing on click.

	private boolean suggestActive = false;
	private Widget banner;

	void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() != SCRIPT_GE_SEARCHBOX_BUILT || !config.geSearchSuggestions())
		{
			return;
		}
		// Defer one tick so the freshly built search layer is complete.
		clientThread.invokeLater(this::activateSuggestions);
	}

	private void activateSuggestions()
	{
		if (client.getVarcIntValue(VARC_INPUT_TYPE) != INPUT_TYPE_GE_SEARCH)
		{
			return;
		}
		if (nativePreviousSearchShown())
		{
			// The game's own "Previous search" panel (the Show-last-searched
			// setting) owns the empty-input layout; drawing over it garbles
			// both. Yield, and hand back everything we took: banner, title,
			// echo, and the sentinel if it is ours.
			standDown(SENTINEL.equals(client.getVarcStrValue(VARC_INPUT_TEXT)));
			return;
		}
		final String cur = client.getVarcStrValue(VARC_INPUT_TEXT);
		if (cur != null && !cur.isEmpty() && !cur.equals(SENTINEL))
		{
			// A real search is in the buffer: don't hijack it, and if we were
			// active a moment ago make sure nothing of ours lingers over it.
			if (suggestActive || banner != null)
			{
				standDown(false);
			}
			return;
		}
		if (suggestionIds().length == 0)
		{
			if (suggestActive || banner != null)
			{
				standDown(false);
			}
			return;   // nothing to show yet (no key / board still loading)
		}
		client.setVarcStrValue(VARC_INPUT_TEXT, SENTINEL);
		suggestActive = true;
		// The banner owns the title row while we are active: hide BOTH native
		// occupants (the "What would you like to buy?" title and the input
		// echo), or whichever one the game draws bleeds through our text.
		final Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (title != null)
		{
			title.setHidden(true);
		}
		final Widget echo = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (echo != null)
		{
			echo.setHidden(true);
		}
		injectSearchBanner();
		replaySearch();
	}

	/**
	 * Give the title row back to the game: banner gone, title and echo
	 * restored, and the sentinel cleared when asked. Safe to call in any
	 * state; every path that stops suggesting funnels through here so no
	 * combination of rebuilds can leave our banner over native text.
	 */
	private void standDown(boolean clearSentinel)
	{
		suggestActive = false;
		if (banner != null)
		{
			banner.setHidden(true);
			banner = null;
		}
		final Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (title != null)
		{
			title.setHidden(false);
		}
		final Widget echo = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (echo != null)
		{
			echo.setHidden(false);
		}
		if (clearSentinel)
		{
			final String cur = client.getVarcStrValue(VARC_INPUT_TEXT);
			if (cur != null && cur.startsWith(SENTINEL))
			{
				client.setVarcStrValue(VARC_INPUT_TEXT, cur.substring(SENTINEL.length()));
			}
		}
	}

	/** Plugin disable: put the chatbox back exactly as the client expects it.
	 *  Without this, a hidden input echo and the sentinel string survive into
	 *  vanilla GE search until the interface is rebuilt. */
	void shutdown()
	{
		clientThread.invoke(() ->
		{
			if (!suggestActive)
			{
				return;
			}
			standDown(true);
			replaySearch();
		});
	}

	// Back to vanilla: clear the sentinel, unhide the input, rerun the search
	// so the normal "start typing" panel returns.
	private void deactivateSuggestions()
	{
		standDown(true);
		client.setVarcStrValue(VARC_INPUT_TEXT, "");
		final Widget echo = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (echo != null)
		{
			echo.setText("*");
		}
		replaySearch();
	}

	/** True when the chatbox is showing the game's previous-search panel. */
	private boolean nativePreviousSearchShown()
	{
		final Widget layer = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (layer == null)
		{
			return false;
		}
		final Widget[][] sets = { layer.getStaticChildren(), layer.getDynamicChildren(), layer.getNestedChildren() };
		for (final Widget[] set : sets)
		{
			if (set == null)
			{
				continue;
			}
			for (final Widget w : set)
			{
				if (w == null || w.isHidden())
				{
					continue;
				}
				final String t = w.getText();
				if (t != null && (t.contains("Previous search") || t.contains("Show last searched")))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void injectSearchBanner()
	{
		final Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (parent == null)
		{
			return;
		}
		// One banner at a time: a rebuild can re-activate before the old child
		// is torn down, and a second copy doubles the text.
		if (banner != null)
		{
			banner.setHidden(true);
		}
		final Widget w = parent.createChild(-1, WidgetType.TEXT);
		banner = w;
		w.setText("Your tracked items + best flips, ranked. Click here to type a search instead.");
		w.setTextColor(INK);
		w.setFontId(FontID.VERDANA_11_BOLD);
		w.setOriginalX(10);
		w.setOriginalY(4);
		w.setOriginalHeight(15);
		w.setXTextAlignment(WidgetTextAlignment.LEFT);
		w.setWidthMode(WidgetSizeMode.MINUS);
		w.setHasListener(true);
		w.setAction(0, "Search normally");
		w.setOnOpListener((JavaScriptCallback) ev -> deactivateSuggestions());
		w.setOnMouseRepeatListener((JavaScriptCallback) ev -> w.setTextColor(INK_HOVER));
		w.setOnMouseLeaveListener((JavaScriptCallback) ev -> w.setTextColor(INK));
		w.revalidate();
	}

	private void replaySearch()
	{
		if (client.getVarcIntValue(VARC_INPUT_TYPE) != INPUT_TYPE_GE_SEARCH)
		{
			return;
		}
		final Widget box = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if (box == null)
		{
			return;
		}
		final Object[] onKey = box.getOnKeyListener();
		if (onKey == null)
		{
			return;
		}
		client.runScript(onKey);
	}

	void onGrandExchangeSearched(GrandExchangeSearched e)
	{
		if (!config.geSearchSuggestions() || e.isConsumed())
		{
			return;   // another plugin (bank tags etc.) got there first
		}
		final String input = client.getVarcStrValue(VARC_INPUT_TEXT);
		if (SENTINEL.equals(input))
		{
			final short[] ids = suggestionIds();
			if (ids.length == 0)
			{
				return;
			}
			e.consume();
			client.setGeSearchResultIndex(0);
			client.setGeSearchResultCount(ids.length);
			client.setGeSearchResultIds(ids);
			return;
		}
		// If keystrokes reach the hidden input while suggestions are up, the
		// buffer becomes "pc-flipsX". Strip the sentinel, restore the echo, and
		// rerun so the player lands in a normal search for exactly what they
		// typed (one repaint behind, corrected on the replay).
		if (suggestActive && input != null && input.startsWith(SENTINEL))
		{
			final String typed = input.substring(SENTINEL.length());
			suggestActive = false;
			client.setVarcStrValue(VARC_INPUT_TEXT, typed);
			final Widget echo = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
			if (echo != null)
			{
				echo.setText(typed + "*");
				echo.setHidden(false);
			}
			clientThread.invokeLater(this::replaySearch);
		}
	}

	// Tracked positions first (you manage those), then the board by EV. The
	// result buffer takes shorts: ids above that range can't be suggested.
	private short[] suggestionIds()
	{
		final Set<Integer> out = new LinkedHashSet<>();
		for (TrackedItem t : tracked)
		{
			if (t.getGeId() > 0 && t.getGeId() <= Short.MAX_VALUE)
			{
				out.add(t.getGeId());
			}
		}
		for (FlipData f : flips)
		{
			if (out.size() >= MAX_SUGGESTIONS)
			{
				break;
			}
			if (f.getGeId() > 0 && f.getGeId() <= Short.MAX_VALUE)
			{
				out.add(f.getGeId());
			}
		}
		final short[] arr = new short[out.size()];
		int i = 0;
		for (int id : out)
		{
			arr[i++] = (short) id;
		}
		return arr;
	}
}

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
 * 1) PRICE FILL — when the "Set a price for each item:" input opens, inject
 *    clickable lines with our engine's queue-jumping price for that side (and
 *    your break-even floor when selling a tracked position). A click pre-fills
 *    the input exactly as if typed — the user still presses Enter, so it stays
 *    one input action per click (the accepted pattern from approved Hub
 *    plugins: pre-fill via the mes-layer input varcstr, never submit).
 *
 * 2) SEARCH SUGGESTIONS — while the GE item search is EMPTY, feed our tracked
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

	private static final int GOLD = 0xe6c667;
	private static final int WHITE = 0xffffff;

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
		if (!config.gePriceButtons() || e.getIndex() != VARC_INPUT_TYPE)
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
			return;   // no live data yet — stay silent rather than suggest something wrong
		}
		// Right-aligned so they don't collide with other plugins' lines top-left.
		addLine(parent, 5, "PriceCheck " + (isBuy ? "buy" : "sell") + ": " + Fmt.full(price), price);
		if (isSell)
		{
			final TrackedItem t = trackedFor(itemId);
			if (t != null && t.getFloor() > 0)
			{
				addLine(parent, 21, "your floor: " + Fmt.full(t.getFloor()), t.getFloor());
			}
		}
	}

	private void addLine(Widget parent, int y, String label, long price)
	{
		final Widget w = parent.createChild(-1, WidgetType.TEXT);
		w.setText(label);
		w.setTextColor(GOLD);
		w.setFontId(FontID.VERDANA_11_BOLD);
		w.setOriginalX(10);
		w.setOriginalY(y);
		w.setOriginalHeight(16);
		w.setXTextAlignment(WidgetTextAlignment.RIGHT);
		w.setWidthMode(WidgetSizeMode.MINUS);
		w.setHasListener(true);
		w.setAction(0, "Set price");
		w.setOnOpListener((JavaScriptCallback) ev -> fillInput(price));
		w.setOnMouseRepeatListener((JavaScriptCallback) ev -> w.setTextColor(WHITE));
		w.setOnMouseLeaveListener((JavaScriptCallback) ev -> w.setTextColor(GOLD));
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

	void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() != SCRIPT_GE_SEARCHBOX_BUILT || !config.geSearchSuggestions())
		{
			return;
		}
		// The search box just opened. Replay its own key listener once so the
		// search script runs with the (empty) input and our suggestions render
		// immediately instead of waiting for the first keystroke.
		clientThread.invokeLater(this::replaySearch);
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
		if (input != null && !input.trim().isEmpty())
		{
			return;   // the player is searching — vanilla behavior
		}
		final short[] ids = suggestionIds();
		if (ids.length == 0)
		{
			return;
		}
		e.consume();
		client.setGeSearchResultIndex(0);
		client.setGeSearchResultCount(ids.length);
		client.setGeSearchResultIds(ids);
	}

	// Tracked positions first (you manage those), then the board by EV. The
	// result buffer takes shorts — ids above that range can't be suggested.
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

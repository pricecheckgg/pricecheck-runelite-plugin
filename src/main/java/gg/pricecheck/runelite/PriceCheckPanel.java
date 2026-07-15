package gg.pricecheck.runelite;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

/**
 * The in-client sidebar. Two tabs: Flips (synced Tracking positions + the live
 * ranked flips with search) and Settings (your account, plugin key management, and
 * the overlay/filter options). Server-fed; empty without a valid key.
 */
class PriceCheckPanel extends PluginPanel
{
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color CARD_HOVER = ColorScheme.DARKER_GRAY_HOVER_COLOR;

	interface Listener
	{
		void onTrack(FlipData flip);
		void onUntrack(int geId);
		void onSearch(String query);
		void onFetchAccount();     // fired when the Settings tab is opened / after a key save
	}

	private final Listener listener;
	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final PriceCheckConfig config;

	// Flips tab
	private final IconTextField search = new IconTextField();
	private final JPanel list = new JPanel();

	// Settings tab widgets (built once, mutated in place)
	private final JLabel acctName = new JLabel("Loading account…");
	private final JLabel acctPlan = pill("PREMIUM", Palette.GOLD);
	private final JLabel acctSub = new JLabel(" ");
	private final JLabel keyPrefixLabel = new JLabel("—");
	private final JLabel keyDot = new JLabel("●");
	private final JPasswordField keyField = new JPasswordField();
	private final JButton saveKeyBtn = new JButton("Save key");
	private final JCheckBox advisorToggle = new JCheckBox("Offer advisor overlay");
	private final JSpinner minEvSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 50));
	private boolean settingsMuted = false;
	private MaterialTab settingsTab;
	private Timer searchDebounce;

	// State
	private List<FlipData> lastFlips = new ArrayList<>();
	private List<TrackedItem> lastTracked = new ArrayList<>();
	private List<FlipData> searchResults = new ArrayList<>();
	private String searchResultsQuery = null;
	private int minEvPerHrK = 0;
	private PriceCheckApiClient.AuthState authState = PriceCheckApiClient.AuthState.NO_KEY;

	PriceCheckPanel(Listener listener, ItemManager itemManager, ConfigManager configManager, PriceCheckConfig config)
	{
		super(false);
		this.listener = listener;
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.config = config;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JPanel display = new JPanel(new BorderLayout());
		final MaterialTabGroup tabGroup = new MaterialTabGroup(display);
		tabGroup.setLayout(new GridLayout(1, 2, 8, 0));
		tabGroup.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		final MaterialTab flipsTab = new MaterialTab("Flips", tabGroup, buildFlipsView());
		settingsTab = new MaterialTab("Settings", tabGroup, buildSettingsView());
		settingsTab.setOnSelectEvent(() -> { listener.onFetchAccount(); return true; });
		tabGroup.addTab(flipsTab);
		tabGroup.addTab(settingsTab);
		tabGroup.select(flipsTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
	}

	// ── Flips tab ──
	private JPanel buildFlipsView()
	{
		final JPanel view = new JPanel(new BorderLayout());

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(0, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.addClearListener(() -> { search.setIcon(IconTextField.Icon.SEARCH); render(); });

		// Debounce keystrokes into one server search 250ms after typing stops.
		searchDebounce = new Timer(250, e ->
		{
			final String q = search.getText().trim();
			if (q.length() >= 2) { search.setIcon(IconTextField.Icon.LOADING); listener.onSearch(q); }
		});
		searchDebounce.setRepeats(false);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			private void changed()
			{
				if (search.getText().trim().length() >= 2) { searchDebounce.restart(); }
				else { searchDebounce.stop(); search.setIcon(IconTextField.Icon.SEARCH); }
				render();
			}
			public void insertUpdate(DocumentEvent e) { changed(); }
			public void removeUpdate(DocumentEvent e) { changed(); }
			public void changedUpdate(DocumentEvent e) { changed(); }
		});

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		final ScrollList listWrap = new ScrollList();
		listWrap.add(list, BorderLayout.NORTH);

		final JScrollPane scroll = new JScrollPane(listWrap,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		final JPanel top = new JPanel(new BorderLayout());
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		top.add(search, BorderLayout.CENTER);

		view.add(top, BorderLayout.NORTH);
		view.add(scroll, BorderLayout.CENTER);
		return view;
	}

	// ── Settings tab ──
	private JPanel buildSettingsView()
	{
		final JPanel v = new JPanel();
		v.setLayout(new BoxLayout(v, BoxLayout.Y_AXIS));

		// Account card
		final JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(CARD);
		card.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
		final JPanel head = row();
		acctName.setFont(acctName.getFont().deriveFont(Font.BOLD, 13f));
		acctName.setForeground(Color.WHITE);
		head.add(acctName, BorderLayout.CENTER);
		head.add(acctPlan, BorderLayout.EAST);
		acctSub.setForeground(Palette.SUBTLE);
		acctSub.setFont(FontManager.getRunescapeSmallFont());
		acctSub.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		card.add(head);
		card.add(acctSub);
		v.add(card);
		v.add(gap(12));

		// Plugin key section
		v.add(sectionHeader("Plugin key"));
		final JPanel keyRow = row();
		keyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		keyPrefixLabel.setFont(FontManager.getRunescapeSmallFont());
		keyPrefixLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		keyDot.setFont(keyDot.getFont().deriveFont(9f));
		keyDot.setForeground(Palette.SUBTLE);
		keyRow.add(keyPrefixLabel, BorderLayout.CENTER);
		keyRow.add(keyDot, BorderLayout.EAST);
		v.add(keyRow);
		v.add(gap(6));

		keyField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		keyField.setForeground(Color.WHITE);
		keyField.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		keyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		keyField.setToolTipText("Paste a new key (pck_…)");
		saveKeyBtn.setEnabled(false);
		saveKeyBtn.setFocusPainted(false);
		saveKeyBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		saveKeyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		keyField.getDocument().addDocumentListener(new DocumentListener()
		{
			private void changed()
			{
				final String k = new String(keyField.getPassword()).trim();
				saveKeyBtn.setEnabled(k.startsWith("pck_") && k.length() >= 20);
			}
			public void insertUpdate(DocumentEvent e) { changed(); }
			public void removeUpdate(DocumentEvent e) { changed(); }
			public void changedUpdate(DocumentEvent e) { changed(); }
		});
		saveKeyBtn.addActionListener(e ->
		{
			final String k = new String(keyField.getPassword()).trim();
			configManager.setConfiguration(PriceCheckConfig.GROUP, "apiKey", k);
			keyField.setText("");
			saveKeyBtn.setEnabled(false);
			keyDot.setForeground(Palette.AMBER);
			keyDot.setToolTipText("Checking…");
			listener.onFetchAccount();
		});
		keyField.setAlignmentX(Component.LEFT_ALIGNMENT);
		v.add(keyField);
		v.add(gap(6));
		v.add(saveKeyBtn);
		v.add(gap(4));

		final JLabel link = new JLabel("Get a key · premium.pricecheck.gg");
		link.setForeground(Palette.SUBTLE);
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setAlignmentX(Component.LEFT_ALIGNMENT);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e) { LinkBrowser.browse("https://premium.pricecheck.gg"); }
			public void mouseEntered(MouseEvent e) { link.setForeground(Palette.GOLD); }
			public void mouseExited(MouseEvent e) { link.setForeground(Palette.SUBTLE); }
		});
		v.add(link);
		v.add(gap(12));

		// Options section
		v.add(sectionHeader("Options"));
		advisorToggle.setOpaque(false);
		advisorToggle.setForeground(Color.WHITE);
		advisorToggle.setFocusPainted(false);
		advisorToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		advisorToggle.setSelected(config.showAdvisor());
		advisorToggle.addItemListener(e ->
		{
			if (!settingsMuted)
			{
				configManager.setConfiguration(PriceCheckConfig.GROUP, "showAdvisor", advisorToggle.isSelected());
			}
		});
		v.add(advisorToggle);
		v.add(gap(6));

		final JPanel evRow = row();
		evRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel evLbl = new JLabel("Min EV/hr (k)");
		evLbl.setForeground(Palette.SUBTLE);
		minEvSpinner.setValue(config.minEvPerHrK());
		minEvSpinner.setPreferredSize(new Dimension(64, 24));
		minEvSpinner.setMaximumSize(new Dimension(64, 24));
		minEvSpinner.addChangeListener(e ->
		{
			if (!settingsMuted)
			{
				configManager.setConfiguration(PriceCheckConfig.GROUP, "minEvPerHrK", minEvSpinner.getValue());
			}
		});
		final JPanel spinWrap = new JPanel(new BorderLayout());
		spinWrap.setOpaque(false);
		spinWrap.add(minEvSpinner, BorderLayout.EAST);
		evRow.add(evLbl, BorderLayout.WEST);
		evRow.add(spinWrap, BorderLayout.EAST);
		v.add(evRow);

		final JLabel footer = new JLabel("PriceCheck");
		footer.setForeground(Palette.SUBTLE);
		footer.setFont(footer.getFont().deriveFont(10f));
		footer.setAlignmentX(Component.LEFT_ALIGNMENT);
		footer.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
		v.add(footer);

		final ScrollList sWrap = new ScrollList();
		sWrap.add(v, BorderLayout.NORTH);
		final JScrollPane sc = new JScrollPane(sWrap,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sc.setBorder(null);
		sc.getVerticalScrollBar().setUnitIncrement(16);
		final JPanel holder = new JPanel(new BorderLayout());
		holder.add(sc, BorderLayout.CENTER);
		return holder;
	}

	// ── data in ──
	void update(PriceCheckApiClient.FlipsResult flips, PriceCheckApiClient.TrackedResult tracked, int minEv)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.authState = flips.state;
			this.minEvPerHrK = minEv;
			if (flips.state == PriceCheckApiClient.AuthState.OK) { this.lastFlips = flips.flips; }
			if (tracked != null && tracked.state == PriceCheckApiClient.AuthState.OK) { this.lastTracked = tracked.tracked; }
			// keep the key dot honest with the live auth state
			if (authState == PriceCheckApiClient.AuthState.OK) { setDot(Palette.GREEN, "Key active"); }
			else if (authState == PriceCheckApiClient.AuthState.INVALID_KEY) { setDot(Palette.RED, "Key rejected"); }
			render();
		});
	}

	void setSearchResults(String query, PriceCheckApiClient.FlipsResult result)
	{
		SwingUtilities.invokeLater(() ->
		{
			searchResults = result != null && result.state == PriceCheckApiClient.AuthState.OK ? result.flips : new ArrayList<>();
			searchResultsQuery = query;
			if (search.getText().trim().equals(query)) { search.setIcon(IconTextField.Icon.SEARCH); }
			render();
		});
	}

	void setAccount(AccountInfo acct)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (acct == null)
			{
				return;   // keep the last good card on a blip
			}
			acctName.setText(acct.getUsername() != null ? acct.getUsername() : "PriceCheck member");
			acctPlan.setText(" " + (acct.getPlan() != null ? acct.getPlan().toUpperCase(Locale.ROOT) : "PREMIUM") + " ");
			acctSub.setText(acct.getTrackedCount() + (acct.getTrackedCount() == 1 ? " position tracked" : " positions tracked"));
			if (acct.getKeyPrefix() != null) { keyPrefixLabel.setText(acct.getKeyPrefix()); }
		});
	}

	// keep the config-dialog and the Settings tab consistent without write loops
	void syncSettings()
	{
		SwingUtilities.invokeLater(() ->
		{
			settingsMuted = true;
			if (advisorToggle.isSelected() != config.showAdvisor()) { advisorToggle.setSelected(config.showAdvisor()); }
			if (!minEvSpinner.getValue().equals(config.minEvPerHrK())) { minEvSpinner.setValue(config.minEvPerHrK()); }
			settingsMuted = false;
		});
	}

	private void setDot(Color c, String tip)
	{
		keyDot.setForeground(c);
		keyDot.setToolTipText(tip);
	}

	// ── render the flips tab ──
	private void render()
	{
		list.removeAll();

		final Set<Integer> trackedIds = new HashSet<>();
		for (TrackedItem t : lastTracked) { trackedIds.add(t.getGeId()); }

		final String q = search.getText().trim();
		final boolean searching = q.length() >= 2;

		if (authState != PriceCheckApiClient.AuthState.OK && !searching)
		{
			list.add(emptyState());
			list.add(Box.createVerticalGlue());
			list.revalidate();
			list.repaint();
			return;
		}

		// Tracking section
		if (!searching && !lastTracked.isEmpty())
		{
			list.add(sectionHeader("Tracking (" + lastTracked.size() + ")"));
			for (TrackedItem t : lastTracked) { list.add(trackingCard(t)); list.add(gap(6)); }
			list.add(gap(6));
		}

		// Flips / search
		final List<FlipData> shown = new ArrayList<>();
		boolean pending = false;
		if (searching)
		{
			if (q.equals(searchResultsQuery)) { shown.addAll(searchResults); }
			else { pending = true; }
		}
		else
		{
			for (FlipData f : lastFlips)
			{
				if (minEvPerHrK > 0 && f.getEvPerHr() < minEvPerHrK * 1000L) { continue; }
				shown.add(f);
			}
		}

		final String header = searching
			? (pending ? "Searching \"" + q + "\"…" : shown.size() + " match \"" + q + "\"")
			: (minEvPerHrK > 0 ? shown.size() + " flips · ≥" + minEvPerHrK + "k/hr" : shown.size() + " flips · by EV/hr");
		list.add(sectionHeader(header));

		if (authState == PriceCheckApiClient.AuthState.ERROR)
		{
			list.add(note("Reconnecting…", Palette.AMBER));
		}

		if (pending)
		{
			// searching icon on the field; nothing else to show yet
		}
		else if (shown.isEmpty())
		{
			list.add(note(searching ? "No items match." : "No flips right now.", Palette.SUBTLE));
		}
		else
		{
			for (FlipData f : shown) { list.add(flipRow(f, trackedIds.contains(f.getGeId()))); list.add(gap(5)); }
		}

		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	// ── flip row: icon | name (wraps) / buy→sell+profit / EV | track toggle ──
	private JPanel flipRow(FlipData f, boolean tracked)
	{
		final JPanel rowP = new JPanel(new BorderLayout(6, 0));
		rowP.setBackground(CARD);
		rowP.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		rowP.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		itemManager.getImage(f.getGeId()).addTo(icon);

		// The name gets the whole first line and wraps instead of truncating —
		// the fixed body width makes the HTML label report a real wrapped
		// preferred height, which plain labels in a BorderLayout won't.
		final JLabel name = new JLabel("<html><body style='width:126px'><b>" + escHtml(f.getName()) + "</b></body></html>");
		name.setForeground(Color.WHITE);
		final JPanel line1 = row();
		line1.add(name, BorderLayout.CENTER);

		final JLabel bs = mono(Fmt.compact(f.getBuy()) + " → " + Fmt.compact(f.getSell()), Palette.SUBTLE);
		final JLabel profit = mono("+" + Fmt.compact(f.getProfit()), f.getProfit() >= 0 ? Palette.GREEN : Palette.RED);
		profit.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line2 = row();
		line2.add(bs, BorderLayout.CENTER);
		line2.add(profit, BorderLayout.EAST);

		final JLabel ev = mono(Fmt.compact(f.getEvPerHr()) + "/hr", Palette.GOLD);
		ev.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line3 = row();
		line3.add(ev, BorderLayout.EAST);

		if (f.isConfirmed())
		{
			final JLabel dot = new JLabel("✓ ");
			dot.setForeground(Palette.GREEN);
			dot.setToolTipText("Margin confirmed across the 5m + 1h books");
			line1.add(dot, BorderLayout.WEST);
		}

		final JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.add(line1, BorderLayout.NORTH);
		final JPanel lower = new JPanel(new BorderLayout());
		lower.setOpaque(false);
		lower.add(line2, BorderLayout.NORTH);
		lower.add(line3, BorderLayout.SOUTH);
		center.add(lower, BorderLayout.SOUTH);

		final JLabel trk = new JLabel(tracked ? "✓" : "+", SwingConstants.CENTER);
		trk.setPreferredSize(new Dimension(18, 34));
		trk.setForeground(tracked ? Palette.GREEN : Palette.SUBTLE);
		trk.setFont(trk.getFont().deriveFont(Font.BOLD, 15f));
		trk.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		trk.setToolTipText(tracked ? "Tracking — click to stop" : "Track this position");
		final boolean[] fired = { false };
		trk.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e)
			{
				if (fired[0]) { return; }   // guard against double-fire before the next poll
				fired[0] = true;
				trk.setText(tracked ? "+" : "✓");
				trk.setForeground(tracked ? Palette.SUBTLE : Palette.GREEN);
				if (tracked) { listener.onUntrack(f.getGeId()); } else { listener.onTrack(f); }
			}
		});
		rowP.addMouseListener(new MouseAdapter()
		{
			public void mouseEntered(MouseEvent e) { rowP.setBackground(CARD_HOVER); }
			public void mouseExited(MouseEvent e) { rowP.setBackground(CARD); }
		});

		rowP.add(icon, BorderLayout.WEST);
		rowP.add(center, BorderLayout.CENTER);
		rowP.add(trk, BorderLayout.EAST);
		// Height follows the (possibly wrapped) name; without this cap the
		// BoxLayout parent stretches rows to fill leftover space.
		rowP.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowP.getPreferredSize().height));
		return rowP;
	}

	private static String escHtml(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	// ── tracking card ──
	private JPanel trackingCard(TrackedItem t)
	{
		final JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(CARD);
		card.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 128));

		final JPanel head = row();
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 20));
		itemManager.getImage(t.getGeId()).addTo(icon);
		final JLabel name = new JLabel(t.getName());
		name.setFont(name.getFont().deriveFont(Font.BOLD));
		name.setForeground(Color.WHITE);
		final JLabel rm = new JLabel("✕");
		rm.setForeground(Palette.SUBTLE);
		rm.setToolTipText("Stop tracking");
		rm.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		rm.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 2));
		rm.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e) { listener.onUntrack(t.getGeId()); }
			public void mouseEntered(MouseEvent e) { rm.setForeground(Palette.RED); }
			public void mouseExited(MouseEvent e) { rm.setForeground(Palette.SUBTLE); }
		});
		final JPanel nameWrap = new JPanel(new BorderLayout(6, 0));
		nameWrap.setOpaque(false);
		nameWrap.add(icon, BorderLayout.WEST);
		nameWrap.add(name, BorderLayout.CENTER);
		head.add(nameWrap, BorderLayout.CENTER);
		head.add(rm, BorderLayout.EAST);
		card.add(head);

		card.add(kv("Bought", Fmt.full(t.getEntryBuy())));
		card.add(kv("Sell now", t.getSellNow() != null ? Fmt.full(t.getSellNow()) : "—"));

		final boolean nodata = "nodata".equals(t.getStatus());
		final boolean thin = "thin".equals(t.getStatus());
		final Color pnlCol = t.getPnl() > 0 ? Palette.GREEN : (t.getPnl() < 0 ? Palette.RED : Palette.SUBTLE);
		final JLabel pnl = new JLabel((t.getPnl() > 0 ? "+" : "") + Fmt.compact(t.getPnl())
			+ "  ·  " + String.format(Locale.ROOT, "%.1f%%", t.getRoi()) + " if sold now");
		pnl.setForeground(pnlCol);
		pnl.setFont(pnl.getFont().deriveFont(Font.BOLD, 13f));
		pnl.setAlignmentX(Component.LEFT_ALIGNMENT);
		pnl.setBorder(BorderFactory.createEmptyBorder(3, 0, 2, 0));
		pnl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		card.add(pnl);

		final JPanel st = row();
		st.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		st.add(pill(nodata ? "no data" : (thin ? "thin" : "healthy"),
			nodata ? Palette.SUBTLE : (thin ? Palette.RED : Palette.GREEN)), BorderLayout.WEST);
		final JLabel hint = new JLabel(nodata ? "not trading" : (thin ? "margin closing" : "good to hold"));
		hint.setForeground(Palette.SUBTLE);
		hint.setHorizontalAlignment(SwingConstants.RIGHT);
		st.add(hint, BorderLayout.EAST);
		card.add(st);

		final JLabel floor = new JLabel("floor " + Fmt.full(t.getFloor()) + " · don't sell below");
		floor.setForeground(Palette.SUBTLE);
		floor.setFont(FontManager.getRunescapeSmallFont());
		floor.setAlignmentX(Component.LEFT_ALIGNMENT);
		floor.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		floor.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		card.add(floor);
		return card;
	}

	// ── empty / auth state with an action ──
	private JPanel emptyState()
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(14, 4, 4, 4));
		String title, body;
		switch (authState)
		{
			case INVALID_KEY: title = "Key not recognised"; body = "Regenerate it at premium.pricecheck.gg, then paste it in Settings."; break;
			case NO_SUBSCRIPTION: title = "Subscription inactive"; body = "Renew at premium.pricecheck.gg to see live flips."; break;
			case ERROR: title = "Can't reach PriceCheck"; body = "Retrying…"; break;
			default: title = "Add your plugin key"; body = "Open Settings and paste your key to see live flips."; break;
		}
		final JLabel t = new JLabel(title);
		t.setForeground(Palette.GOLD);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel b = new JLabel("<html><body style='width:180px'>" + body + "</body></html>");
		b.setForeground(Palette.SUBTLE);
		b.setBorder(BorderFactory.createEmptyBorder(6, 0, 10, 0));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JButton open = new JButton("Open Settings");
		open.setFocusPainted(false);
		open.setAlignmentX(Component.LEFT_ALIGNMENT);
		open.addActionListener(e -> { if (settingsTab != null) { settingsTab.select(); } });
		p.add(t); p.add(b); p.add(open);
		return p;
	}

	// ── small building blocks ──
	private JPanel row()
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JLabel sectionHeader(String text)
	{
		final JLabel h = new JLabel(text.toUpperCase(Locale.ROOT));
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(Palette.GOLD);
		h.setBorder(BorderFactory.createEmptyBorder(2, 1, 6, 0));
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		h.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		return h;
	}

	private JPanel kv(String k, String v)
	{
		final JPanel p = row();
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		final JLabel kl = new JLabel(k);
		kl.setForeground(Palette.SUBTLE);
		kl.setFont(FontManager.getRunescapeSmallFont());
		final JLabel vl = mono(v, Color.WHITE);
		vl.setHorizontalAlignment(SwingConstants.RIGHT);
		p.add(kl, BorderLayout.WEST);
		p.add(vl, BorderLayout.EAST);
		return p;
	}

	private static JLabel pill(String text, Color col)
	{
		final JLabel p = new JLabel(" " + text + " ");
		p.setOpaque(true);
		p.setBackground(new Color(col.getRed(), col.getGreen(), col.getBlue(), 40));
		p.setForeground(col);
		p.setFont(p.getFont().deriveFont(10f));
		p.setBorder(BorderFactory.createEmptyBorder(1, 3, 1, 3));
		return p;
	}

	private static JLabel mono(String text, Color col)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(col);
		l.setFont(FontManager.getRunescapeSmallFont());
		return l;
	}

	private JLabel note(String text, Color col)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(col);
		l.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return l;
	}

	private Component gap(int h)
	{
		return Box.createRigidArea(new Dimension(0, h));
	}

	/** Scroll content that matches the viewport WIDTH (so rows never overflow to the
	 * right and clip the EV/profit column) while growing taller than it for vertical
	 * scroll. Holds its child at NORTH so rows keep their preferred height. */
	private static final class ScrollList extends JPanel implements Scrollable
	{
		ScrollList() { super(new BorderLayout()); }
		public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		public int getScrollableUnitIncrement(Rectangle r, int orient, int dir) { return 16; }
		public int getScrollableBlockIncrement(Rectangle r, int orient, int dir) { return 48; }
		public boolean getScrollableTracksViewportWidth() { return true; }
		public boolean getScrollableTracksViewportHeight() { return false; }
	}
}

package gg.pricecheck.runelite;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
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
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
		// capital < 0 = "use my last reported bank total" (server-side fallback)
		void onBuildPlan(long capital, int slots, int accounts, int hours);
		void onDeleteFlip(String flipId);
		void onDeleteLot(int itemId, int qty, long cost, long openedAt);
		void onOpenPluginConfig();   // jump to the RuneLite config panel for this plugin
	}

	private final Listener listener;
	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final PriceCheckConfig config;

	// Flips tab
	private final IconTextField search = new IconTextField();
	private final JPanel list = new JPanel();

	// Plan tab widgets
	private final javax.swing.JTextField planCapital = new javax.swing.JTextField();
	private final JSpinner planSlots = new JSpinner(new SpinnerNumberModel(8, 1, 8, 1));
	private final JSpinner planAccts = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
	private final JComboBox<String> planHours = new JComboBox<>(new String[]{"1 hour", "4 hours", "Overnight"});
	private static final int[] PLAN_HOURS_VALUES = {1, 4, 8};
	private final JButton planBuild = new JButton("Build plan");
	private final JLabel planStatus = new JLabel(" ");
	private final JPanel planList = new JPanel();
	private boolean planCapitalEdited = false;   // stop auto-detect from clobbering typed capital
	private boolean planCapitalMuted = false;    // programmatic set must not flip the edited flag
	private long detectedCapital = -1;           // exact bank+inventory total; the field shows a display copy

	// Settings tab widgets (built once, mutated in place)
	private final JLabel acctName = new JLabel("Loading account…");
	private final JLabel acctPlan = pill("PREMIUM", Palette.GOLD);
	private final JLabel acctSub = new JLabel(" ");
	private final JLabel keyPrefixLabel = new JLabel("-");
	private final Dot keyDot = new Dot();
	private final JPasswordField keyField = new JPasswordField();
	private final JButton saveKeyBtn = new JButton("Save key");
	private final JCheckBox syncToggle = new JCheckBox("Sync flip log");
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
		tabGroup.setLayout(new GridLayout(1, 4, 6, 0));
		tabGroup.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		final MaterialTab flipsTab = new MaterialTab("Flips", tabGroup, buildFlipsView());
		final MaterialTab logTab = new MaterialTab("Log", tabGroup, buildLogView());
		final MaterialTab planTab = new MaterialTab("Plan", tabGroup, buildPlanView());
		settingsTab = new MaterialTab("Setup", tabGroup, buildSettingsView());
		settingsTab.setOnSelectEvent(() -> { listener.onFetchAccount(); return true; });
		// Equal grid cells clip long labels ("Settings" read "Set..."), and the
		// default left-hung labels looked ragged; short names, centred, one size.
		for (final MaterialTab t : new MaterialTab[]{ flipsTab, logTab, planTab, settingsTab })
		{
			t.setHorizontalAlignment(SwingConstants.CENTER);
			t.setFont(t.getFont().deriveFont(Font.BOLD, 12f));
		}
		tabGroup.addTab(flipsTab);
		tabGroup.addTab(logTab);
		tabGroup.addTab(planTab);
		tabGroup.addTab(settingsTab);
		tabGroup.select(flipsTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
	}

	/** Dev preview only: the Log header panel, for headless rendering. */
	JPanel logHeaderForPreview()
	{
		return logHeader;
	}

	/** Dev preview only: the Setup tab's Discord card, for headless rendering. */
	JPanel discordCardForPreview()
	{
		return discordCard();
	}

	// Community card in the Setup tab: one quiet row, not a banner. Two short
	// subtitle lines instead of one wide one (Swing's HTML width wrapping is
	// unreliable in game-panel labels, so the copy is split explicitly). Click
	// anywhere opens the invite.
	private JPanel discordCard()
	{
		final JPanel disc = new JPanel(new BorderLayout(8, 0));
		disc.setBackground(CARD);
		disc.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
		disc.setAlignmentX(Component.LEFT_ALIGNMENT);
		disc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

		final JLabel dTitle = new JLabel("Join the PriceCheck Discord");
		dTitle.setForeground(new Color(0x93, 0xa1, 0xff));
		dTitle.setFont(dTitle.getFont().deriveFont(Font.BOLD, 12f));
		dTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel dSub1 = new JLabel("Flip chat and price checks");
		final JLabel dSub2 = new JLabel("Giveaways and community help");
		for (final JLabel l : new JLabel[]{dSub1, dSub2})
		{
			l.setForeground(Palette.SUBTLE);
			l.setFont(FontManager.getRunescapeSmallFont());
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		final JPanel dText = new JPanel();
		dText.setLayout(new BoxLayout(dText, BoxLayout.Y_AXIS));
		dText.setOpaque(false);
		dText.add(dTitle);
		dText.add(Box.createVerticalStrut(2));
		dText.add(dSub1);
		dText.add(dSub2);
		disc.add(dText, BorderLayout.CENTER);

		disc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		disc.setToolTipText("discord.gg/pricecheck");
		disc.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse("https://discord.gg/pricecheck");
			}
		});
		return disc;
	}

	/** Dev preview only: the scrollable content (header + list), full height. */
	JPanel logBodyForPreview()
	{
		return logBody;
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

	// ── Log tab: the FREE flip ledger (works with no key at all) ──
	// Header: a session hero, a borderless Today/Week/All-time column strip
	// between hairline dividers, one meta line, and a painted status dot
	// (the RuneScape font has no dot glyph, so it is drawn, not typed).
	private static final Color HAIRLINE = new Color(48, 48, 48);
	private final JLabel heroTitle = new JLabel("SESSION");
	private final JLabel heroValue = new JLabel(" ");
	private final JLabel heroSub = new JLabel(" ");
	private final JLabel cellToday = new JLabel(" ");
	private final JLabel cellWeek = new JLabel(" ");
	private final JLabel cellAll = new JLabel(" ");
	private final JLabel cellFlips = new JLabel(" ");
	private final JLabel cellWon = new JLabel(" ");
	private final JLabel cellRoi = new JLabel(" ");

	// ── inline evidence chart on the Flips tab ──
	// Click a board row and its day chart expands underneath, with your own
	// logged fills painted on it. The plugin supplies cached series data.
	private int expandedChartId = 0;
	private java.util.function.IntFunction<PriceCheckApiClient.SeriesData> seriesSupplier;
	private volatile FlipLogEngine.Summary lastLogSummary;

	void setSeriesSupplier(java.util.function.IntFunction<PriceCheckApiClient.SeriesData> s)
	{
		seriesSupplier = s;
	}
	private final Dot logSyncDot = new Dot();
	private final JLabel logSync = new JLabel(" ");
	private final JPanel logList = new JPanel();
	private JPanel logHeader;
	private JPanel logBody;
	private volatile boolean syncOpensWeb;

	private static final class Dot extends JComponent
	{
		private Color color = Palette.SUBTLE;

		Dot()
		{
			setPreferredSize(new Dimension(9, 14));
			setMinimumSize(getPreferredSize());
			setMaximumSize(getPreferredSize());
		}

		void setColor(Color c)
		{
			color = c;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(0, (getHeight() - 7) / 2, 7, 7);
		}
	}

	/** A small painted ">" between buy and sell — the RuneScape font has no arrow glyph. */
	private static final class Chevron extends JComponent
	{
		private final Color color;

		Chevron(Color c)
		{
			color = c;
			setPreferredSize(new Dimension(6, 12));
			setMinimumSize(getPreferredSize());
			setMaximumSize(getPreferredSize());
			setAlignmentY(0.5f);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			final int cy = getHeight() / 2;
			g2.drawLine(1, cy - 3, 4, cy);
			g2.drawLine(4, cy, 1, cy + 3);
		}
	}

	/** Painted checkbox faces: a dark rounded box, gold check when selected.
	 * The stock Swing checkbox is the one control that never matches the
	 * panel's look. */
	private static final class BoxIcon implements javax.swing.Icon
	{
		private final boolean checked;

		BoxIcon(boolean checked)
		{
			this.checked = checked;
		}

		public int getIconWidth()
		{
			return 15;
		}

		public int getIconHeight()
		{
			return 15;
		}

		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			final Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.translate(x, y);
			g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, 14, 14, 4, 4);
			g2.setColor(new Color(0x55, 0x50, 0x45));
			g2.drawRoundRect(0, 0, 14, 14, 4, 4);
			if (checked)
			{
				g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(Palette.GOLD);
				g2.drawLine(3, 7, 6, 10);
				g2.drawLine(6, 10, 11, 4);
			}
			g2.dispose();
		}
	}

	private static void styleCheckbox(JCheckBox box)
	{
		box.setIcon(new BoxIcon(false));
		box.setSelectedIcon(new BoxIcon(true));
		box.setIconTextGap(8);
	}

	/** A painted check, plus, or cross — the RuneScape font has none of them. */
	private static final class Mark extends JComponent
	{
		static final int CHECK = 0;
		static final int PLUS = 1;
		static final int CROSS = 2;

		private int type;
		private Color color;

		Mark(int type, Color color, int w, int h)
		{
			this.type = type;
			this.color = color;
			setPreferredSize(new Dimension(w, h));
			setMinimumSize(getPreferredSize());
			setMaximumSize(getPreferredSize());
		}

		void set(int type, Color color)
		{
			this.type = type;
			this.color = color;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(color);
			final int cx = getWidth() / 2;
			final int cy = getHeight() / 2;
			if (type == CHECK)
			{
				g2.drawLine(cx - 4, cy, cx - 1, cy + 3);
				g2.drawLine(cx - 1, cy + 3, cx + 4, cy - 4);
			}
			else if (type == PLUS)
			{
				g2.drawLine(cx - 4, cy, cx + 4, cy);
				g2.drawLine(cx, cy - 4, cx, cy + 4);
			}
			else
			{
				g2.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
				g2.drawLine(cx + 3, cy - 3, cx - 3, cy + 3);
			}
		}
	}

	/** Signed gp at three significant figures: +873k, +3.99m, -12.4m, +1.02b. */
	private static void setStat(JLabel cell, long v)
	{
		cell.setText(statGp(v));
		cell.setForeground(v > 0 ? Palette.GREEN : (v < 0 ? Palette.RED : Palette.SUBTLE));
	}

	private static String statGp(long v)
	{
		final long a = Math.abs(v);
		final String sign = v > 0 ? "+" : (v < 0 ? "-" : "");
		if (a >= 999_500_000L) return sign + sig3(a / 1e9) + "b";
		if (a >= 999_500L) return sign + sig3(a / 1e6) + "m";
		if (a >= 1_000L) return sign + sig3(a / 1e3) + "k";
		return sign + a;
	}

	private static String sig3(double x)
	{
		String s = x >= 100 ? String.format(java.util.Locale.ROOT, "%.0f", x)
			: (x >= 10 ? String.format(java.util.Locale.ROOT, "%.1f", x)
			: String.format(java.util.Locale.ROOT, "%.2f", x));
		if (s.contains("."))
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}

	private JPanel statCol(String caption, JLabel value)
	{
		final JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setBackground(CARD);
		final JLabel cap = new JLabel(caption);
		cap.setFont(FontManager.getRunescapeSmallFont());
		cap.setForeground(Palette.SUBTLE);
		cap.setAlignmentX(Component.LEFT_ALIGNMENT);
		value.setFont(FontManager.getRunescapeBoldFont());
		value.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(cap);
		col.add(Box.createVerticalStrut(2));
		col.add(value);
		return col;
	}

	private Component hairline()
	{
		final JPanel line = new JPanel();
		line.setBackground(HAIRLINE);
		line.setPreferredSize(new Dimension(1, 1));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		return line;
	}

	private JPanel buildLogView()
	{
		final JPanel head = new JPanel();
		logHeader = head;
		head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
		head.setBackground(CARD);
		head.setBorder(BorderFactory.createEmptyBorder(9, 12, 8, 12));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Hero: the session owns the header. Kept compact so the completed-flip
		// list below stays visible without scrolling on a short panel.
		heroTitle.setFont(FontManager.getRunescapeSmallFont());
		heroTitle.setForeground(Palette.SUBTLE);
		heroValue.setFont(FontManager.getRunescapeBoldFont().deriveFont(20f));
		heroValue.setForeground(Palette.SUBTLE);
		heroSub.setFont(FontManager.getRunescapeSmallFont());
		heroSub.setForeground(Palette.SUBTLE);
		for (final JLabel l : new JLabel[]{heroTitle, heroValue, heroSub})
		{
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		head.add(heroTitle);
		head.add(Box.createVerticalStrut(1));
		head.add(heroValue);
		head.add(Box.createVerticalStrut(2));
		head.add(heroSub);
		head.add(Box.createVerticalStrut(8));
		head.add(hairline());
		head.add(Box.createVerticalStrut(7));

		final JPanel cols = new JPanel(new GridLayout(1, 3, 8, 0));
		cols.setBackground(CARD);
		cols.setAlignmentX(Component.LEFT_ALIGNMENT);
		cols.add(statCol("TODAY", cellToday));
		cols.add(statCol("WEEK", cellWeek));
		cols.add(statCol("ALL TIME", cellAll));
		cols.setMaximumSize(new Dimension(Integer.MAX_VALUE, cols.getPreferredSize().height));
		head.add(cols);
		head.add(Box.createVerticalStrut(7));
		head.add(hairline());
		head.add(Box.createVerticalStrut(6));

		// Second strip: the ledger's shape rather than its gp. Checks and
		// untracked sells live in the tooltips — detail, not headline.
		final JPanel cols2 = new JPanel(new GridLayout(1, 3, 8, 0));
		cols2.setBackground(CARD);
		cols2.setAlignmentX(Component.LEFT_ALIGNMENT);
		cols2.add(statCol("FLIPS", cellFlips));
		cols2.add(statCol("WON", cellWon));
		cols2.add(statCol("AVG ROI", cellRoi));
		cols2.setMaximumSize(new Dimension(Integer.MAX_VALUE, cols2.getPreferredSize().height));
		head.add(cols2);
		head.add(Box.createVerticalStrut(8));

		// Sync status: painted dot + one short line. Opens the web portfolio
		// once backed up, Settings while local-only.
		final JPanel sync = new JPanel(new BorderLayout(6, 0));
		sync.setBackground(CARD);
		sync.setAlignmentX(Component.LEFT_ALIGNMENT);
		logSync.setFont(FontManager.getRunescapeSmallFont());
		logSync.setForeground(Palette.SUBTLE);
		sync.add(logSyncDot, BorderLayout.WEST);
		sync.add(logSync, BorderLayout.CENTER);
		sync.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		sync.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		sync.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e)
			{
				if (syncOpensWeb)
				{
					LinkBrowser.browse("https://flipping.pricecheck.gg/portfolio");
				}
				else if (settingsTab != null)
				{
					settingsTab.select();
				}
			}
		});
		head.add(sync);

		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));

		// The list stacks vertically. (This line was lost in the header rework,
		// leaving the default FlowLayout: one 42px row, everything else clipped
		// out of view. That is where the completed flips "went".)
		logList.setLayout(new BoxLayout(logList, BoxLayout.Y_AXIS));

		final JPanel body = new JPanel();
		logBody = body;
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		// Explicit panel background: the gaps between cards are part of the
		// design, and the headless preview harness has no dark L&F to inherit.
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		logList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		logList.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(head);
		body.add(gap(6));
		body.add(logList);

		final ScrollList wrap = new ScrollList();
		wrap.add(body, BorderLayout.NORTH);
		final JScrollPane scroll = new JScrollPane(wrap,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		final JPanel holder = new JPanel(new BorderLayout());
		holder.add(scroll, BorderLayout.CENTER);
		return holder;
	}

	private static String gpSign(long v)
	{
		return (v > 0 ? "+" : "") + Fmt.compact(v);
	}

	private static String dur(long ms)
	{
		final long m = ms / 60_000L;
		if (m < 1)
		{
			return "<1m";
		}
		if (m < 60)
		{
			return m + "m";
		}
		final long h = m / 60;
		if (h < 24)
		{
			return h + "h " + (m % 60) + "m";
		}
		return (h / 24) + "d " + (h % 24) + "h";
	}

	void setFlipLog(FlipLogEngine.Summary s, boolean hasKey)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (s == null)
			{
				return;
			}
			lastLogSummary = s;   // the expanded chart paints your fills from it
			heroValue.setText(s.sessionProfit == 0 ? "0 gp" : statGp(s.sessionProfit));
			heroValue.setForeground(s.sessionProfit > 0 ? Palette.GREEN : (s.sessionProfit < 0 ? Palette.RED : Palette.SUBTLE));
			heroSub.setText(s.sessionProfit == 0 && s.sessionGpHr <= 0
				? "no flips yet this session"
				: (s.sessionGpHr != Long.MIN_VALUE
					? statGp(s.sessionGpHr) + "/hr while flipping"
					: "gp/hr shows after a few active minutes"));
			setStat(cellToday, s.todayProfit);
			setStat(cellWeek, s.weekProfit);
			setStat(cellAll, s.allProfit);
			cellAll.setToolTipText("Tax paid: " + Fmt.compact(s.allTax) + " gp");
			cellFlips.setText(Fmt.full(s.allFlips));
			cellFlips.setForeground(Palette.LIGHT);
			cellFlips.setToolTipText((s.checks > 0 ? s.checks + " margin checks kept out of these totals" : "Margin checks are kept out of these totals")
				+ (s.untrackedSells > 0 ? ". " + s.untrackedSells + " sold items had no tracked buy, so they count at zero cost" : ""));
			cellWon.setText(s.winRatePct >= 0 ? s.winRatePct + "%" : "-");
			cellWon.setForeground(Palette.LIGHT);
			cellWon.setToolTipText(s.winRatePct >= 0 ? s.allWins + " of " + s.allFlips + " closed in profit" : null);
			if (Double.isNaN(s.avgRoiPct))
			{
				cellRoi.setText("-");
				cellRoi.setForeground(Palette.SUBTLE);
			}
			else
			{
				cellRoi.setText(pctSigned(s.avgRoiPct));
				cellRoi.setForeground(s.avgRoiPct > 0 ? Palette.GREEN : (s.avgRoiPct < 0 ? Palette.RED : Palette.SUBTLE));
			}
			cellRoi.setToolTipText("Profit vs gp spent across your logged flips, checks excluded");
			syncOpensWeb = hasKey && s.pendingSync == 0;
			logSyncDot.setColor(hasKey ? (s.pendingSync > 0 ? Palette.AMBER : Palette.GREEN) : Palette.SUBTLE);
			// Two-tone: state stays quiet, the clickable action reads as a link.
			logSync.setText(hasKey
				? (s.pendingSync > 0
					? "Backing up " + s.pendingSync + " fills…"
					: "<html><span style='color:#9a917c'>Backed up · </span><span style='color:#e6c667'>open web portfolio</span></html>")
				: "<html><span style='color:#9a917c'>Local only · </span><span style='color:#e6c667'>back up in Setup</span></html>");

			logList.removeAll();
			if (!s.openLots.isEmpty())
			{
				logList.add(sectionHeader("Open positions · " + s.openLots.size()));
				for (final FlipLogEngine.Lot l : s.openLots)
				{
					logList.add(lotRow(l));
					logList.add(gap(5));
				}
				logList.add(gap(4));
			}
			logList.add(sectionHeader(s.recent.size() < s.allFlips
				? "Completed flips · last " + s.recent.size()
				: "Completed flips"));
			if (s.recent.isEmpty())
			{
				logList.add(note("Buy and sell on the GE and flips appear here. No key needed.", Palette.SUBTLE));
			}
			for (final FlipLogEngine.Flip f : s.recent)
			{
				logList.add(logFlipRow(f));
				logList.add(gap(5));
			}
			logList.add(Box.createVerticalGlue());
			logList.revalidate();
			logList.repaint();
		});
	}

	private JPanel lotRow(FlipLogEngine.Lot l)
	{
		final JPanel rowP = new JPanel(new BorderLayout(6, 0));
		rowP.setBackground(CARD);
		rowP.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		rowP.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 30));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null) { itemManager.getImage(l.itemId).addTo(icon); }
		final JLabel name = new JLabel("<html><body style='width:126px'><b>"
			+ escHtml(l.name != null ? l.name : ("#" + l.itemId)) + "</b></body></html>");
		name.setForeground(Color.WHITE);
		final JPanel line1 = row();
		line1.add(name, BorderLayout.CENTER);
		final JLabel amt = mono(Fmt.full(l.qty) + " @ " + Fmt.compact(l.qty > 0 ? l.cost / l.qty : l.cost), Palette.SUBTLE);
		final JLabel age = mono("held " + dur(System.currentTimeMillis() - l.openedAt), Palette.SUBTLE);
		age.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line2 = row();
		line2.add(amt, BorderLayout.CENTER);
		line2.add(age, BorderLayout.EAST);
		final JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.add(line1, BorderLayout.NORTH);
		center.add(line2, BorderLayout.SOUTH);
		rowP.add(icon, BorderLayout.WEST);
		rowP.add(center, BorderLayout.CENTER);
		rowP.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowP.getPreferredSize().height));
		final String lotLabel = (l.name != null ? l.name : ("#" + l.itemId)) + " × " + l.qty;
		attachDeleteMenu(rowP, "Remove position…",
			"Remove " + lotLabel + " from tracking?\nA later sell of it will show as untracked instead of a flip.",
			() -> listener.onDeleteLot(l.itemId, l.qty, l.cost, l.openedAt));
		return rowP;
	}

	// Right-click delete on log rows: menu, then an explicit confirm. Deletes
	// propagate to the web portfolio and any other machine on the next sync.
	private void attachDeleteMenu(JPanel rowP, String menuLabel, String confirmText, Runnable action)
	{
		final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		final javax.swing.JMenuItem item = new javax.swing.JMenuItem(menuLabel);
		item.addActionListener(ev ->
		{
			final int ok = javax.swing.JOptionPane.showConfirmDialog(rowP, confirmText, "PriceCheck",
				javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (ok == javax.swing.JOptionPane.OK_OPTION)
			{
				action.run();
			}
		});
		menu.add(item);
		rowP.setComponentPopupMenu(menu);
	}

	/** "1 @ 40.0m · -0.1%" — qty at unit buy price, then signed return. Falls
	 * back to bare qty when the buy side is unknown (shouldn't happen for real
	 * flips, but a zero denominator must not render garbage). */
	private static String flipDetail(FlipLogEngine.Flip f)
	{
		if (f.qty <= 0 || f.buyGross <= 0)
		{
			return Fmt.full(Math.max(0, f.qty)) + " ×";
		}
		return Fmt.full(f.qty) + " @ " + Fmt.compact(f.buyGross / f.qty)
			+ " · " + pctSigned(100.0 * f.profit / f.buyGross);
	}

	/** Signed percent, one decimal — two when that would show a flat 0.0 for a
	 * real gain or loss (big-ticket flips live in the hundredths). */
	private static String pctSigned(double raw)
	{
		final double r1 = Math.round(raw * 10) / 10.0;
		final boolean fine = r1 == 0 && raw != 0;
		final double shown = fine ? Math.round(raw * 100) / 100.0 : r1;
		return (shown > 0 ? "+" : "")
			+ String.format(java.util.Locale.ROOT, fine ? "%.2f" : "%.1f", shown) + "%";
	}

	private JPanel logFlipRow(FlipLogEngine.Flip f)
	{
		final JPanel rowP = new JPanel(new BorderLayout(6, 0));
		rowP.setBackground(CARD);
		rowP.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		rowP.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 30));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null) { itemManager.getImage(f.itemId).addTo(icon); }
		final JLabel name = new JLabel("<html><body style='width:110px'><b>"
			+ escHtml(f.name != null ? f.name : ("#" + f.itemId)) + "</b>"
			+ (f.check ? " <span style='color:#e6c667'>check</span>" : "") + "</body></html>");
		name.setForeground(Color.WHITE);
		final JLabel profit = mono(gpSign(f.profit), f.profit >= 0 ? Palette.GREEN : Palette.RED);
		profit.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line1 = row();
		line1.add(name, BorderLayout.CENTER);
		line1.add(profit, BorderLayout.EAST);
		final JLabel det = mono(flipDetail(f), Palette.SUBTLE);
		final JLabel when = mono("in " + dur(Math.max(0, f.closedAt - f.openedAt)), Palette.SUBTLE);
		when.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line2 = row();
		line2.add(det, BorderLayout.CENTER);
		line2.add(when, BorderLayout.EAST);
		final JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.add(line1, BorderLayout.NORTH);
		center.add(line2, BorderLayout.SOUTH);
		rowP.add(icon, BorderLayout.WEST);
		rowP.add(center, BorderLayout.CENTER);
		rowP.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowP.getPreferredSize().height));
		attachDeleteMenu(rowP, "Delete flip…",
			"Delete this " + (f.name != null ? f.name : ("#" + f.itemId)) + " flip (" + gpSign(f.profit) + ")?\n"
				+ "It comes out of your log and totals everywhere. Open positions are not restored.",
			() -> listener.onDeleteFlip(f.id));
		return rowP;
	}

	// ── Plan tab: split your capital across your GE slots ──
	private JPanel buildPlanView()
	{
		final JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		// Capital. Left empty it uses your last reported bank total; the bank
		// tracker fills it in live once your bank has been opened.
		final JPanel capRow = row();
		capRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		final JLabel capLbl = new JLabel("Capital");
		capLbl.setForeground(Palette.SUBTLE);
		planCapital.setFont(FontManager.getRunescapeSmallFont());
		planCapital.setToolTipText("Your roll, like 25m or 1.2b. Filled from your bank when detected.");
		planCapital.setPreferredSize(new Dimension(90, 24));
		planCapital.setMaximumSize(new Dimension(90, 24));
		planCapital.getDocument().addDocumentListener(new DocumentListener()
		{
			private void edited() { if (!planCapitalMuted) { planCapitalEdited = true; } }
			public void insertUpdate(DocumentEvent e) { edited(); }
			public void removeUpdate(DocumentEvent e) { edited(); }
			public void changedUpdate(DocumentEvent e) { edited(); }
		});
		final JPanel capWrap = new JPanel(new BorderLayout());
		capWrap.setOpaque(false);
		capWrap.add(planCapital, BorderLayout.EAST);
		capRow.add(capLbl, BorderLayout.WEST);
		capRow.add(capWrap, BorderLayout.EAST);
		controls.add(capRow);
		controls.add(gap(6));

		// Slots per account + accounts. Buy limits are per account, so accounts
		// multiply how much of each item the plan can move.
		final JPanel slotRow = row();
		slotRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel slotLbl = new JLabel("Slots / account");
		slotLbl.setForeground(Palette.SUBTLE);
		planSlots.setPreferredSize(new Dimension(56, 24));
		planSlots.setMaximumSize(new Dimension(56, 24));
		final JPanel slotWrap = new JPanel(new BorderLayout());
		slotWrap.setOpaque(false);
		slotWrap.add(planSlots, BorderLayout.EAST);
		slotRow.add(slotLbl, BorderLayout.WEST);
		slotRow.add(slotWrap, BorderLayout.EAST);
		controls.add(slotRow);
		controls.add(gap(6));

		final JPanel acctRow = row();
		acctRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel acctLbl = new JLabel("Accounts");
		acctLbl.setForeground(Palette.SUBTLE);
		planAccts.setToolTipText("Buy limits are per account, so more accounts move more of each item. Volume caps stay shared.");
		planAccts.setPreferredSize(new Dimension(56, 24));
		planAccts.setMaximumSize(new Dimension(56, 24));
		final JPanel acctWrap = new JPanel(new BorderLayout());
		acctWrap.setOpaque(false);
		acctWrap.add(planAccts, BorderLayout.EAST);
		acctRow.add(acctLbl, BorderLayout.WEST);
		acctRow.add(acctWrap, BorderLayout.EAST);
		controls.add(acctRow);
		controls.add(gap(6));

		final JPanel hoursRow = row();
		hoursRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel hoursLbl = new JLabel("Session");
		hoursLbl.setForeground(Palette.SUBTLE);
		planHours.setToolTipText("How long you are flipping for. Short sessions favour fast movers; overnight admits patient big tickets and fits more buy-limit windows.");
		planHours.setPreferredSize(new Dimension(96, 24));
		planHours.setMaximumSize(new Dimension(96, 24));
		planHours.setSelectedIndex(1);
		final JPanel hoursWrap = new JPanel(new BorderLayout());
		hoursWrap.setOpaque(false);
		hoursWrap.add(planHours, BorderLayout.EAST);
		hoursRow.add(hoursLbl, BorderLayout.WEST);
		hoursRow.add(hoursWrap, BorderLayout.EAST);
		controls.add(hoursRow);
		controls.add(gap(8));

		// Restore the last-used shape. Best-effort: any failure keeps defaults.
		try
		{
			final String s = configManager.getConfiguration(PriceCheckConfig.GROUP, "planSlots");
			if (s != null) { planSlots.setValue(Math.min(Math.max(Integer.parseInt(s), 1), 8)); }
			final String a = configManager.getConfiguration(PriceCheckConfig.GROUP, "planAccounts");
			if (a != null) { planAccts.setValue(Math.min(Math.max(Integer.parseInt(a), 1), 100)); }
			final String h = configManager.getConfiguration(PriceCheckConfig.GROUP, "planHours");
			if (h != null)
			{
				final int hv = Integer.parseInt(h);
				for (int i = 0; i < PLAN_HOURS_VALUES.length; i++)
				{
					if (PLAN_HOURS_VALUES[i] == hv) { planHours.setSelectedIndex(i); }
				}
			}
		}
		catch (Exception ignored)
		{
		}

		planBuild.setFocusPainted(false);
		planBuild.setAlignmentX(Component.LEFT_ALIGNMENT);
		planBuild.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		planBuild.addActionListener(e -> firePlan());
		planCapital.addActionListener(e -> firePlan());
		controls.add(planBuild);
		controls.add(gap(6));

		planStatus.setForeground(Palette.SUBTLE);
		planStatus.setFont(planStatus.getFont().deriveFont(11f));
		planStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(planStatus);

		planList.setLayout(new BoxLayout(planList, BoxLayout.Y_AXIS));

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		planList.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(controls);
		body.add(planList);

		final ScrollList wrap = new ScrollList();
		wrap.add(body, BorderLayout.NORTH);
		final JScrollPane scroll = new JScrollPane(wrap,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		final JPanel holder = new JPanel(new BorderLayout());
		holder.add(scroll, BorderLayout.CENTER);
		return holder;
	}

	private void firePlan()
	{
		final String txt = planCapital.getText().trim();
		long capital = -1;
		if (!planCapitalEdited && detectedCapital >= 100_000)
		{
			// Untouched field = use the EXACT detected total, not the display
			// string (formatting rounds up to 0.5%, which would plan gp the
			// player doesn't have).
			capital = detectedCapital;
		}
		else if (!txt.isEmpty())
		{
			capital = Fmt.parseGp(txt);
			if (capital < 100_000)
			{
				planStatus.setText("Enter at least 100k. 25m and 1.2b formats work.");
				planStatus.setForeground(Palette.AMBER);
				return;
			}
		}
		final int slots = (Integer) planSlots.getValue();
		final int accounts = (Integer) planAccts.getValue();
		final int hours = PLAN_HOURS_VALUES[Math.max(0, planHours.getSelectedIndex())];
		configManager.setConfiguration(PriceCheckConfig.GROUP, "planSlots", String.valueOf(slots));
		configManager.setConfiguration(PriceCheckConfig.GROUP, "planAccounts", String.valueOf(accounts));
		configManager.setConfiguration(PriceCheckConfig.GROUP, "planHours", String.valueOf(hours));
		planBuild.setEnabled(false);
		planBuild.setText("Building…");
		planStatus.setText(" ");
		planStatus.setForeground(Palette.SUBTLE);
		listener.onBuildPlan(capital, slots, accounts, hours);
	}

	/** Live bank+inventory total from the capital tracker. Prefills the capital
	 *  field until the user types their own number. */
	void setDetectedCapital(long total)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (total >= 100_000)
			{
				detectedCapital = total;
			}
			if (planCapitalEdited || total < 100_000)
			{
				return;
			}
			planCapitalMuted = true;
			planCapital.setText(Fmt.full(total));   // exact; parseGp strips the commas
			planCapital.setToolTipText("From your bank + inventory: " + Fmt.full(total) + " gp. Type to override.");
			planCapitalMuted = false;
		});
	}

	void setPlan(PriceCheckApiClient.PlanResult result)
	{
		SwingUtilities.invokeLater(() ->
		{
			planBuild.setEnabled(true);
			planBuild.setText("Build plan");
			planList.removeAll();

			if (result == null || result.state == PriceCheckApiClient.AuthState.ERROR)
			{
				planStatus.setText("Couldn't reach PriceCheck. Try again.");
				planStatus.setForeground(Palette.AMBER);
			}
			else if (result.state == PriceCheckApiClient.AuthState.NO_KEY)
			{
				planStatus.setText("Add your plugin key in Settings first.");
				planStatus.setForeground(Palette.AMBER);
			}
			else if (result.state == PriceCheckApiClient.AuthState.INVALID_KEY)
			{
				planStatus.setText("Key rejected. Check it in Settings.");
				planStatus.setForeground(Palette.RED);
			}
			else if (result.state == PriceCheckApiClient.AuthState.NO_SUBSCRIPTION)
			{
				planStatus.setText("Subscription inactive.");
				planStatus.setForeground(Palette.RED);
			}
			else if (result.state == PriceCheckApiClient.AuthState.PLAN_REQUIRED)
			{
				planStatus.setText("The plugin comes with Trader Pro.");
				planStatus.setForeground(Palette.AMBER);
			}
			else if (result.needCapital)
			{
				planStatus.setText("Enter your capital, or open your bank once in game.");
				planStatus.setForeground(Palette.AMBER);
			}
			else if (result.plan == null || result.plan.getPlan() == null || result.plan.getPlan().isEmpty())
			{
				planStatus.setText("Nothing to allocate right now. Try again shortly.");
				planStatus.setForeground(Palette.SUBTLE);
			}
			else
			{
				final PlanData d = result.plan;
				planStatus.setText(" ");
				planList.add(planTotals(d));
				planList.add(gap(6));
				for (PlanData.Row r : d.getPlan())
				{
					planList.add(planRow(r));
					planList.add(gap(5));
				}
			}
			planList.revalidate();
			planList.repaint();
		});
	}

	private JPanel planTotals(PlanData d)
	{
		final JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(CARD);
		card.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JPanel l1 = row();
		final boolean hasRange = d.getTotals().getEstSession() != 0 || d.getTotals().getHigh() != 0;
		final JLabel exp = mono(hasRange
			? Fmt.compact(d.getTotals().getEstSession()) + " expected this session"
			: Fmt.compact(d.getTotals().getEstPerHr()) + "/hr expected", Palette.GOLD);
		l1.add(exp, BorderLayout.WEST);
		card.add(l1);

		if (hasRange)
		{
			final JPanel lr = row();
			final JLabel range = mono(Fmt.compact(d.getTotals().getLow()) + " to " + Fmt.compact(d.getTotals().getHigh())
				+ (d.getTotals().getWorstCase() > 0 ? " · worst case -" + Fmt.compact(d.getTotals().getWorstCase()) : ""), Palette.SUBTLE);
			range.setToolTipText("Range from the measured odds: a slow tape lands near the low, a clean sweep near the high. Worst case assumes every big ticket bails and every flow item eats its daily wobble.");
			lr.add(range, BorderLayout.WEST);
			card.add(lr);
		}

		final JPanel l2 = row();
		final JLabel dep = mono("deployed " + Fmt.compact(d.getTotals().getOutlay())
			+ " · left " + Fmt.compact(d.getTotals().getLeftover()), Palette.SUBTLE);
		l2.add(dep, BorderLayout.WEST);
		card.add(l2);

		if (d.getAccounts() > 1)
		{
			final JPanel l3 = row();
			final JLabel slots = mono("slots " + d.getUsedSlots() + "/" + d.getTotalSlots()
				+ " across " + d.getAccounts() + " accounts", Palette.SUBTLE);
			l3.add(slots, BorderLayout.WEST);
			card.add(l3);
		}
		if ("detected".equals(d.getCapitalSource()))
		{
			final JPanel l4 = row();
			final JLabel src = mono("capital from your bank: " + Fmt.compact(d.getCapital()), Palette.SUBTLE);
			l4.add(src, BorderLayout.WEST);
			card.add(l4);
		}
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	// One plan allocation: icon | name (wraps) / qty @ price / outlay | est per hr.
	private JPanel planRow(PlanData.Row r)
	{
		final JPanel rowP = new JPanel(new BorderLayout(6, 0));
		rowP.setBackground(CARD);
		rowP.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		rowP.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		itemManager.getImage(r.getGeId()).addTo(icon);

		String tag = "";
		if ("big".equals(r.getLane()))
		{
			tag = r.isBand() ? " <span style='color:#e6c667'>BIG · BAND</span>" : " <span style='color:#e6c667'>BIG</span>";
		}
		else if ("dip".equals(r.getLane()))
		{
			tag = " <span style='color:#e6b34a'>DIP</span>";
		}
		final JLabel name = new JLabel("<html><body style='width:126px'><b>" + escHtml(r.getName()) + "</b>" + tag + "</body></html>");
		name.setForeground(Color.WHITE);
		final JPanel line1 = row();
		line1.add(name, BorderLayout.CENTER);

		String qtyTxt = Fmt.full(r.getQty()) + " @ " + Fmt.compact(r.getBuy());
		if (r.getAcctsUsed() > 1)
		{
			qtyTxt += " · " + Fmt.full(r.getPerAcct()) + " × " + r.getAcctsUsed() + " accts";
		}
		final JLabel qty = mono(qtyTxt, Palette.SUBTLE);
		final JPanel line2 = row();
		line2.add(qty, BorderLayout.CENTER);

		final JLabel outlay = mono(Fmt.compact(r.getOutlay()) + " in", Palette.SUBTLE);
		final JLabel ev = mono(r.getEstSession() != 0
			? "+" + Fmt.compact(r.getEstSession()) : Fmt.compact(r.getEstPerHr()) + "/hr", Palette.GOLD);
		ev.setToolTipText(r.getEstSession() != 0 ? "Expected over your session (" + Fmt.compact(r.getEstPerHr()) + "/hr)" : null);
		ev.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line3 = row();
		line3.add(outlay, BorderLayout.CENTER);
		line3.add(ev, BorderLayout.EAST);

		// Receipt: the evidence behind the line. Old servers send no lane, so
		// this row simply doesn't appear.
		JPanel receiptLine = null;
		if (r.getLane() != null)
		{
			String receipt;
			if ("big".equals(r.getLane()))
			{
				receipt = Math.round(r.getOdds() * 100) + "% fill · ~"
					+ String.format(java.util.Locale.ROOT, "%.1f", r.getHoldHrs()) + "h hold"
					+ (r.isBand() ? " · rest these exact prices" : "");
			}
			else if ("dip".equals(r.getLane()))
			{
				receipt = "target " + Fmt.compact(r.getTarget()) + " · half odds, opportunistic";
			}
			else
			{
				receipt = Math.round(r.getOdds() * 100) + "% fill · ~" + r.getTtfMin() + "m to first fill";
			}
			final JLabel rec = mono(receipt, Palette.SUBTLE);
			receiptLine = row();
			receiptLine.add(rec, BorderLayout.CENTER);
		}

		final JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.add(line1, BorderLayout.NORTH);
		final JPanel lower = new JPanel(new BorderLayout());
		lower.setOpaque(false);
		lower.add(line2, BorderLayout.NORTH);
		if (receiptLine != null)
		{
			lower.add(receiptLine, BorderLayout.CENTER);
		}
		lower.add(line3, BorderLayout.SOUTH);
		center.add(lower, BorderLayout.SOUTH);

		rowP.add(icon, BorderLayout.WEST);
		rowP.add(center, BorderLayout.CENTER);
		rowP.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowP.getPreferredSize().height));
		return rowP;
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
		keyDot.setColor(Palette.SUBTLE);
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
		// Flat, panel-native button: same surface as the key field, hairline
		// frame, gold on hover once a plausible key is in the field.
		saveKeyBtn.setContentAreaFilled(false);
		saveKeyBtn.setOpaque(true);
		saveKeyBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		saveKeyBtn.setForeground(Palette.LIGHT);
		saveKeyBtn.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(HAIRLINE),
			BorderFactory.createEmptyBorder(4, 0, 4, 0)));
		saveKeyBtn.addMouseListener(new MouseAdapter()
		{
			public void mouseEntered(MouseEvent e)
			{
				if (saveKeyBtn.isEnabled())
				{
					saveKeyBtn.setForeground(Palette.GOLD);
					saveKeyBtn.setBorder(BorderFactory.createCompoundBorder(
						BorderFactory.createLineBorder(new Color(0xe6, 0xc6, 0x67, 110)),
						BorderFactory.createEmptyBorder(4, 0, 4, 0)));
				}
			}

			public void mouseExited(MouseEvent e)
			{
				saveKeyBtn.setForeground(Palette.LIGHT);
				saveKeyBtn.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(HAIRLINE),
					BorderFactory.createEmptyBorder(4, 0, 4, 0)));
			}
		});
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
			keyDot.setColor(Palette.AMBER);
			keyDot.setToolTipText("Checking…");
			listener.onFetchAccount();
		});
		keyField.setAlignmentX(Component.LEFT_ALIGNMENT);
		v.add(keyField);
		v.add(gap(6));
		v.add(saveKeyBtn);
		v.add(gap(4));

		final JLabel link = new JLabel("Get a key · flipping.pricecheck.gg");
		link.setForeground(Palette.SUBTLE);
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setAlignmentX(Component.LEFT_ALIGNMENT);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e) { LinkBrowser.browse("https://flipping.pricecheck.gg"); }
			public void mouseEntered(MouseEvent e) { link.setForeground(Palette.GOLD); }
			public void mouseExited(MouseEvent e) { link.setForeground(Palette.SUBTLE); }
		});
		v.add(link);
		v.add(gap(12));

		// Options section
		v.add(sectionHeader("Options"));
		// The sync toggle lives HERE, where the Log tab's "Local only" hint sends
		// people, not just in the RuneLite config panel. Enabling shows the same
		// data-submission disclosure the config panel warns with; declining
		// reverts the box.
		syncToggle.setOpaque(false);
		syncToggle.setForeground(Color.WHITE);
		syncToggle.setFocusPainted(false);
		styleCheckbox(syncToggle);
		syncToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		syncToggle.setToolTipText("Back up your flip log to your PriceCheck account and see it at flipping.pricecheck.gg/portfolio");
		syncToggle.setSelected(config.syncFlipLog());
		syncToggle.addItemListener(e ->
		{
			if (settingsMuted)
			{
				return;
			}
			// Capture the intent from the EVENT, not from isSelected() later: a
			// modal dialog opened mid-click can see the checkbox model revert
			// underneath it (Swing re-entrancy), which would make the OK write
			// the wrong value. The dialog is also deferred out of the click
			// transaction for the same reason.
			final boolean want = e.getStateChange() == java.awt.event.ItemEvent.SELECTED;
			if (!want)
			{
				configManager.setConfiguration(PriceCheckConfig.GROUP, "syncFlipLog", false);
				return;
			}
			SwingUtilities.invokeLater(() ->
			{
				final int ok = javax.swing.JOptionPane.showConfirmDialog(syncToggle,
					"<html><body style='width:300px'>Enabling this submits your Grand Exchange trades (item, price, quantity, "
						+ "tax, profit, timestamps), open positions, offer-slot snapshots, an anonymous per-account identifier "
						+ "(never your RSN), and your IP address to PriceCheck's servers, which are not controlled or verified "
						+ "by the RuneLite Developers.</body></html>",
					"Sync flip log", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
				settingsMuted = true;
				syncToggle.setSelected(ok == javax.swing.JOptionPane.OK_OPTION);
				settingsMuted = false;
				if (ok == javax.swing.JOptionPane.OK_OPTION)
				{
					configManager.setConfiguration(PriceCheckConfig.GROUP, "syncFlipLog", true);
				}
			});
		});
		v.add(syncToggle);
		v.add(gap(6));
		advisorToggle.setOpaque(false);
		advisorToggle.setForeground(Color.WHITE);
		advisorToggle.setFocusPainted(false);
		styleCheckbox(advisorToggle);
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
		final javax.swing.JComponent spinEd = minEvSpinner.getEditor();
		if (spinEd instanceof JSpinner.DefaultEditor)
		{
			final javax.swing.JTextField tf = ((JSpinner.DefaultEditor) spinEd).getTextField();
			tf.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			tf.setForeground(Palette.LIGHT);
			tf.setCaretColor(Color.WHITE);
			tf.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		}
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
		v.add(gap(8));

		// The panel shows the everyday options; the full set (overlays, GE
		// helpers, data sharing) lives in RuneLite's own config panel, which
		// users rarely think to open. This jumps straight to it.
		final JLabel allOpts = new JLabel("<html><span style='color:#9a917c'>All options · </span><span style='color:#e6c667'>RuneLite plugin settings</span></html>");
		allOpts.setFont(FontManager.getRunescapeSmallFont());
		allOpts.setAlignmentX(Component.LEFT_ALIGNMENT);
		allOpts.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		allOpts.setToolTipText("Every PriceCheck toggle, including overlay and GE options");
		allOpts.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e) { listener.onOpenPluginConfig(); }
		});
		v.add(allOpts);

		v.add(gap(10));
		v.add(discordCard());

		final JLabel footer = new JLabel("PriceCheck v" + PriceCheckPlugin.VERSION);
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
			final String plan = acct.getPlan() == null ? "" : acct.getPlan();
			final String pill = !acct.isPremium() ? "FREE"
				: ("pro".equals(plan) || "premium".equals(plan)) ? "TRADER PRO" : "TRADER";
			acctPlan.setText(" " + pill + " ");
			// License line: what the plan is doing and when it runs out. "Renews"
			// for a live subscription, "left" for comped/prepaid time, no date talk
			// for a lifetime grant. The watchlist count rides along at the end.
			String lic;
			if (!acct.isPremium())
			{
				lic = "Free plan";
			}
			else if (acct.getExpiresAt() == null || acct.getExpiresAt() <= 0)
			{
				lic = "Lifetime license";
			}
			else
			{
				final long days = Math.max(0, (acct.getExpiresAt() - System.currentTimeMillis() + 86_399_999L) / 86_400_000L);
				final boolean renews = "stripe".equals(acct.getSource());
				lic = days <= 0 ? "License expired"
					: (renews ? "Renews in " : "") + days + (renews ? (days == 1 ? " day" : " days") : (days == 1 ? " day left" : " days left"));
			}
			final int n = acct.getTrackedCount();
			acctSub.setText(lic + " · watching " + n + (n == 1 ? " item" : " items"));
			acctSub.setToolTipText("Watching = your tracked-margins watchlist (the + button on flip rows); live GE offers show on the Flips tab and the in-game overlays.");
			if (acct.getKeyPrefix() != null) { keyPrefixLabel.setText(acct.getKeyPrefix()); }
		});
	}

	// keep the config-dialog and the Settings tab consistent without write loops
	void syncSettings()
	{
		SwingUtilities.invokeLater(() ->
		{
			settingsMuted = true;
			if (syncToggle.isSelected() != config.syncFlipLog()) { syncToggle.setSelected(config.syncFlipLog()); }
			if (advisorToggle.isSelected() != config.showAdvisor()) { advisorToggle.setSelected(config.showAdvisor()); }
			if (!minEvSpinner.getValue().equals(config.minEvPerHrK())) { minEvSpinner.setValue(config.minEvPerHrK()); }
			settingsMuted = false;
		});
	}

	private void setDot(Color c, String tip)
	{
		keyDot.setColor(c);
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
			list.add(sectionHeader("Tracking · " + lastTracked.size()));
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
			: (minEvPerHrK > 0 ? shown.size() + " flips · " + minEvPerHrK + "k+/hr" : shown.size() + " flips · by EV/hr");
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
			for (FlipData f : shown)
			{
				list.add(flipRow(f, trackedIds.contains(f.getGeId())));
				if (f.getGeId() == expandedChartId)
				{
					list.add(gap(2));
					list.add(chartCard(f));
				}
				list.add(gap(5));
			}
		}

		list.add(Box.createVerticalGlue());
		list.revalidate();
		list.repaint();
	}

	/** The expanded evidence chart under a clicked board row. */
	private JPanel chartCard(FlipData f)
	{
		final JPanel card = new JPanel(new BorderLayout());
		card.setBackground(CARD);
		card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		final PriceCheckApiClient.SeriesData sd = seriesSupplier != null ? seriesSupplier.apply(f.getGeId()) : null;
		if (sd == null || sd.ts == null || sd.ts.length < 2)
		{
			final JLabel wait = new JLabel("Loading day chart…");
			wait.setFont(FontManager.getRunescapeSmallFont());
			wait.setForeground(Palette.SUBTLE);
			card.add(wait, BorderLayout.CENTER);
			card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
			// The supplier schedules the fetch; repaint shortly to pick it up.
			final javax.swing.Timer t = new javax.swing.Timer(1200, e -> render());
			t.setRepeats(false);
			t.start();
			return card;
		}
		final ItemChart.Series s = new ItemChart.Series();
		s.ts = sd.ts;
		s.high = sd.ah;
		s.low = sd.al;
		s.hvol = sd.hv;
		s.lvol = sd.lv;
		s.quoteBuy = f.getBuy();
		s.quoteSell = f.getSell();
		s.fillPct = sd.fillPct;
		// Your own logged fills on this item, exact prices from the flip log.
		final FlipLogEngine.Summary log = lastLogSummary;
		if (log != null && log.recent != null)
		{
			final java.util.List<long[]> marks = new ArrayList<>();
			for (final FlipLogEngine.Flip fl : log.recent)
			{
				if (fl.itemId != f.getGeId() || fl.qty <= 0)
				{
					continue;
				}
				marks.add(new long[]{fl.openedAt / 1000L, fl.buyGross / fl.qty, 1});
				marks.add(new long[]{fl.closedAt / 1000L, fl.sellGross / fl.qty, 0});
			}
			if (!marks.isEmpty())
			{
				s.markTs = new long[marks.size()];
				s.markPrice = new long[marks.size()];
				s.markBuy = new boolean[marks.size()];
				for (int i = 0; i < marks.size(); i++)
				{
					s.markTs[i] = marks.get(i)[0];
					s.markPrice[i] = marks.get(i)[1];
					s.markBuy[i] = marks.get(i)[2] == 1;
				}
			}
		}
		final ItemChart chart = new ItemChart(s, 206, 150);
		card.add(chart, BorderLayout.CENTER);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 168));
		return card;
	}

	// ── flip row: icon | name (wraps) / buy>sell+profit / EV | track toggle ──
	// Package-visible for the headless preview harness.
	JPanel flipRow(FlipData f, boolean tracked)
	{
		final JPanel rowP = new JPanel(new BorderLayout(6, 0));
		rowP.setBackground(CARD);
		rowP.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		rowP.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null) { itemManager.getImage(f.getGeId()).addTo(icon); }

		// The name gets the whole first line and wraps instead of truncating —
		// the fixed body width makes the HTML label report a real wrapped
		// preferred height, which plain labels in a BorderLayout won't.
		final JLabel name = new JLabel("<html><body style='width:126px'><b>" + escHtml(f.getName()) + "</b></body></html>");
		name.setForeground(Color.WHITE);
		final JPanel line1 = row();
		line1.add(name, BorderLayout.CENTER);

		// Buy-to-sell with a painted chevron: the RuneScape font has no arrow
		// glyph, so typing one renders a garbage box.
		final JPanel bs = new JPanel();
		bs.setLayout(new BoxLayout(bs, BoxLayout.X_AXIS));
		bs.setOpaque(false);
		bs.add(mono(Fmt.compact(f.getBuy()), Palette.LIGHT));
		bs.add(Box.createRigidArea(new Dimension(5, 0)));
		bs.add(new Chevron(Palette.SUBTLE));
		bs.add(Box.createRigidArea(new Dimension(5, 0)));
		bs.add(mono(Fmt.compact(f.getSell()), Palette.LIGHT));
		final JLabel profit = mono("+" + Fmt.compact(f.getProfit()), f.getProfit() >= 0 ? Palette.GREEN : Palette.RED);
		profit.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line2 = row();
		line2.add(bs, BorderLayout.CENTER);
		line2.add(profit, BorderLayout.EAST);

		final JLabel ev = mono(Fmt.compact(f.getEvPerHr()) + "/hr", Palette.GOLD);
		ev.setHorizontalAlignment(SwingConstants.RIGHT);
		final JPanel line3 = row();
		line3.add(ev, BorderLayout.EAST);

		if (f.riskLabel() != null)
		{
			// Risk-tier row: volume-confirmed margin that missed one quality bar.
			// The amber mark carries WHY so the row explains itself on hover.
			final JLabel dot = new JLabel("! ");
			dot.setForeground(Palette.AMBER);
			dot.setFont(dot.getFont().deriveFont(Font.BOLD));
			dot.setToolTipText("Higher risk: " + f.riskLabel() + ". Margin is volume-confirmed but this missed one board quality bar.");
			line1.add(dot, BorderLayout.WEST);
			final JLabel why = new JLabel(f.riskLabel());
			why.setForeground(Palette.AMBER);
			why.setFont(FontManager.getRunescapeSmallFont());
			line3.add(why, BorderLayout.WEST);
		}
		else if (f.isConfirmed())
		{
			final Mark ok = new Mark(Mark.CHECK, Palette.GREEN, 13, 16);
			ok.setToolTipText("Margin confirmed across the 5m + 1h books");
			line1.add(ok, BorderLayout.WEST);
		}

		final JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.add(line1, BorderLayout.NORTH);
		final JPanel lower = new JPanel(new BorderLayout());
		lower.setOpaque(false);
		lower.add(line2, BorderLayout.NORTH);
		lower.add(line3, BorderLayout.SOUTH);
		center.add(lower, BorderLayout.SOUTH);

		final Mark trk = new Mark(tracked ? Mark.CHECK : Mark.PLUS, tracked ? Palette.GREEN : Palette.SUBTLE, 18, 34);
		trk.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		trk.setToolTipText(tracked ? "Tracking, click to stop" : "Track this position");
		final boolean[] fired = { false };
		trk.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e)
			{
				if (fired[0]) { return; }   // guard against double-fire before the next poll
				fired[0] = true;
				trk.set(tracked ? Mark.PLUS : Mark.CHECK, tracked ? Palette.SUBTLE : Palette.GREEN);
				if (tracked) { listener.onUntrack(f.getGeId()); } else { listener.onTrack(f); }
			}
		});
		rowP.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		rowP.addMouseListener(new MouseAdapter()
		{
			public void mouseEntered(MouseEvent e) { rowP.setBackground(CARD_HOVER); }
			public void mouseExited(MouseEvent e) { rowP.setBackground(CARD); }

			public void mousePressed(MouseEvent e)
			{
				// The row toggles its evidence chart; the track mark on the
				// right has its own listener and consumes its own clicks.
				expandedChartId = expandedChartId == f.getGeId() ? 0 : f.getGeId();
				render();
			}
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
	// Package-visible for the headless preview harness.
	JPanel trackingCard(TrackedItem t)
	{
		final JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(CARD);
		card.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));

		final JPanel head = row();
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(24, 20));
		if (itemManager != null) { itemManager.getImage(t.getGeId()).addTo(icon); }
		final JLabel name = new JLabel(t.getName());
		name.setFont(name.getFont().deriveFont(Font.BOLD));
		name.setForeground(Color.WHITE);
		final Mark rm = new Mark(Mark.CROSS, Palette.SUBTLE, 18, 20);
		rm.setToolTipText("Stop tracking");
		rm.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		rm.addMouseListener(new MouseAdapter()
		{
			public void mousePressed(MouseEvent e) { listener.onUntrack(t.getGeId()); }
			public void mouseEntered(MouseEvent e) { rm.set(Mark.CROSS, Palette.RED); }
			public void mouseExited(MouseEvent e) { rm.set(Mark.CROSS, Palette.SUBTLE); }
		});
		final JPanel nameWrap = new JPanel(new BorderLayout(6, 0));
		nameWrap.setOpaque(false);
		nameWrap.add(icon, BorderLayout.WEST);
		nameWrap.add(name, BorderLayout.CENTER);
		head.add(nameWrap, BorderLayout.CENTER);
		head.add(rm, BorderLayout.EAST);
		card.add(head);

		card.add(kv("Bought", Fmt.full(t.getEntryBuy())));
		card.add(kv("Sell now", t.getSellNow() != null ? Fmt.full(t.getSellNow()) : "-"));
		card.add(Box.createVerticalStrut(5));
		card.add(hairline());
		card.add(Box.createVerticalStrut(2));

		final boolean nodata = "nodata".equals(t.getStatus());
		final boolean thin = "thin".equals(t.getStatus());
		final Color pnlCol = t.getPnl() > 0 ? Palette.GREEN : (t.getPnl() < 0 ? Palette.RED : Palette.SUBTLE);
		final JLabel pnl = new JLabel((t.getPnl() > 0 ? "+" : "") + Fmt.compact(t.getPnl())
			+ " · " + pctSigned(t.getRoi()) + " if sold now");
		pnl.setForeground(pnlCol);
		pnl.setFont(pnl.getFont().deriveFont(Font.BOLD, 13f));
		pnl.setAlignmentX(Component.LEFT_ALIGNMENT);
		pnl.setBorder(BorderFactory.createEmptyBorder(3, 0, 2, 0));
		pnl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		card.add(pnl);

		// Verdict cluster: the pill and its plain-language reading sit together.
		final JPanel st = row();
		st.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		final JPanel verdict = new JPanel();
		verdict.setLayout(new BoxLayout(verdict, BoxLayout.X_AXIS));
		verdict.setOpaque(false);
		verdict.add(pill(nodata ? "no data" : (thin ? "thin" : "healthy"),
			nodata ? Palette.SUBTLE : (thin ? Palette.RED : Palette.GREEN)));
		verdict.add(Box.createRigidArea(new Dimension(7, 0)));
		final JLabel hint = new JLabel(nodata ? "not trading right now" : (thin ? "margin closing" : "good to hold"));
		hint.setForeground(Palette.LIGHT);
		hint.setFont(FontManager.getRunescapeSmallFont());
		verdict.add(hint);
		st.add(verdict, BorderLayout.WEST);
		card.add(st);

		final JLabel floor = new JLabel("Floor " + Fmt.full(t.getFloor()) + " · don't sell below");
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
			case INVALID_KEY: title = "Key not recognised"; body = "Regenerate it at flipping.pricecheck.gg, then paste it in Settings."; break;
			case NO_SUBSCRIPTION: title = "Subscription inactive"; body = "Renew at flipping.pricecheck.gg to see live flips."; break;
			case PLAN_REQUIRED: title = "Trader Pro feature"; body = "The RuneLite plugin comes with Trader Pro. Upgrade at flipping.pricecheck.gg."; break;
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

	private JComponent sectionHeader(String text)
	{
		final JLabel h = new JLabel(text.toUpperCase(Locale.ROOT));
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(Palette.GOLD);
		h.setBorder(BorderFactory.createEmptyBorder(2, 1, 6, 8));
		// A hairline rule fills the rest of the header line; raised a little
		// so it tracks the text's optical middle, not the padded label's.
		final JPanel rule = new JPanel();
		rule.setBackground(HAIRLINE);
		rule.setPreferredSize(new Dimension(1, 1));
		final JPanel ruleWrap = new JPanel(new GridBagLayout());
		ruleWrap.setOpaque(false);
		final GridBagConstraints rc = new GridBagConstraints();
		rc.weightx = 1;
		rc.fill = GridBagConstraints.HORIZONTAL;
		rc.insets = new Insets(0, 0, 4, 0);
		ruleWrap.add(rule, rc);
		final JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(h, BorderLayout.WEST);
		p.add(ruleWrap, BorderLayout.CENTER);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		return p;
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
		// HTML with a fixed body width wraps; a plain label clips mid-sentence
		// at the panel's 226px.
		final JLabel l = new JLabel("<html><body style='width:176px'>" + escHtml(text) + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(col);
		l.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
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

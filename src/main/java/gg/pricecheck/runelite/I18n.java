package gg.pricecheck.runelite;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin language. English is the source text AND the key, so an untranslated
 * string simply stays English instead of showing a placeholder: nothing can
 * render blank because a phrase was missed.
 *
 * The font is the real constraint. RuneLite's bitmap faces (and the game's own)
 * carry no Japanese glyphs, so drawing Japanese in them paints empty boxes.
 * Three rules keep that from happening:
 *   1. Japanese only switches on when a font that can actually draw it is
 *      installed - otherwise the plugin stays English and says so in the log.
 *   2. fit() hands back a Japanese-capable face ONLY for the strings that need
 *      one, so prices and the tabular mono columns keep the face they were
 *      tuned for.
 *   3. Text drawn INTO the game (the GE price and quantity prefills) is never
 *      translated: those render in the client's own font, which has no
 *      Japanese glyphs at any size.
 */
@Slf4j
final class I18n
{
	/** A glyph any usable Japanese face must carry. */
	private static final char PROBE = '価';   // 価

	private static volatile boolean jp;
	private static volatile String cjk;
	private static volatile boolean probed;
	private static final Map<String, Font> SUBST = new java.util.concurrent.ConcurrentHashMap<>();

	private I18n() { }

	static boolean japanese()
	{
		return jp;
	}

	/** Client thread / EDT. Silently stays English when no Japanese font exists. */
	static void setLanguage(PriceCheckConfig.Language lang)
	{
		if (lang != PriceCheckConfig.Language.JAPANESE)
		{
			jp = false;
			return;
		}
		if (!probed)
		{
			cjk = findJapaneseFamily();
			probed = true;   // set AFTER the scan it guards
			if (cjk == null)
			{
				log.warn("PriceCheck: no font on this machine can draw Japanese, staying in English");
			}
			else
			{
				log.debug("PriceCheck: Japanese font resolved to {}", cjk);
			}
		}
		jp = cjk != null;
	}

	/** The translation, or the English source when there isn't one. */
	static String t(String en)
	{
		if (!jp || en == null)
		{
			return en;
		}
		final String s = JA.get(en);
		return s != null ? s : en;
	}

	/**
	 * Translate a pattern, then fill it. Composed labels ("31 FLIPS · BY EV/HR")
	 * must translate as ONE pattern, never as glued fragments: Japanese puts the
	 * pieces in a different order and needs no plural forms.
	 */
	static String f(String pattern, Object... args)
	{
		try
		{
			return String.format(t(pattern), args);
		}
		catch (Exception e)
		{
			return String.format(pattern, args);   // a bad translation never breaks a label
		}
	}

	/**
	 * A verdict like "OK +26.2k" or "RAISE +12.9k" is a word plus a number. The
	 * word translates; the number is left exactly as the engine produced it.
	 */
	static String verdict(String s)
	{
		if (!jp || s == null || s.isEmpty())
		{
			return s;
		}
		final int sp = s.indexOf(' ');
		if (sp < 0)
		{
			return t(s);
		}
		final String head = t(s.substring(0, sp));
		return head + s.substring(sp);
	}

	/**
	 * The face to draw `text` in: the caller's own font whenever it can render
	 * the string, else the Japanese face at the same size and style. Latin
	 * strings therefore keep the mono/pixel face the layout was built around.
	 */
	static Font fit(Font base, String text)
	{
		if (!jp || cjk == null || base == null || text == null || text.isEmpty())
		{
			return base;
		}
		if (!hasJapanese(text))
		{
			return base;   // pure Latin keeps the mono face the columns were measured in
		}
		return font(base);
	}

	/**
	 * The Japanese stand-in for a font, matched on RENDERED LINE HEIGHT rather
	 * than nominal size. RuneLite's "size 16" pixel faces are 12px tall, so
	 * substituting at the same point size doubles every label and bursts the
	 * layout - this picks the size that occupies the same line instead.
	 */
	static Font font(Font base)
	{
		if (!jp || cjk == null || base == null || cjk.equals(base.getFamily()))
		{
			return base;
		}
		return SUBST.computeIfAbsent(base.getFamily() + "|" + base.getStyle() + "|" + base.getSize(),
			k -> matchHeight(base));
	}

	private static Font matchHeight(Font base)
	{
		final java.awt.Graphics2D g = metrics();
		// +2px: kanji carry far more detail than Latin at the same height, so
		// an exact match reads as squinting-small. Two pixels stays inside the
		// line spacing the panel lays out with.
		final int want = g.getFontMetrics(base).getHeight() + 2;
		for (int size = 6; size <= 40; size++)
		{
			final Font f = new Font(cjk, base.getStyle(), size);
			if (g.getFontMetrics(f).getHeight() >= want)
			{
				return f;
			}
		}
		return new Font(cjk, base.getStyle(), base.getSize());
	}

	private static volatile java.awt.Graphics2D probeG;

	/** Synchronised: reachable from the client thread (overlays) and the EDT
	 *  (panel build) both. Only ever called on a cache miss. */
	private static synchronized java.awt.Graphics2D metrics()
	{
		if (probeG == null)
		{
			probeG = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
				.createGraphics();
		}
		return probeG;
	}

	/**
	 * Swing: give every component in the tree a face that can draw Japanese.
	 * Called after the side panel is built and after each list refresh, so
	 * rows added later are covered too. No-op in English.
	 */
	static void applyFonts(Component root)
	{
		if (!jp || cjk == null || root == null)
		{
			return;
		}
		final Font f = root.getFont();
		if (f != null && !cjk.equals(f.getFamily()))
		{
			// Unconditional: RuneLite's pixel faces have no kana at all, and the
			// logical ones cannot be trusted to draw a mixed row.
			root.setFont(font(f));
		}
		if (root instanceof Container)
		{
			for (final Component kid : ((Container) root).getComponents())
			{
				applyFonts(kid);
			}
		}
	}

	/**
	 * Keep a whole panel in the right face for as long as it lives: every
	 * component added to the tree from now on gets the substitution too. Without
	 * this, a tab built the first time it is opened renders its Japanese as
	 * empty boxes, because the one-shot sweep already ran.
	 */
	static void installFontWatcher(Container root)
	{
		if (!jp || cjk == null || root == null)
		{
			return;
		}
		applyFonts(root);
		attach(root);
	}

	private static final java.awt.event.ContainerListener WATCHER = new java.awt.event.ContainerAdapter()
	{
		@Override
		public void componentAdded(java.awt.event.ContainerEvent e)
		{
			applyFonts(e.getChild());
			attach(e.getChild());
		}
	};

	private static void attach(Component c)
	{
		if (!(c instanceof Container))
		{
			return;
		}
		final Container k = (Container) c;
		k.removeContainerListener(WATCHER);   // never stack duplicates
		k.addContainerListener(WATCHER);
		for (final Component kid : k.getComponents())
		{
			attach(kid);
		}
	}

	/** Kana, CJK ideographs and the fullwidth forms - anything the plugin's own
	 *  faces cannot draw. */
	private static boolean hasJapanese(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			if (s.charAt(i) >= 0x2E80)
			{
				return true;
			}
		}
		return false;
	}

	/** Preferred faces first (Windows, macOS, Linux), then anything that fits. */
	private static String findJapaneseFamily()
	{
		final String[] prefer = {
			"Noto Sans CJK JP", "Noto Sans JP", "Source Han Sans JP",
			"Hiragino Sans", "Hiragino Kaku Gothic ProN", "Osaka",
			"Yu Gothic", "Meiryo", "MS Gothic", "MS PGothic",
			"IPAGothic", "IPAPGothic", "TakaoGothic", "VL Gothic", "Droid Sans Japanese",
		};
		String[] installed;
		try
		{
			installed = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		}
		catch (Exception e)
		{
			return null;
		}
		final Set<String> have = new HashSet<>(Arrays.asList(installed));
		for (final String f : prefer)
		{
			if (have.contains(f) && canShow(f))
			{
				return f;
			}
		}
		// Any installed PHYSICAL face that can draw kana. Logical faces
		// (Monospaced, SansSerif, Dialog) are deliberately excluded: they claim
		// they can draw Japanese, then render a mixed Japanese + Latin string
		// with the Latin run missing entirely.
		for (final String f : installed)
		{
			if (!isLogical(f) && canShow(f))
			{
				return f;
			}
		}
		return null;
	}

	private static boolean isLogical(String family)
	{
		return Font.MONOSPACED.equalsIgnoreCase(family) || Font.SANS_SERIF.equalsIgnoreCase(family)
			|| Font.SERIF.equalsIgnoreCase(family) || Font.DIALOG.equalsIgnoreCase(family)
			|| Font.DIALOG_INPUT.equalsIgnoreCase(family);
	}

	private static boolean canShow(String family)
	{
		try
		{
			return new Font(family, Font.PLAIN, 12).canDisplay(PROBE);
		}
		catch (Exception e)
		{
			return false;
		}
	}

	// Terminal labels stay SHORT: the desk's columns are sized in pixels, and a
	// two or three character Japanese label fits where the English one did.
	private static final Map<String, String> JA = new HashMap<>();

	/** The table itself, for the guard test that checks pattern parity. */
	static Map<String, String> dictionary()
	{
		return java.util.Collections.unmodifiableMap(JA);
	}

	static
	{
		// ── side panel: tabs and chrome ──
		// Tab labels are 2 characters on purpose: four kana truncate to "フリ…"
		// in a ~56px tab, and a clipped label is worse than a terser one.
		JA.put("Flips", "売買");
		JA.put("Catch", "急落");
		JA.put("Log", "記録");
		JA.put("Plan", "プラン");
		JA.put("Setup", "設定");
		JA.put("Session", "セッション");
		JA.put("Capital", "資金");
		JA.put("Accounts", "アカウント数");
		JA.put("Slots / account", "枠/アカウント");
		JA.put("Min EV/hr (k)", "最低EV/時 (k)");
		JA.put("Build plan", "プラン作成");
		JA.put("Building…", "作成中…");
		JA.put("Open Settings", "設定を開く");
		JA.put("Save key", "キーを保存");
		JA.put("Paste a new key (pck_…)", "新しいキーを貼り付け (pck_…)");
		JA.put("Loading account…", "アカウント読込中…");
		JA.put("Loading day chart…", "日次チャート読込中…");
		JA.put("Checking…", "確認中…");
		JA.put("Stop tracking", "追跡を停止");

		// ── side panel: states and help text ──
		JA.put("Add your plugin key in Settings first.", "先に設定でプラグインキーを入力してください。");
		JA.put("Key rejected. Check it in Settings.", "キーが拒否されました。設定を確認してください。");
		JA.put("Couldn't reach PriceCheck. Try again.", "PriceCheckに接続できませんでした。再試行してください。");
		JA.put("Subscription inactive.", "サブスクリプションが無効です。");
		JA.put("The plugin comes with Trader Pro.", "このプラグインはTrader Proに含まれます。");
		JA.put("Free key · Discord login, no RSN", "無料キー · Discordログイン、RSN不要");
		JA.put("Join the PriceCheck Discord", "PriceCheckのDiscordに参加");
		JA.put("Flip chat and price checks", "フリップの雑談と価格チェック");
		JA.put("Giveaways and community help", "配布とコミュニティサポート");
		JA.put("Nothing to allocate right now. Try again shortly.", "今は配分できるものがありません。しばらくしてから再試行してください。");
		JA.put("Enter your capital, or open your bank once in game.", "資金を入力するか、ゲーム内で銀行を一度開いてください。");
		JA.put("Enter at least 100k. 25m and 1.2b formats work.", "最低100k。25mや1.2bの形式も使えます。");
		JA.put("From your bank + inventory: ", "銀行＋インベントリ: ");
		JA.put("Tax paid: ", "税支払額: ");
		JA.put("Higher risk: ", "高リスク: ");
		JA.put("Every PriceCheck toggle, including overlay and GE options",
			"オーバーレイとGEオプションを含む、すべてのPriceCheck設定");
		JA.put("Margin confirmed across the 5m + 1h books", "5分足と1時間足の板で利幅を確認済み");
		JA.put("Profit vs gp spent across your logged flips, checks excluded",
			"記録したフリップの利益÷投入gp（価格チェックは除く）");
		JA.put("Conservative half-recovery target, taxed. Only if it reverts - never guaranteed.",
			"税引き後の控えめな半値戻り目標。反発した場合のみで、保証はありません。");
		JA.put("Buy limits are per account, so more accounts move more of each item. Volume caps stay shared.",
			"購入上限はアカウントごとなので、アカウントが多いほど各アイテムを多く動かせます。出来高の上限は共有です。");
		JA.put("Your roll, like 25m or 1.2b. Filled from your bank when detected.",
			"手持ち資金（例: 25m や 1.2b）。銀行から検出されると自動入力されます。");
		JA.put("Back up your flip log to your PriceCheck account and see it at flipping.pricecheck.gg/portfolio",
			"フリップ記録をPriceCheckアカウントにバックアップし、flipping.pricecheck.gg/portfolio で確認できます");
		JA.put("How long you are flipping for. Short sessions favour fast movers; overnight admits patient big tickets and fits more buy-limit windows.",
			"フリップする時間の長さ。短いセッションは回転の速い品、夜通しは待てる高額品を選び、購入上限の回復も多く入ります。");
		JA.put("Watching = your tracked-margins watchlist (the + button on flip rows); live GE offers show on the Flips tab and the in-game overlays.",
			"ウォッチ＝追跡中の利幅リスト（フリップ行の＋ボタン）。実際のGE注文はフリップタブとゲーム内オーバーレイに表示されます。");

		// ── terminal desk ──
		JA.put("BID", "買値");
		JA.put("ASK", "売値");
		JA.put("SPRD", "差額");
		JA.put("NET/EA", "純益/個");
		JA.put("ROI", "利益率");
		JA.put("TAX", "税");
		JA.put("VOL 24H", "出来高24H");
		JA.put("HI", "高値");
		JA.put("LO", "安値");
		JA.put("OFI", "需給");
		JA.put("LIMIT", "上限");
		JA.put("RESET", "回復");
		JA.put("FILL", "約定率");
		JA.put("YOUR PRICE", "指値");
		JA.put("MARGIN/EA", "利幅/個");
		JA.put("MARGIN", "利幅");
		JA.put("ORDER TICKET", "注文票");
		JA.put("POSITIONS", "建玉");
		JA.put("HELD", "保有");
		JA.put("ITEM", "アイテム");
		JA.put("PRICE", "価格");
		JA.put("EV/HR", "EV/時");
		JA.put("AGE", "経過");
		JA.put("NOW", "現在");
		JA.put("TIME & SALES", "約定履歴");
		JA.put("Δ VS YOU", "指値との差");
		JA.put("LIVE", "稼働");
		JA.put("ENGINE", "エンジン");
		JA.put("CASH", "所持金");
		JA.put("SLOTS", "枠");
		JA.put("WORLD", "ワールド");
		JA.put("P&L TODAY", "本日損益");
		JA.put("uP&L", "含み損益");
		JA.put("SEATED", "約定待ち");
		JA.put("NET", "純額");
		JA.put("NET ", "純額 ");
		JA.put("SESSION", "セッション");
		JA.put("OPPORTUNITY RADAR  ·  TOP EV/HR", "注目銘柄  ·  EV/時 上位");
		JA.put("FRESH DIPS  ·  DUMP CATCHER", "急落  ·  ダンプ検知");
		JA.put("TOP MOVERS  ·  GAINERS", "値動き  ·  上昇");
		JA.put("TOP MOVERS  ·  LOSERS", "値動き  ·  下落");
		JA.put("HELD  ·  YOUR POSITIONS", "保有  ·  現在の建玉");
		JA.put("SESSION  ·  FLOW", "セッション  ·  推移");
		JA.put("RECENT FLIPS  ·  CLOSED", "直近のフリップ  ·  約定済み");
		JA.put("WATCHLIST  ·  YOUR TARGETS", "ウォッチ  ·  目標価格");
		JA.put("TOP PICKS  ·  BY EV/HR", "おすすめ  ·  EV/時順");
		JA.put("TOP PICKS  ·  EV/HR", "おすすめ  ·  EV/時");
		JA.put("PROFIT  ·  x", "利益  ·  x");
		JA.put("PROFIT", "利益");
		JA.put("YOUR TRADES  ·  ", "自分の取引  ·  ");
		JA.put("building the corridor...", "レンジを構築中...");
		JA.put("BUY", "買い");
		JA.put("SELL", "売り");
		JA.put("WAIT", "待機");
		JA.put("NEAR", "接近");
		JA.put("CATCH", "拾う");
		JA.put("KNIFE", "落下中");
		JA.put("WATCH", "様子見");
		JA.put("OFFERS", "件");
		JA.put(" OFFERS", " 件");
		JA.put("Catches · %d", "急落 · %d件");
		JA.put("Open positions · %d", "建玉 · %d件");
		JA.put("Tracking · %d", "追跡中 · %d件");
		JA.put("Plugin key", "プラグインキー");
		JA.put("Options", "オプション");
		JA.put("%d flips · by EV/hr", "%d件 · EV/時順");
		JA.put("%d flips · %dk+/hr", "%d件 · %dk+/時");
		JA.put("Searching \"%s\"…", "「%s」を検索中…");
		JA.put("%d match \"%s\"", "「%2$s」に%1$d件");
		JA.put("TODAY", "本日");
		JA.put("WEEK", "今週");
		JA.put("ALL TIME", "累計");
		JA.put("FLIPS", "件数");
		JA.put("WON", "勝率");
		JA.put("AVG ROI", "平均利益率");
		JA.put("Sync flip log", "フリップ記録を同期");
		JA.put("Offer advisor overlay", "注文アドバイザー表示");
		JA.put("All options · ", "すべての設定 · ");
		JA.put("RuneLite plugin settings", "RuneLiteのプラグイン設定");
		JA.put("Key active", "キー有効");
		JA.put("Key rejected", "キーが拒否されました");
		JA.put("Free plan", "無料プラン");
		JA.put("Lifetime license", "無期限ライセンス");
		JA.put("License expired", "ライセンス期限切れ");
		JA.put("PriceCheck member", "PriceCheckメンバー");
		JA.put("Renews in %d days", "%d日後に更新");
		JA.put("%d days left", "残り%d日");
		JA.put("Renews in %d hours", "%d時間後に更新");
		JA.put("%d hours left", "残り%d時間");
		JA.put("Trial · day %d of %d · %dm left today", "体験 · %d/%d日目 · 本日残り%d分");
		JA.put("%s · watching %d items", "%s · %d件を監視");
		JA.put("SKIP", "見送り");
		JA.put("FORMING", "形成中");
		JA.put("RECOVER", "回復中");
		JA.put("FALLING KNIFE - skip", "落下中 - 見送り");
		JA.put("Loading chart…", "チャート読込中…");
		JA.put("displaced", "乖離");
		JA.put("displaced %s", "乖離 %s");
		JA.put("est.", "推定");
		JA.put("%s est", "%s 推定");
		JA.put("bounces %d/10 (n=%d)", "反発 %d/10 (n=%d)");
		JA.put("Displaced", "乖離");
		JA.put("Entry", "建値");
		JA.put("Recover to", "戻り目標");
		JA.put("Read", "判断");
		JA.put("Est. profit", "予想利益");
		JA.put("Reversion", "反発");
		JA.put("Exp. hold", "予想保有");
		JA.put("Suggested size", "推奨数量");
		JA.put("The dump-catch board comes online when the measured detector is live.", "急落検知パネルは計測検知が稼働すると表示されます。");
		JA.put("held %s", "保有 %s");
		JA.put("in %s", "所要 %s");
		JA.put("Remove position…", "建玉を削除…");
		JA.put("Delete flip…", "フリップを削除…");
		JA.put("Buy and sell on the GE and flips appear here. No key needed.", "GEで売買するとここにフリップが表示されます。キーは不要です。");
		JA.put("1 hour", "1時間");
		JA.put("4 hours", "4時間");
		JA.put("Overnight", "夜通し");
		JA.put("%s in", "%s 投入");
		JA.put("capital from your bank: %s", "銀行の資金: %s");
		JA.put("Held %s · avg", "保有 %s · 平均");
		JA.put("Watching at", "監視価格");
		JA.put("Sell now", "現在の売値");
		JA.put("watching", "監視中");
		JA.put("no data", "データなし");
		JA.put("thin", "薄い");
		JA.put("healthy", "良好");
		JA.put("Reconnecting…", "再接続中…");
		JA.put("No items match.", "一致するアイテムはありません。");
		JA.put("No flips right now.", "今はフリップがありません。");
		JA.put("Stale Prints", "約定が古い");
		JA.put("Slow Fills", "約定が遅い");
		JA.put("Low EV", "EVが低い");
		JA.put("Small Margin", "利幅が小さい");
		JA.put("Higher risk: %s. Margin is volume-confirmed but this missed one board quality bar.", "高リスク: %s。利幅は出来高で確認済みですが、板の品質基準を1つ満たしていません。");
		JA.put("no flips yet this session", "このセッションはまだ取引なし");
		JA.put("%s/hr while flipping", "取引中 %s/時");
		JA.put("gp/hr shows after a few active minutes", "gp/時は数分の取引後に表示されます");
		JA.put("Backing up %d fills…", "%d件をバックアップ中…");
		JA.put("Backed up · ", "バックアップ済 · ");
		JA.put("open web portfolio", "ウェブのポートフォリオを開く");
		JA.put("Local only · ", "ローカルのみ · ");
		JA.put("back up in Setup", "設定でバックアップ");
		JA.put("Completed flips · last %d", "完了フリップ · 直近%d件");
		JA.put("Completed flips", "完了フリップ");
		JA.put("Your tracked items + best flips, ranked. Click here to type a search instead.", "追跡中のアイテムと注目フリップ（ランク順）。ここをクリックすると通常の検索に戻ります。");
		JA.put("rec buy: %s", "推奨買値: %s");
		JA.put("rec sell: %s", "推奨売値: %s");
		JA.put("traded: %s", "直近約定: %s");
		JA.put("%s low: %s", "%s 安値: %s");
		JA.put("%s high: %s", "%s 高値: %s");
		JA.put("buy limit: %s left of %s", "購入上限: 残り%s / %s");
		JA.put("4h buy limit reached", "4時間の購入上限に到達");
		JA.put("4h buy limit reached - resets in %s", "4時間の購入上限に到達 - %s後に回復");
		JA.put("No trade history yet", "取引履歴はまだありません");
		JA.put("balanced", "均衡");
		JA.put("%s SELL", "%s 売り優勢");
		JA.put("%s BUY", "%s 買い優勢");
		JA.put("%s price", "%s 価格");
		JA.put("%s trades", "%s 約定");
		JA.put("hold shift", "Shiftキーで表示");
		JA.put("Latest", "最新");
		JA.put("type a price to preview profit", "価格を入力すると利益を表示");
		JA.put("resells at", "再販価格");
		JA.put("your cost", "取得単価");
		JA.put("vs buy", "買値との比");
		JA.put("ACTIVE", "進行中");
		JA.put("SETTLING", "収束中");
		JA.put("ENDED", "終了");
		JA.put("RECOVERING", "回復中");
		JA.put("FADED", "消滅");
		JA.put("QUIET", "閑散");
		JA.put("NORMAL", "通常");
		JA.put("OK", "適正");
		JA.put("HOLD", "待ち");
		JA.put("COLLECT", "回収");
		JA.put("RAISE", "引上げ");
		JA.put("DROP", "引下げ");
		JA.put("CUT", "損切り");
		JA.put("DEAD", "妙味なし");
		JA.put("seated", "約定待ち");
		JA.put("%s closing", "%s 接近中");
		JA.put("%s drifting", "%s 乖離中");
		JA.put("%s away", "%s 差");
		JA.put("%d/%d SEATED", "%d/%d 約定待ち");
		JA.put("REALIZED TODAY", "本日確定");
		JA.put("GP / HR", "GP/時");
		JA.put("WIN", "勝率");
		JA.put("TAX PAID", "税支払額");
		JA.put("buy %s", "買値 %s");
		JA.put("TRIAL", "体験");
		JA.put("FREE", "無料");
		JA.put("PREMIUM", "プレミアム");
		JA.put("DIP", "押し目");
		JA.put("BIG", "大口");
		JA.put("BIG · BAND", "大口 · 帯");
		JA.put("holding", "保有中");
		JA.put("no live price", "価格データなし");
	}
}

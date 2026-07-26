package gg.pricecheck.runelite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Guards the translation table. A mistranslated pattern would not crash the
 * plugin (I18n.f falls back to English), it would quietly show English in one
 * spot forever, which is exactly the kind of thing nobody notices until a user
 * reports it.
 */
public class I18nTest
{
	private static final Pattern SPEC = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");

	/** Every %s / %d in a key must be answerable by its translation. */
	@Test
	public void patternsTakeTheSameArguments()
	{
		final List<String> bad = new ArrayList<>();
		for (final Map.Entry<String, String> e : I18n.dictionary().entrySet())
		{
			final List<String> want = specs(e.getKey());
			if (want.isEmpty())
			{
				// A translation must not invent placeholders the caller never fills.
				if (!specs(e.getValue()).isEmpty())
				{
					bad.add(e.getKey() + " -> value has placeholders the key does not");
				}
				continue;
			}
			final List<String> got = specs(e.getValue());
			// Positional forms (%1$d) may reorder, so compare as multisets.
			final List<String> a = new ArrayList<>(want);
			final List<String> b = new ArrayList<>(got);
			a.sort(null);
			b.sort(null);
			if (!a.equals(b))
			{
				bad.add(e.getKey() + " -> " + e.getValue() + "  (" + a + " vs " + b + ")");
			}
		}
		assertTrue("translation patterns disagree with their source:\n" + String.join("\n", bad), bad.isEmpty());
	}

	/** Nothing in the table may be blank, or a label would render as nothing. */
	@Test
	public void noEmptyTranslations()
	{
		for (final Map.Entry<String, String> e : I18n.dictionary().entrySet())
		{
			assertFalse("empty translation for: " + e.getKey(), e.getValue().trim().isEmpty());
		}
	}

	/** English must pass through untouched, whatever the table says. */
	@Test
	public void englishIsUnchanged()
	{
		I18n.setLanguage(PriceCheckConfig.Language.ENGLISH);
		assertFalse(I18n.japanese());
		for (final String key : new ArrayList<>(I18n.dictionary().keySet()))
		{
			assertEquals(key, I18n.t(key));
		}
		assertEquals("31 flips · by EV/hr", I18n.f("%d flips · by EV/hr", 31));
	}

	/** A key with no entry falls back to its English source, never to blank. */
	@Test
	public void missingKeysFallBackToEnglish()
	{
		I18n.setLanguage(PriceCheckConfig.Language.JAPANESE);
		assertEquals("no entry for this one", I18n.t("no entry for this one"));
		assertEquals("Twisted bow", I18n.t("Twisted bow"));   // item names must survive
		I18n.setLanguage(PriceCheckConfig.Language.ENGLISH);
	}

	/** Every pattern must actually format with plausible arguments. */
	@Test
	public void patternsFormatWithoutThrowing()
	{
		I18n.setLanguage(PriceCheckConfig.Language.JAPANESE);
		for (final String key : new ArrayList<>(I18n.dictionary().keySet()))
		{
			final List<String> want = specs(key);
			if (want.isEmpty())
			{
				continue;
			}
			final Object[] args = new Object[want.size()];
			for (int i = 0; i < args.length; i++)
			{
				args[i] = want.get(i).endsWith("d") ? (Object) 7 : (Object) "x";
			}
			final String out = I18n.f(key, args);
			assertFalse("pattern produced nothing: " + key, out == null || out.isEmpty());
		}
		I18n.setLanguage(PriceCheckConfig.Language.ENGLISH);
	}

	private static List<String> specs(String s)
	{
		final List<String> out = new ArrayList<>();
		final Matcher m = SPEC.matcher(s);
		while (m.find())
		{
			// Compare on the conversion letter only; %1$d and %d take the same value.
			out.add(m.group().substring(m.group().length() - 1));
		}
		out.removeAll(Arrays.asList("%"));
		return out;
	}
}

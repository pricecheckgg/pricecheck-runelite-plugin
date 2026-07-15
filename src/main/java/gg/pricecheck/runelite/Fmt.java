package gg.pricecheck.runelite;

import java.util.Locale;

/** gp formatting shared everywhere. Lowercase compact (1.5b / 200m / 50.9k) to
 * match pricecheck.gg, and exact comma-grouped for the figures you type/read. */
final class Fmt
{
	private Fmt() {}

	static String compact(long n)
	{
		final long a = Math.abs(n);
		if (a >= 1_000_000_000L) return trim(n / 1e9) + "b";
		if (a >= 1_000_000L) return trim(n / 1e6) + "m";
		if (a >= 1_000L) return trim(n / 1e3) + "k";
		return String.valueOf(n);
	}

	static String full(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

	private static String trim(double d)
	{
		String s = String.format(Locale.ROOT, "%.2f", d);
		if (s.contains("."))
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}
}

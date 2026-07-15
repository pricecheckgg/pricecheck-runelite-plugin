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

	/** Parse a gp amount the way players type it: 25m, 1.2b, 800k, 25,000,000.
	 *  Returns -1 when it isn't one. */
	static long parseGp(String s)
	{
		if (s == null)
		{
			return -1;
		}
		s = s.trim().toLowerCase(Locale.ROOT).replace(",", "");
		if (s.isEmpty())
		{
			return -1;
		}
		double mult = 1;
		final char last = s.charAt(s.length() - 1);
		if (last == 'k') { mult = 1e3; s = s.substring(0, s.length() - 1); }
		else if (last == 'm') { mult = 1e6; s = s.substring(0, s.length() - 1); }
		else if (last == 'b') { mult = 1e9; s = s.substring(0, s.length() - 1); }
		try
		{
			final double v = Double.parseDouble(s);
			if (v < 0 || v * mult > 9e15)
			{
				return -1;
			}
			return (long) Math.floor(v * mult);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
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

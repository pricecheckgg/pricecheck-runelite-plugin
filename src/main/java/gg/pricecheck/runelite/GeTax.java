package gg.pricecheck.runelite;

/**
 * Grand Exchange tax, mirroring the server exactly so client advice and server
 * margins never disagree: 2% of the sell price, floored, capped at 5,000,000 per
 * item; sells under 50gp are untaxed. (Bond/starter-tool exemptions are ignored
 * here — those items aren't flipped.)
 */
final class GeTax
{
	static final long CAP = 5_000_000L;

	private GeTax()
	{
	}

	static long tax(long sellPrice)
	{
		if (sellPrice < 50)
		{
			return 0;
		}
		return Math.min((long) Math.floor(sellPrice * 0.02D), CAP);
	}

	/** Net profit per item from buying at {@code buy} and selling at {@code sell}, after tax. */
	static long net(long buy, long sell)
	{
		return (sell - tax(sell)) - buy;
	}

	/** Lowest sell price that still breaks even (net >= 0) against a given buy. */
	static long breakevenSell(long buy)
	{
		long s = (long) Math.ceil(buy / 0.98D);
		// Tax is floored, so the true minimum can sit a little below the naive guess.
		while (s > buy && net(buy, s - 1) >= 0)
		{
			s--;
		}
		int guard = 0;
		while (net(buy, s) < 0 && guard++ < 64)
		{
			s++;
		}
		return s;
	}
}

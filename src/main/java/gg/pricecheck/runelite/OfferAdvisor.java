package gg.pricecheck.runelite;

import java.awt.Color;

/**
 * The brain. Turns one active GE offer + the live market into an EXACT
 * instruction: keep it, or move it by a precise amount, or kill it. Pure logic —
 * no client or network calls — so it's deterministic and unit-testable.
 *
 * Market reference (matches the server): to BUY you place at the current low
 * (FlipData.buy, the price sellers are hitting); to SELL you place at the current
 * high (FlipData.sell, the price buyers are paying). Margins are post-GE-tax.
 */
final class OfferAdvisor
{
	private static final Color GREEN = new Color(0x5d, 0xf2, 0x9a);
	private static final Color AMBER = new Color(0xe6, 0xc6, 0x67);
	private static final Color RED = new Color(0xf2, 0x6b, 0x6d);
	private static final Color GREY = new Color(0x9a, 0x91, 0x7c);

	private OfferAdvisor()
	{
	}

	static OfferAdvice advise(TrackedOffer offer, FlipData live)
	{
		final int slot = offer.getSlot();

		if (offer.isDone())
		{
			final String nm = live != null ? live.getName() : "Item " + offer.getItemId();
			return new OfferAdvice(slot, nm, "", OfferAdvice.Kind.COLLECT, "Filled - collect", GREY);
		}
		if (!offer.isActive())
		{
			return null;
		}

		final String side = offer.isBuying() ? "BUY" : "SELL";
		if (live == null)
		{
			return new OfferAdvice(slot, "Item " + offer.getItemId(), side, OfferAdvice.Kind.NO_DATA, "No live data yet", GREY);
		}

		final String name = live.getName();
		final long yourPrice = offer.getPrice();
		final long marketBuy = live.getBuy();      // current low  - place a BUY here
		final long marketSell = live.getSell();    // current high - place a SELL here
		final long marketMargin = live.getProfit();// net(marketBuy, marketSell), post-tax

		if (offer.isBuying())
		{
			// A dead market margin can't be rescued by repricing the bid.
			if (marketMargin <= 0)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DEAD,
					"Margin gone (" + signed(marketMargin) + " at market) - cancel", RED);
			}
			if (live.isFallingKnife())
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.FALLING,
					"Price falling " + pct(live.getTrendPct()) + " - margin closing, watch it", AMBER);
			}
			// Your bid must sit at or above the current low or sellers won't hit it.
			if (yourPrice < marketBuy)
			{
				final long delta = marketBuy - yourPrice;
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.RAISE_BUY,
					"Raise buy to " + full(marketBuy) + " (+" + gp(delta) + ") - margin " + signed(marketMargin), AMBER);
			}
			final long yourMargin = GeTax.net(yourPrice, marketSell);
			if (yourMargin <= 0)
			{
				// Competitive bid, but priced so high it loses even at the market high.
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DEAD,
					"Bid too high - " + signed(yourMargin) + " if it fills, lower it", RED);
			}
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.ON_TRACK,
				"On track - filling, " + signed(yourMargin) + " if sold at market", GREEN);
		}

		// SELLING: your ask fills only when it is at or below the current high.
		if (yourPrice > marketSell)
		{
			final long delta = yourPrice - marketSell;
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DROP_SELL,
				"Won't fill - drop to " + full(marketSell) + " (-" + gp(delta) + ")", AMBER);
		}
		return new OfferAdvice(slot, name, side, OfferAdvice.Kind.ON_TRACK,
			"On track - at/under market, filling", GREEN);
	}

	private static String signed(long n)
	{
		return (n >= 0 ? "+" : "-") + gp(Math.abs(n));
	}

	private static String pct(double p)
	{
		return String.format("%.1f%%", p);
	}

	/** Exact, comma-grouped price to type into the GE. */
	static String full(long n)
	{
		return String.format("%,d", n);
	}

	/** Compact gp for deltas and margins. */
	static String gp(long n)
	{
		final long a = Math.abs(n);
		final String sign = n < 0 ? "-" : "";
		if (a >= 1_000_000_000L)
		{
			return sign + trim(a / 1e9D) + "b";
		}
		if (a >= 1_000_000L)
		{
			return sign + trim(a / 1e6D) + "m";
		}
		if (a >= 1_000L)
		{
			return sign + trim(a / 1e3D) + "k";
		}
		return String.valueOf(n);
	}

	private static String trim(double d)
	{
		String s = String.format("%.2f", d);
		if (s.contains("."))
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}
}

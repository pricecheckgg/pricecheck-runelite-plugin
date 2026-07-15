package gg.pricecheck.runelite;

import java.awt.Color;

/**
 * The brain. Turns one active GE offer + the live market into an EXACT
 * instruction: keep it, or move it by a precise amount, or kill it. Pure logic —
 * no client or network calls — so it's deterministic and unit-testable.
 *
 * Market reference (matches the server): to BUY you place at FlipData.buy and to
 * SELL at FlipData.sell. The server's prices are already positioned so an offer
 * at them heads the queue. Margins are post-GE-tax.
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
			return new OfferAdvice(slot, nm, "", OfferAdvice.Kind.COLLECT, "Filled - collect", "COLLECT", GREY);
		}
		if (!offer.isActive())
		{
			return null;
		}

		final String side = offer.isBuying() ? "BUY" : "SELL";
		if (live == null)
		{
			return new OfferAdvice(slot, "Item " + offer.getItemId(), side, OfferAdvice.Kind.NO_DATA, "No live data yet", "", GREY);
		}

		final String name = live.getName();
		final long yourPrice = offer.getPrice();
		final long marketBuy = live.getBuy();      // current low  - place a BUY here
		final long marketSell = live.getSell();    // current high - place a SELL here
		// A returned-but-unpriced item deserializes to 0 (Gson leaves a missing
		// primitive at its default). Never derive a reprice target from that or
		// we'd tell a seller to "drop to 0" and give the item away.
		if (marketBuy <= 0 || marketSell <= 0)
		{
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.NO_DATA, "No live price yet", "", GREY);
		}
		final long marketMargin = live.getProfit(); // net(marketBuy, marketSell), post-tax

		if (offer.isBuying())
		{
			// Loss states are RED and take precedence over the amber falling warning.
			if (marketMargin <= 0)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DEAD,
					"Margin gone (" + signed(marketMargin) + " at market) - cancel", "MARGIN DEAD", RED);
			}
			final long yourMargin = GeTax.net(yourPrice, marketSell);
			if (yourMargin <= 0)
			{
				// Bid so high it loses even sold at the market high — always report red.
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DEAD,
					"Bid too high - " + signed(yourMargin) + " if it fills, lower it", "BID TOO HIGH", RED);
			}
			// An offer that has already transacted is demonstrably filling — don't
			// nag it over small ticks. getQuantitySold covers bought units too.
			final boolean filling = offer.getSoldQty() > 0;
			if (!filling && live.isFallingKnife())
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.FALLING,
					"Price falling " + pct(Math.abs(live.getTrendPct())) + " - margin closing, watch it",
					"FALLING " + pct(Math.abs(live.getTrendPct())), AMBER);
			}
			// Your bid must sit at (near) the current low or sellers won't hit it.
			// Ignore sub-1% noise, and don't nag an offer that's already buying.
			final long buyTol = Math.max(marketBuy / 100, 1);
			if (!filling && yourPrice < marketBuy - buyTol)
			{
				final long delta = marketBuy - yourPrice;
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.RAISE_BUY,
					"Raise buy to " + full(marketBuy) + " (+" + gp(delta) + ") - margin " + signed(marketMargin),
					"RAISE +" + gp(delta), AMBER);
			}
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.ON_TRACK,
				"On track - " + signed(yourMargin) + " if sold at market", "ON TRACK", GREEN);
		}

		// SELLING: it fills at/under the current high. Only flag a drop when the ask
		// is meaningfully (>1%) above market AND nothing has sold yet — an offer that
		// is already selling is filling fine even a touch above the momentary high.
		final boolean sellFilling = offer.getSoldQty() > 0;
		final long sellTol = Math.max(marketSell / 100, 1);
		if (!sellFilling && yourPrice > marketSell + sellTol)
		{
			final long delta = yourPrice - marketSell;
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DROP_SELL,
				"Above market by " + gp(delta) + " - drop to " + full(marketSell) + " to fill",
				"DROP -" + gp(delta), AMBER);
		}
		return new OfferAdvice(slot, name, side, OfferAdvice.Kind.ON_TRACK,
			"On track - at/near market, filling", "ON TRACK", GREEN);
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

package gg.pricecheck.runelite;

import java.awt.Color;

/**
 * The brain. Turns one active GE offer + the live market into an EXACT
 * instruction: keep it, or move it by a precise amount, or kill it. Pure logic -
 * no client or network calls: so it's deterministic and unit-testable.
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
	private static final Color BLUE = new Color(0x7f, 0xb0, 0xff);   // patient hold: no action, will fill

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

		// The ONE thing that turns a patient offer into a real problem: the item is
		// dropping against its own 1h average, so the spread is closing and waiting
		// only gets worse. A thin or briefly-inverted spread on a STABLE item is not
		// this - it reopens, and the offer still fills - so we hold it rather than
		// tell the user to cancel or to cut the price and lose value.
		final boolean falling = live.isFallingKnife() || live.getTrendPct() <= -2.0D;

		if (offer.isBuying())
		{
			// An offer that has already transacted is demonstrably filling.
			final boolean filling = offer.getSoldQty() > 0;
			final long yourMargin = GeTax.net(yourPrice, marketSell);

			// A real error, not a market wobble: bidding at or above the price you
			// could sell into is a guaranteed loss whatever the trend. Lower it.
			if (yourPrice >= marketSell)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DEAD,
					"Bid at/above the sell price - " + signed(yourMargin) + " if it fills, lower it", "BID TOO HIGH", RED);
			}
			// Genuinely dead: the price is falling AND there is no margin left, so
			// the spread will not reopen soon. This is the only cancel-a-buy state.
			if (falling && marketMargin <= 0)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DEAD,
					"Price falling and margin gone (" + signed(marketMargin) + ") - cancel", "MARGIN DEAD", RED);
			}
			// Falling but not yet filling: a heads-up, not a cancel.
			if (!filling && falling)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.FALLING,
					"Price falling " + pct(Math.abs(live.getTrendPct())) + " - margin closing, watch it",
					"FALLING " + pct(Math.abs(live.getTrendPct())), AMBER);
			}
			// Spread thin or briefly gone but the item is NOT falling: the buy still
			// fills and the spread reopens. Hold it. Do not tell the user to cancel a
			// buy that will fill.
			if (marketMargin <= 0)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.HOLD,
					"Spread thin right now, not falling - the buy still fills, hold for it to reopen", "HOLD", BLUE);
			}
			// Sitting below the low and not yet buying: nudge the bid to head the queue.
			final long buyTol = Math.max(marketBuy / 100, 1);
			if (!filling && yourPrice < marketBuy - buyTol)
			{
				final long delta = marketBuy - yourPrice;
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.RAISE_BUY,
					"Raise buy to " + full(marketBuy) + " (+" + gp(delta) + ") - margin " + signed(marketMargin),
					"RAISE +" + gp(delta), AMBER);
			}
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.ON_TRACK,
				"On track - " + signed(yourMargin) + " if sold at market", "OK " + signed(yourMargin), GREEN);
		}

		// SELLING: an ask above the current high is a PATIENT sell, not a mistake -
		// it fills when a buyer prints up at your price. Only advise a drop when the
		// item is actually falling, so waiting would cost you; otherwise hold and let
		// it fill, rather than telling the user to cut the price and lose value.
		final boolean sellFilling = offer.getSoldQty() > 0;
		final long sellTol = Math.max(marketSell / 100, 1);
		if (!sellFilling && yourPrice > marketSell + sellTol)
		{
			final long delta = yourPrice - marketSell;
			if (falling)
			{
				return new OfferAdvice(slot, name, side, OfferAdvice.Kind.DROP_SELL,
					"Above market and price falling " + pct(Math.abs(live.getTrendPct())) + " - drop to " + full(marketSell) + " so it fills",
					"DROP -" + gp(delta), AMBER);
			}
			return new OfferAdvice(slot, name, side, OfferAdvice.Kind.HOLD,
				"Patient sell, " + gp(delta) + " above the current high - it fills when a buyer prints up here, holding", "HOLD", BLUE);
		}
		return new OfferAdvice(slot, name, side, OfferAdvice.Kind.ON_TRACK,
			"On track - at/near market, filling (" + signed(marketMargin) + " live margin)", "OK " + signed(marketMargin), GREEN);
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

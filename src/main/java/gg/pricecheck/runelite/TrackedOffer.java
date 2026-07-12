package gg.pricecheck.runelite;

import lombok.Value;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Immutable snapshot of one Grand Exchange slot, captured on the client thread in
 * the offer-changed handler so the advisor (which runs on a background poller and
 * the overlay render thread) can read it without touching the live client object.
 */
@Value
class TrackedOffer
{
	int slot;
	int itemId;
	long price;   // per-item price the player set
	int totalQty;
	int soldQty;
	GrandExchangeOfferState state;

	static TrackedOffer of(int slot, GrandExchangeOffer o)
	{
		return new TrackedOffer(slot, o.getItemId(), o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getState());
	}

	boolean isBuying()
	{
		return state == GrandExchangeOfferState.BUYING;
	}

	boolean isSelling()
	{
		return state == GrandExchangeOfferState.SELLING;
	}

	boolean isActive()
	{
		return isBuying() || isSelling();
	}

	boolean isDone()
	{
		return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD;
	}

	boolean isRelevant()
	{
		return isActive() || isDone();
	}
}

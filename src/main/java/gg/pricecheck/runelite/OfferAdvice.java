package gg.pricecheck.runelite;

import java.awt.Color;
import lombok.Value;

/** One line of advice for one active/finished GE slot. */
@Value
class OfferAdvice
{
	enum Kind
	{
		ON_TRACK, RAISE_BUY, DROP_SELL, DEAD, FALLING, COLLECT, NO_DATA
	}

	int slot;
	String itemName;
	String side;      // "BUY", "SELL" or ""
	Kind kind;
	String message;   // the exact instruction — no filler
	Color color;
}

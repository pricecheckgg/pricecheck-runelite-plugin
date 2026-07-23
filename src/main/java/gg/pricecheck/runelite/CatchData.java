package gg.pricecheck.runelite;

import java.util.List;
import lombok.Data;

/**
 * One live "mover": a price dump the board is tracking for a possible catch,
 * served as the {@code movers} sibling array on GET /api/plugin/flips. Field
 * names match the server's trimmed shape so Gson maps them directly.
 *
 * Honesty contract: pReversion, target and expHoldMin are trial-measured only
 * when {@code measured} is true; otherwise they are conservative estimates and
 * the surfaces say so. pReversion is a Wilson LOWER-bounded historical base
 * rate (only ever discounted by live flow, never inflated). target is an
 * explicit conservative, taxed, half-recovery projection contingent on the
 * reversion actually happening; it is never presented as guaranteed. A row
 * with too few trials hides its "bounces N/10" fraction and shows the sample
 * count, and a still-falling dump reads "FALLING KNIFE - skip", never a catch.
 */
@Data
public class CatchData
{
	private int geId;
	private String name;
	private String dir;            // "up" | "down"
	private double pctMove;        // signed displacement from the reference, %
	private int minutesRunning;    // how long the move has been running
	private String state;          // catchable | knife | watch | recovering | faded
	private double pReversion;     // Wilson lower-bound base rate [0,1], measured only when `measured`
	private int baseRateN;         // excursion trials behind pReversion (0 = heuristic/none)
	private int pReversionN;       // server alias for the same trial count
	private boolean knife;         // still falling: not catchable
	private boolean catchable;     // engine says a catch is on the table
	private boolean measured;      // pReversion/target/expHoldMin are trial-measured, not estimated
	private int expHoldMin;        // measured median recovery minutes; 0 when unmeasured
	private List<String> reasons;  // short board notes behind the read
	private long bid;              // live insta-buy entry
	private long target;           // conservative taxed half-recovery level
	private double roi;            // projected ROI at the target
	private int catchQty;          // suggested catch size
	private int rating;            // engine rank score (higher = stronger)
	private boolean thinEvidence;  // small sample: hide the fraction, show the count

	/** The excursion trial count behind the base rate, under either wire name. */
	int trialsN()
	{
		return Math.max(baseRateN, pReversionN);
	}

	/**
	 * True only when the loud CATCH read is earned: the row is trial-measured,
	 * catchable, not a knife, has enough excursion trials, a valid base rate,
	 * and that rate clears the caller's bar. Everything else stays a quiet watch.
	 */
	boolean loudEligible(int barPercent)
	{
		return measured && catchable && !knife
			&& trialsN() >= 20
			&& pReversion >= 0.0 && pReversion <= 1.0
			&& pReversion * 100.0 >= barPercent;
	}

	/**
	 * The reversion read. A measured, non-thin row shows the Wilson fraction and
	 * its sample count; a measured-but-thin row shows just the count; anything
	 * heuristic or under 20 trials reads "displaced" with no invented odds.
	 */
	String bouncesText()
	{
		if (!measured || trialsN() < 20)
		{
			return "displaced";
		}
		if (thinEvidence)
		{
			return "n=" + trialsN();
		}
		final int hits = (int) Math.round(Math.max(0.0, Math.min(1.0, pReversion)) * 10);
		return "bounces " + hits + "/10 (n=" + trialsN() + ")";
	}

	/** Measured median recovery time, or an honest "est." placeholder. */
	String holdText()
	{
		return (measured && expHoldMin > 0) ? "~" + expHoldMin + "m" : "est.";
	}
}

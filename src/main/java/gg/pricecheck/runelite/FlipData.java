package gg.pricecheck.runelite;

import lombok.Data;

/**
 * One flip as served by GET /api/plugin/flips. Field names match the server's
 * trimmed plugin shape exactly, so Gson maps them directly. No scoring internals
 * are sent — this is all the client ever sees.
 */
@Data
public class FlipData
{
	private int geId;
	private String name;
	private boolean members;
	private long buy;
	private long sell;
	private long margin;
	private long profit;
	private double roi;
	private long evPerHr;
	private boolean confirmed;
	private boolean fallingKnife;   // price dropping vs its 1h average -> margin at risk
	private double trendPct;        // mid vs 1h-avg, % (negative = falling)
	private Long limit;             // null = no published buy limit for this item
	private long vol1h;
	private String risk;            // null = clean board row; else why it missed one quality bar
	private String quote;           // "band" = laneBid/laneAsk are resting p25/p75 levels
	private Long laneBid;           // big-lane resting quote (null = not a lane item)
	private Long laneAsk;
	private Double laneOdds;        // measured sell-fill odds for the lane play
	private Double laneHold;        // expected hold in hours
	                                // ("age" | "qty" | "ev" | "profit")

	String riskLabel()
	{
		if (risk == null)
		{
			return null;
		}
		switch (risk)
		{
			case "age": return "Stale Prints";
			case "qty": return "Slow Fills";
			case "ev": return "Low EV";
			case "profit": return "Small Margin";
			default: return risk.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + risk.substring(1);
		}
	}
}

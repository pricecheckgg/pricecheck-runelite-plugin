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
}

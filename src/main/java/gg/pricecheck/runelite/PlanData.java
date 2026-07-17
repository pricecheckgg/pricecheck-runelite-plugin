package gg.pricecheck.runelite;

import java.util.List;
import lombok.Data;

/**
 * A slot-planner result as served by GET /api/plugin/plan — the same engine the
 * web dashboard uses. Field names match the server response exactly for Gson.
 */
@Data
public class PlanData
{
	private long scannedAt;
	private long capital;
	private int slots;          // GE slots per account
	private int accounts;
	private int totalSlots;
	private int usedSlots;
	private String capitalSource;   // "detected" (your reported bank) or "manual"
	private int hours;              // session horizon the plan was shaped for
	private List<Row> plan;
	private Totals totals;

	@Data
	public static class Row
	{
		private int slot;
		private int geId;
		private String name;
		private long buy;
		private long sell;
		private long profit;
		private long qty;
		private long outlay;
		private long estPerHr;
		private int acctsUsed;      // GE slots this item takes, one per account
		private long perAcct;       // quantity per account when acctsUsed > 1
		private String confidence;  // high / med / low
		private boolean confirmed;
		// Session receipts (Plan v2; absent on older servers, Gson leaves defaults).
		private String lane;        // flow / big / dip
		private long estSession;    // expected gp over the session horizon
		private double odds;        // measured fill odds 0-1
		private int ttfMin;         // ~minutes to first fill (flow rows)
		private double holdHrs;     // expected hold (big rows)
		private int patienceH;      // sell patience window in hours (big rows)
		private boolean band;       // big row quoting resting p25/p75 band levels
		private long target;        // reversion target (dip rows)
		private long riskGp;        // rough worst case for this row
		private long fills;         // expected fills over the session
	}

	@Data
	public static class Totals
	{
		private long outlay;
		private long leftover;
		private long estPerHr;
		// Plan v2 session range (absent on older servers).
		private long estSession;
		private long low;
		private long high;
		private long worstCase;
	}
}

package gg.pricecheck.runelite;

import lombok.Data;

/**
 * One tracked position as served by GET /api/plugin/tracked: already enriched by
 * the server with the live sell / P&L / break-even floor / status, so the panel
 * renders it verbatim and shows the exact same numbers as the website dashboard.
 */
@Data
public class TrackedItem
{
	private int geId;
	private String name;
	private long entryBuy;      // your cost basis
	private Long sellNow;       // live market sell (null = not trading right now)
	private long pnl;           // net gp if you sold now, vs your buy (post-tax)
	private double roi;         // pnl as % of entryBuy
	private long floor;         // don't sell below this: the break-even sell
	private String status;      // "healthy" | "thin" | "nodata"
	// Held truth from the synced flip log: when held, entryBuy above is the
	// REAL average fill cost and pnlTotal is the whole position's outcome.
	// Absent on older servers: Gson leaves held=false, the watch rendering.
	private boolean held;
	private long heldQty;
	private Long pnlTotal;      // null when the market is not printing
	private long watchBuy;      // the stored watch price (differs from entryBuy when held)
}

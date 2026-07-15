package gg.pricecheck.runelite;

import lombok.Data;

/** Account identity for the Settings tab, from GET /api/plugin/me. */
@Data
public class AccountInfo
{
	private String username;     // PriceCheck display name, or null if unresolved
	private boolean premium;
	private String plan;         // "premium"
	private String status;       // "active"
	private String keyPrefix;    // e.g. "pck_AbCd0000…" — display-only, never the full key
	private int trackedCount;
}

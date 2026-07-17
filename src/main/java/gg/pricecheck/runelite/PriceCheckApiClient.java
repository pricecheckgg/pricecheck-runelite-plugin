package gg.pricecheck.runelite;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The only network surface. Calls the gated data API with the user's plugin key
 * as a Bearer token. The server re-checks the subscription LIVE on every request,
 * so a lapsed sub returns 403 here and the panel goes empty — there is no local
 * gate to bypass.
 */
@Slf4j
@Singleton
public class PriceCheckApiClient
{
	// Hardcoded on purpose: this is our endpoint, not something the user sets.
	private static final HttpUrl BASE = HttpUrl.get("https://flipping.pricecheck.gg/api/plugin");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	PriceCheckApiClient(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	enum AuthState
	{
		OK, NO_KEY, INVALID_KEY, NO_SUBSCRIPTION, PLAN_REQUIRED, ERROR
	}

	// 403 means either a lapsed subscription or a plan below Trader Pro — the
	// body's error code tells them apart so the panel can say the right thing.
	private AuthState forbiddenState(Response res)
	{
		try
		{
			final String body = res.body() != null ? res.body().string() : "";
			if (body.contains("plan_required"))
			{
				return AuthState.PLAN_REQUIRED;
			}
		}
		catch (IOException | RuntimeException ignored)
		{
		}
		return AuthState.NO_SUBSCRIPTION;
	}

	static final class FlipsResult
	{
		final AuthState state;
		final List<FlipData> flips;

		FlipsResult(AuthState state, List<FlipData> flips)
		{
			this.state = state;
			this.flips = flips;
		}
	}

	FlipsResult getFlips(String key, int limit)
	{
		if (key == null || key.trim().isEmpty())
		{
			return new FlipsResult(AuthState.NO_KEY, Collections.emptyList());
		}

		final HttpUrl url = BASE.newBuilder()
			.addPathSegment("flips")
			.addQueryParameter("limit", String.valueOf(limit))
			.build();
		final Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + key.trim())
			.build();

		try (Response res = http.newCall(req).execute())
		{
			switch (res.code())
			{
				case 401:
					return new FlipsResult(AuthState.INVALID_KEY, Collections.emptyList());
				case 403:
					return new FlipsResult(forbiddenState(res), Collections.emptyList());
				default:
					break;
			}
			if (!res.isSuccessful() || res.body() == null)
			{
				return new FlipsResult(AuthState.ERROR, Collections.emptyList());
			}
			final FlipsResponse parsed = gson.fromJson(res.body().string(), FlipsResponse.class);
			final List<FlipData> flips = parsed != null && parsed.flips != null
				? parsed.flips : Collections.emptyList();
			return new FlipsResult(AuthState.OK, flips);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck flips fetch failed", e);
			return new FlipsResult(AuthState.ERROR, Collections.emptyList());
		}
	}

	private static final class FlipsResponse
	{
		boolean ok;
		long scannedAt;
		List<FlipData> flips;
	}

	/** Server-side search over ALL tradeable items (not just the ranked flips). */
	FlipsResult searchItems(String key, String query)
	{
		if (key == null || key.trim().isEmpty())
		{
			return new FlipsResult(AuthState.NO_KEY, Collections.emptyList());
		}
		if (query == null || query.trim().length() < 2)
		{
			return new FlipsResult(AuthState.OK, Collections.emptyList());
		}
		final HttpUrl url = BASE.newBuilder()
			.addPathSegment("search")
			.addQueryParameter("q", query.trim())
			.build();
		final Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + key.trim())
			.build();
		try (Response res = http.newCall(req).execute())
		{
			if (res.code() == 401)
			{
				return new FlipsResult(AuthState.INVALID_KEY, Collections.emptyList());
			}
			if (res.code() == 403)
			{
				return new FlipsResult(AuthState.NO_SUBSCRIPTION, Collections.emptyList());
			}
			if (!res.isSuccessful() || res.body() == null)
			{
				return new FlipsResult(AuthState.ERROR, Collections.emptyList());
			}
			final SearchResponse parsed = gson.fromJson(res.body().string(), SearchResponse.class);
			final List<FlipData> results = parsed != null && parsed.results != null
				? parsed.results : Collections.emptyList();
			return new FlipsResult(AuthState.OK, results);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck search failed", e);
			return new FlipsResult(AuthState.ERROR, Collections.emptyList());
		}
	}

	private static final class SearchResponse
	{
		long scannedAt;
		List<FlipData> results;
	}

	/** Account identity for the Settings tab. Returns null on any failure. */
	AccountInfo getAccount(String key)
	{
		if (key == null || key.trim().isEmpty())
		{
			return null;
		}
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("me").build())
			.header("Authorization", "Bearer " + key.trim())
			.build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				return null;
			}
			return gson.fromJson(res.body().string(), AccountInfo.class);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck account fetch failed", e);
			return null;
		}
	}

	/** Live data for a set of item ids (the items in your active GE offers). */
	Map<Integer, FlipData> getItems(String key, Collection<Integer> ids)
	{
		if (key == null || key.trim().isEmpty() || ids == null || ids.isEmpty())
		{
			return Collections.emptyMap();
		}
		final String idsCsv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
		final HttpUrl url = BASE.newBuilder()
			.addPathSegment("items")
			.addQueryParameter("ids", idsCsv)
			.build();
		final Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + key.trim())
			.build();

		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				return Collections.emptyMap();
			}
			final ItemsResponse parsed = gson.fromJson(res.body().string(), ItemsResponse.class);
			if (parsed == null || parsed.items == null)
			{
				return Collections.emptyMap();
			}
			final Map<Integer, FlipData> map = new HashMap<>(parsed.items.size());
			for (FlipData f : parsed.items)
			{
				map.put(f.getGeId(), f);
			}
			return map;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck items fetch failed", e);
			return Collections.emptyMap();
		}
	}

	private static final class ItemsResponse
	{
		long scannedAt;
		List<FlipData> items;
	}

	// ── Tracked positions (shared account store with the website dashboard) ──

	static final class TrackedResult
	{
		final AuthState state;
		final List<TrackedItem> tracked;

		TrackedResult(AuthState state, List<TrackedItem> tracked)
		{
			this.state = state;
			this.tracked = tracked;
		}
	}

	private static final MediaType JSON = MediaType.get("application/json");

	private TrackedResult readTracked(Request req)
	{
		try (Response res = http.newCall(req).execute())
		{
			switch (res.code())
			{
				case 401:
					return new TrackedResult(AuthState.INVALID_KEY, Collections.emptyList());
				case 403:
					return new TrackedResult(AuthState.NO_SUBSCRIPTION, Collections.emptyList());
				default:
					break;
			}
			if (!res.isSuccessful() || res.body() == null)
			{
				return new TrackedResult(AuthState.ERROR, Collections.emptyList());
			}
			final TrackedResponse parsed = gson.fromJson(res.body().string(), TrackedResponse.class);
			final List<TrackedItem> list = parsed != null && parsed.tracked != null
				? parsed.tracked : Collections.emptyList();
			return new TrackedResult(AuthState.OK, list);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck tracked call failed", e);
			return new TrackedResult(AuthState.ERROR, Collections.emptyList());
		}
	}

	TrackedResult getTracked(String key)
	{
		if (key == null || key.trim().isEmpty())
		{
			return new TrackedResult(AuthState.NO_KEY, Collections.emptyList());
		}
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("tracked").build())
			.header("Authorization", "Bearer " + key.trim())
			.build();
		return readTracked(req);
	}

	/** Start tracking an item at the given cost basis. Returns the updated list. */
	TrackedResult addTracked(String key, int geId, long entryBuy, String name, long entryMargin)
	{
		if (key == null || key.trim().isEmpty())
		{
			return new TrackedResult(AuthState.NO_KEY, Collections.emptyList());
		}
		final Map<String, Object> body = new HashMap<>();
		body.put("geId", geId);
		body.put("entryBuy", entryBuy);
		if (name != null) body.put("name", name);
		body.put("entryMargin", entryMargin);
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("tracked").build())
			.header("Authorization", "Bearer " + key.trim())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		return readTracked(req);
	}

	TrackedResult removeTracked(String key, int geId)
	{
		if (key == null || key.trim().isEmpty())
		{
			return new TrackedResult(AuthState.NO_KEY, Collections.emptyList());
		}
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("tracked").addPathSegment(String.valueOf(geId)).build())
			.header("Authorization", "Bearer " + key.trim())
			.delete()
			.build();
		return readTracked(req);
	}

	private static final class TrackedResponse
	{
		long scannedAt;
		List<TrackedItem> tracked;
	}

	// ── Slot planner ──

	static final class PlanResult
	{
		final AuthState state;
		final PlanData plan;        // null unless state == OK
		final boolean needCapital;  // server has no capital for you and none was sent

		PlanResult(AuthState state, PlanData plan, boolean needCapital)
		{
			this.state = state;
			this.plan = plan;
			this.needCapital = needCapital;
		}
	}

	/** Build a plan. capital < 0 means "use my last reported bank total". */
	PlanResult getPlan(String key, long capital, int slots, int accounts, int hours)
	{
		if (key == null || key.trim().isEmpty())
		{
			return new PlanResult(AuthState.NO_KEY, null, false);
		}
		final HttpUrl.Builder url = BASE.newBuilder()
			.addPathSegment("plan")
			.addQueryParameter("slots", String.valueOf(slots))
			.addQueryParameter("accounts", String.valueOf(accounts))
			.addQueryParameter("hours", String.valueOf(hours));
		if (capital >= 0)
		{
			url.addQueryParameter("capital", String.valueOf(capital));
		}
		final Request req = new Request.Builder()
			.url(url.build())
			.header("Authorization", "Bearer " + key.trim())
			.build();
		try (Response res = http.newCall(req).execute())
		{
			switch (res.code())
			{
				case 401:
					return new PlanResult(AuthState.INVALID_KEY, null, false);
				case 403:
					return new PlanResult(forbiddenState(res), null, false);
				case 400:
					return new PlanResult(AuthState.OK, null, true);
				default:
					break;
			}
			if (!res.isSuccessful() || res.body() == null)
			{
				return new PlanResult(AuthState.ERROR, null, false);
			}
			final PlanData parsed = gson.fromJson(res.body().string(), PlanData.class);
			return new PlanResult(parsed != null ? AuthState.OK : AuthState.ERROR, parsed, false);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck plan fetch failed", e);
			return new PlanResult(AuthState.ERROR, null, false);
		}
	}

	/** Sync a flip-log batch (fills + flips + open lots) for one game account.
	 *  Free-tier endpoint: needs only a valid key. Returns true when the server
	 *  accepted the batch (the caller then marks it synced). */
	boolean postFills(String key, FlipLogEngine.SyncBatch batch)
	{
		if (key == null || key.trim().isEmpty() || batch == null)
		{
			return false;
		}
		final Map<String, Object> body = new HashMap<>(4);
		body.put("accountHash", String.valueOf(batch.accountHash));
		final List<Map<String, Object>> fills = new java.util.ArrayList<>(batch.fills.size());
		for (final FlipLogEngine.Fill f : batch.fills)
		{
			final Map<String, Object> m = new HashMap<>(8);
			m.put("id", f.id);
			m.put("itemId", f.itemId);
			m.put("buy", f.buy);
			m.put("qty", f.qty);
			m.put("gross", f.gross);
			m.put("tax", f.tax);
			m.put("ts", f.ts);
			fills.add(m);
		}
		body.put("fills", fills);
		final List<Map<String, Object>> flips = new java.util.ArrayList<>(batch.flips.size());
		for (final FlipLogEngine.Flip f : batch.flips)
		{
			final Map<String, Object> m = new HashMap<>(10);
			m.put("id", f.id);
			m.put("itemId", f.itemId);
			m.put("itemName", f.name);
			m.put("qty", f.qty);
			m.put("buyGross", f.buyGross);
			m.put("sellGross", f.sellGross);
			m.put("tax", f.tax);
			m.put("openedAt", f.openedAt);
			m.put("closedAt", f.closedAt);
			m.put("marginCheck", f.check);
			flips.add(m);
		}
		body.put("flips", flips);
		final List<Map<String, Object>> lots = new java.util.ArrayList<>(batch.lots.size());
		for (final FlipLogEngine.Lot l : batch.lots)
		{
			final Map<String, Object> m = new HashMap<>(6);
			m.put("itemId", l.itemId);
			m.put("itemName", l.name);
			m.put("qty", l.qty);
			m.put("cost", l.cost);
			m.put("openedAt", l.openedAt);
			lots.add(m);
		}
		body.put("lots", lots);
		if (batch.deletes != null && !batch.deletes.isEmpty())
		{
			body.put("deletes", batch.deletes);
		}
		final List<Map<String, Object>> slots = new java.util.ArrayList<>(batch.slots == null ? 0 : batch.slots.size());
		if (batch.slots != null)
		{
			for (final FlipLogEngine.SlotExport s : batch.slots)
			{
				final Map<String, Object> m = new HashMap<>(10);
				m.put("slot", s.slot);
				m.put("itemId", s.itemId);
				m.put("qtySold", s.qtySold);
				m.put("total", s.total);
				m.put("price", s.price);
				m.put("spent", s.spent);
				m.put("state", s.state);
				m.put("placedMs", s.placedMs);
				m.put("updatedMs", s.updatedMs);
				slots.add(m);
			}
		}
		body.put("slots", slots);
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("fills").build())
			.header("Authorization", "Bearer " + key.trim())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		try (Response res = http.newCall(req).execute())
		{
			return res.isSuccessful();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck fills sync failed", e);
			return false;
		}
	}

	/** The freshest slot snapshots + open lots another machine uploaded for
	 *  this game account (the multi-machine handoff). Null on any failure. */
	FlipLogEngine.RemoteState getSlots(String key, long accountHash)
	{
		if (key == null || key.trim().isEmpty() || accountHash == -1)
		{
			return null;
		}
		final HttpUrl url = BASE.newBuilder()
			.addPathSegment("slots")
			.addQueryParameter("account", String.valueOf(accountHash))
			.build();
		final Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + key.trim())
			.build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				return null;
			}
			return gson.fromJson(res.body().string(), FlipLogEngine.RemoteState.class);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck slots fetch failed", e);
			return null;
		}
	}

	/** Report the player's liquid capital (coins + platinum tokens, bank +
	 *  inventory) so the web planner can prefill. Best-effort. */
	boolean postCapital(String key, long coins, long platTokens)
	{
		if (key == null || key.trim().isEmpty())
		{
			return false;
		}
		final Map<String, Object> body = new HashMap<>(2);
		body.put("coins", coins);
		body.put("platTokens", platTokens);
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("capital").build())
			.header("Authorization", "Bearer " + key.trim())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		try (Response res = http.newCall(req).execute())
		{
			return res.isSuccessful();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck capital post failed", e);
			return false;
		}
	}

	/** Ship a batch of the player's own GE offer events (see TelemetryCollector).
	 *  Best-effort: any failure just drops the batch. */
	boolean postTelemetry(String key, List<Map<String, Object>> events)
	{
		if (key == null || key.trim().isEmpty() || events == null || events.isEmpty())
		{
			return false;
		}
		final Map<String, Object> body = new HashMap<>(1);
		body.put("events", events);
		final Request req = new Request.Builder()
			.url(BASE.newBuilder().addPathSegment("telemetry").build())
			.header("Authorization", "Bearer " + key.trim())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		try (Response res = http.newCall(req).execute())
		{
			return res.isSuccessful();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("PriceCheck telemetry post failed", e);
			return false;
		}
	}
}

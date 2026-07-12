package gg.pricecheck.runelite;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
	private static final HttpUrl BASE = HttpUrl.get("https://premium.pricecheck.gg/api/plugin");

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
		OK, NO_KEY, INVALID_KEY, NO_SUBSCRIPTION, ERROR
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
					return new FlipsResult(AuthState.NO_SUBSCRIPTION, Collections.emptyList());
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
}

package com.ultimatepkmanmode;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class LeaderboardClient
{
	private static final MediaType JSON = MediaType.get("application/json");
	// Retry delays in seconds for prestige POST (4 attempts total).
	private static final long[] PRESTIGE_RETRY_DELAYS_SEC = { 2, 5, 15 };

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r ->
	{
		Thread t = new Thread(r, "UNM-LeaderboardRetry");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public LeaderboardClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	public void postWealth(String baseUrl, String apiKey, String playerName, long wealth)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("player_name", playerName);
		body.addProperty("wealth", wealth);

		final Request request = new Request.Builder()
			.url(baseUrl + "/wealth")
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Leaderboard POST failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	public void postPrestige(String baseUrl, String apiKey, String playerName, IntConsumer callback)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("player_name", playerName);

		final Request request = new Request.Builder()
			.url(baseUrl + "/prestige")
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		attemptPostPrestige(request, callback, 0);
	}

	private void attemptPostPrestige(Request request, IntConsumer callback, int attempt)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Prestige POST failed (attempt {}): {}", attempt + 1, e.getMessage());
				scheduleRetry(request, callback, attempt);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					final okhttp3.ResponseBody respBody = response.body();
					if (!response.isSuccessful() || respBody == null)
					{
						log.debug("Prestige POST non-success (attempt {}): code={}",
							attempt + 1, response.code());
						scheduleRetry(request, callback, attempt);
						return;
					}
					final JsonObject json = gson.fromJson(respBody.string(), JsonObject.class);
					final int prestige = json.get("prestige").getAsInt();
					SwingUtilities.invokeLater(() -> callback.accept(prestige));
				}
				catch (Exception e)
				{
					log.debug("Prestige POST parse failed (attempt {}): {}", attempt + 1, e.getMessage());
					scheduleRetry(request, callback, attempt);
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private void scheduleRetry(Request request, IntConsumer callback, int attempt)
	{
		if (attempt >= PRESTIGE_RETRY_DELAYS_SEC.length)
		{
			log.warn("Prestige POST gave up after {} attempts", attempt + 1);
			return;
		}
		final long delaySec = PRESTIGE_RETRY_DELAYS_SEC[attempt];
		log.debug("Retrying prestige POST in {}s (attempt {} of {})",
			delaySec, attempt + 2, PRESTIGE_RETRY_DELAYS_SEC.length + 1);
		retryExecutor.schedule(
			() -> attemptPostPrestige(request, callback, attempt + 1),
			delaySec, TimeUnit.SECONDS);
	}

	public void fetchLeaderboard(String baseUrl, String playerName, int page, Consumer<LeaderboardResponse> callback)
	{
		String url = baseUrl + "/leaderboard?page=" + page;
		if (playerName != null && !playerName.isEmpty())
		{
			url += "&player=" + playerName;
		}

		final Request request = new Request.Builder()
			.url(url)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Leaderboard GET failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					final okhttp3.ResponseBody body = response.body();
					if (!response.isSuccessful() || body == null)
					{
						return;
					}
					final String json = body.string();
					final LeaderboardResponse data = gson.fromJson(json, LeaderboardResponse.class);
					if (data != null)
					{
						if (data.getLeaderboard() == null)
						{
							data.setLeaderboard(Collections.emptyList());
						}
						SwingUtilities.invokeLater(() -> callback.accept(data));
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}

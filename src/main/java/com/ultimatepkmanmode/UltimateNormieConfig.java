package com.ultimatepkmanmode;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("ultimatenormie")
public interface UltimateNormieConfig extends Config
{
	@ConfigItem(
		keyName = "leaderboardEnabled",
		name = "Enable Leaderboard",
		description = "Post your wealth to the global UNM leaderboard",
		position = 1
	)
	default boolean leaderboardEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "leaderboardApiKey",
		name = "Leaderboard API Key",
		description = "API key for the UNM leaderboard server",
		secret = true,
		position = 2
	)
	default String leaderboardApiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "leaderboardUrl",
		name = "Leaderboard URL",
		description = "Base URL of the UNM leaderboard server",
		position = 3
	)
	default String leaderboardUrl()
	{
		return "https://your-app.up.railway.app";
	}
}

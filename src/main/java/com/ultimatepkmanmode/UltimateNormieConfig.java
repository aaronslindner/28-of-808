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

}

package com.ultimatepkmanmode;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("ultimatepker")
public interface UltimatePkerConfig extends Config
{
	@ConfigItem(
		keyName = "disableBanking",
		name = "Disable Banking",
		description = "Block all bank interactions",
		position = 1
	)
	default boolean disableBanking()
	{
		return true;
	}

	@ConfigItem(
		keyName = "limitPercent",
		name = "Limit Percent",
		description = "Maximum allowed deviation from market/offer value (0 to disable)",
		position = 2
	)
	@Range(min = 0, max = 100)
	default int limitPercent()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "absoluteCapGp",
		name = "Absolute Cap (GP)",
		description = "Maximum allowed GP deviation regardless of percentage",
		position = 3
	)
	default int absoluteCapGp()
	{
		return 5_000_000;
	}
}

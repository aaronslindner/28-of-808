package com.ultimatepkmanmode;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("ultimatenormie")
public interface UltimateNormieConfig extends Config
{
	@ConfigSection(
		name = "Grand Exchange",
		description = "Grand Exchange restrictions",
		position = 10
	)
	String geSection = "geSection";

	@ConfigSection(
		name = "Trading",
		description = "Player-to-player trading restrictions",
		position = 20
	)
	String tradeSection = "tradeSection";

	@ConfigItem(
		keyName = "leaderboardEnabled",
		name = "Opt in/Enable Leaderboard",
		description = "Post your wealth to the global UNM leaderboard",
		position = 1
	)
	default boolean leaderboardEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowBank",
		name = "Allow Bank",
		description = "If enabled, all banking is unrestricted (banks, deposit boxes, bankers, etc.)",
		position = 2
	)
	default boolean allowBank()
	{
		return false;
	}

	@ConfigItem(
		keyName = "allowGe",
		name = "Allow GE",
		description = "If enabled, the Grand Exchange may be used. If disabled, GE is fully blocked.",
		section = geSection,
		position = 1
	)
	default boolean allowGe()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enforceGeValidation",
		name = "Enforce ±10% price validation",
		description = "When 'Allow GE' is enabled, restrict offers to ±10% of market price. Has no effect when 'Allow GE' is disabled.",
		section = geSection,
		position = 2
	)
	default boolean enforceGeValidation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowTrade",
		name = "Allow Trade",
		description = "If enabled, player-to-player trading is allowed. If disabled, trading is fully blocked.",
		section = tradeSection,
		position = 1
	)
	default boolean allowTrade()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enforceTradeValidation",
		name = "Enforce ±10% value validation",
		description = "When 'Allow Trade' is enabled, restrict trades to ±10% value match. Has no effect when 'Allow Trade' is disabled.",
		section = tradeSection,
		position = 2
	)
	default boolean enforceTradeValidation()
	{
		return true;
	}

}

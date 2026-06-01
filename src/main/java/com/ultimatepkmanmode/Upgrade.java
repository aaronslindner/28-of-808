package com.ultimatepkmanmode;

public enum Upgrade
{
	// ============================================================================
	// !!! TEST MODE: all upgrade costs reduced to 1,000 gp for in-game smoke-testing.
	// REVERT before shipping. Production costs are commented to the right of each line.
	// ============================================================================

	// Standalone
	BANKING(
		"Banking Unlock",
		1_000L, // PROD: 1_000_000_000L
		null,
		Category.BANKING,
		"Removes all banking restrictions for this life."),

	// GE chain: USE -> QUARTER -> REMOVAL
	GE_USE(
		"GE Use",
		1_000L, // PROD: 1_000_000L
		null,
		Category.GE,
		"Unlocks Grand Exchange access. Submitted offers must still match market price within \u00b110%."),

	GE_QUARTER(
		"GE \u00b125%",
		1_000L, // PROD: 5_000_000L
		GE_USE,
		Category.GE,
		"Loosens the GE price tolerance from \u00b110% to \u00b125%."),

	GE_REMOVAL(
		"GE Removal",
		1_000L, // PROD: 50_000_000L
		GE_QUARTER,
		Category.GE,
		"Removes GE price enforcement entirely. Any offer is allowed."),

	// Trade chain: USE -> QUARTER -> REMOVAL
	TRADE_USE(
		"Trade Use",
		1_000L, // PROD: 1_000_000L
		null,
		Category.TRADE,
		"Unlocks player trading. Trade values must still match within \u00b110%."),

	TRADE_QUARTER(
		"Trade \u00b125%",
		1_000L, // PROD: 1_000_000L
		TRADE_USE,
		Category.TRADE,
		"Loosens the trade value tolerance from \u00b110% to \u00b125%."),

	TRADE_REMOVAL(
		"Trade Removal",
		1_000L, // PROD: 10_000_000L
		TRADE_QUARTER,
		Category.TRADE,
		"Removes trade value enforcement entirely. Any trade is allowed.");

	public enum Category
	{
		BANKING("Banking"),
		GE("Grand Exchange"),
		TRADE("Trading");

		private final String displayName;

		Category(String displayName)
		{
			this.displayName = displayName;
		}

		public String getDisplayName()
		{
			return displayName;
		}
	}

	private final String displayName;
	private final long cost;
	private final Upgrade parent;
	private final Category category;
	private final String description;

	Upgrade(String displayName, long cost, Upgrade parent, Category category, String description)
	{
		this.displayName = displayName;
		this.cost = cost;
		this.parent = parent;
		this.category = category;
		this.description = description;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public long getCost()
	{
		return cost;
	}

	public Upgrade getParent()
	{
		return parent;
	}

	public Category getCategory()
	{
		return category;
	}

	public String getDescription()
	{
		return description;
	}
}

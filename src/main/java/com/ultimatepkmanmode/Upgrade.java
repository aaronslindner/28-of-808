package com.ultimatepkmanmode;

public enum Upgrade
{
	// ============================================================================
	// !!! TEST MODE: all upgrade costs reduced to 1,000 gp for in-game smoke-testing.
	// REVERT before shipping. Production costs are commented to the right of each line.
	// ============================================================================

	// Standalone
	BANKING(
		"Bank Unlock",
		1_000_000L, // PROD: 1_000_000_000L
		null,
		Category.BANKING,
		"Removes all banking restrictions for this life."),

	// GE chain: UNLOCK -> QUARTER -> REMOVAL
	GE_USE(
		"GE Unlock",
		100_000L, // PROD: 1_000_000L
		null,
		Category.GE,
		"Unlocks Grand Exchange access. Submitted offers must still match market price within \u00b110%."),

	GE_QUARTER(
		"GE \u00b125%",
		500_000L, // PROD: 5_000_000L
		GE_USE,
		Category.GE,
		"Loosens the GE price restriction from \u00b110% to \u00b125%."),

	GE_REMOVAL(
		"Restriction Removal",
		1_000_000L, // PROD: 50_000_000L
		GE_QUARTER,
		Category.GE,
		"Removes the GE price restriction entirely."),

	// Trade chain: UNLOCK -> QUARTER -> REMOVAL
	TRADE_USE(
		"Trade Unlock",
		1_000L, // PROD: 1_000_000L
		null,
		Category.TRADE,
		"Unlocks player trading. Trade values must still match within \u00b110%."),

	TRADE_QUARTER(
		"Trade \u00b125%",
		5_000L, // PROD: 1_000_000L
		TRADE_USE,
		Category.TRADE,
		"Loosens the trade value restriction from \u00b110% to \u00b125%."),

	TRADE_REMOVAL(
		"Restriction Removal",
		10_000L, // PROD: 10_000_000L
		TRADE_QUARTER,
		Category.TRADE,
		"Removes the trade value restriction entirely."),

	// Consumable (one-shot) passes. Base cost shown here; the actual price
	// scales +1K per purchase this life (managed by UpgradeManager). These do NOT
	// grant a permanent unlock \u2014 paying for one grants a single action charge.
	DEPOSIT_PASS(
		"Deposit Pass",
		1_000L,
		null,
		Category.BANKING,
		true,
		"Single-use deposit pass. Only allows Deposit-1. Costs 1K more each time you buy one. All passes and the price tier reset on death."),

	WITHDRAWAL_PASS(
		"Withdrawal Pass",
		1_000L,
		null,
		Category.BANKING,
		true,
		"Single-use withdrawal pass. Only allows Withdraw-1. Costs 1K more each time you buy one. All passes and the price tier reset on death."),

	GE_PASS(
		"GE Pass",
		1_000L,
		null,
		Category.GE,
		true,
		"Single-use Grand Exchange pass. Opens a temporary GE session; access is revoked when you close the GE. Costs 1K more each time you buy one. Resets on death."),

	PURGATORY_UNLOCK(
		"Purgatory Unlock",
		1_000L,
		null,
		Category.BANKING,
		true,
		"Unlocks Purgatory to retrieve items deposited in the UNM bank before death. Cost equals total spent on deposit/withdrawal passes in the prior life. Only available if Purgatory has items.");

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
	private final boolean consumable;
	private final String description;

	Upgrade(String displayName, long cost, Upgrade parent, Category category, String description)
	{
		this(displayName, cost, parent, category, false, description);
	}

	Upgrade(String displayName, long cost, Upgrade parent, Category category, boolean consumable, String description)
	{
		this.displayName = displayName;
		this.cost = cost;
		this.parent = parent;
		this.category = category;
		this.consumable = consumable;
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

	/**
	 * Whether this upgrade can be toggled active/inactive once unlocked.
	 * Only gateway upgrades (Banking, GE, Trade) are toggleable; chain upgrades
	 * automatically apply via their parent, and consumables are never \"unlocked\".
	 */
	public boolean isToggleable()
	{
		return parent == null && !consumable;
	}

	/**
	 * One-shot pass rather than a permanent unlock. Paying its (escalating) cost grants
	 * a single charge usable for one bank action, then the price tier increases.
	 */
	public boolean isConsumable()
	{
		return consumable;
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

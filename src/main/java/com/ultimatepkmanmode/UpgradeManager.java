package com.ultimatepkmanmode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Owns the player's Upgrade Mode state: per-life active upgrades, per-upgrade
 * incineration progress, and the currently-selected saving goal. State is persisted
 * per RuneScape profile via {@link ConfigManager}, and is hard-wiped on death.
 */
@Slf4j
@Singleton
public class UpgradeManager
{
	private static final String CONFIG_GROUP = "unmupgrade";
	private static final String KEY_GOAL = "selectedGoal";
	private static final String KEY_PROGRESS_PREFIX = "progress.";
	private static final String KEY_ACTIVE_PREFIX = "active.";
	private static final String KEY_UNLOCKED_PREFIX = "unlocked.";
	private static final String KEY_CHARGES_PREFIX = "charges.";
	private static final String KEY_PURCHASES_PREFIX = "purchases.";
	private static final String KEY_PURGATORY_DATA = "purgatoryData";
	private static final String KEY_PURGATORY_UNLOCK_COST = "purgatoryUnlockCost";

	@Inject
	private ConfigManager configManager;

	// 'Unlocked' = paid for this life. Persists until death.
	// 'Active'   = currently in effect; subset of unlocked. Toggleable for gateway upgrades.
	private final EnumSet<Upgrade> unlockedUpgrades = EnumSet.noneOf(Upgrade.class);
	private final EnumSet<Upgrade> activeUpgrades = EnumSet.noneOf(Upgrade.class);
	private final EnumMap<Upgrade, Long> progress = new EnumMap<>(Upgrade.class);
	// Consumable per-life state (keyed by Upgrade entries flagged isConsumable()):
	//   charges[u]   = ready-to-use single-action passes (consumed on a deposit/withdraw)
	//   purchases[u] = how many times this consumable has been bought this life. The next
	//                  purchase price is u.getCost() + 1000 * purchases[u].
	private final EnumMap<Upgrade, Integer> consumableCharges = new EnumMap<>(Upgrade.class);
	private final EnumMap<Upgrade, Integer> consumablePurchases = new EnumMap<>(Upgrade.class);
	// Set to true while a temporary GE pass session is open; revoked when GE is closed.
	private boolean temporaryGeSession = false;
	private Upgrade selectedGoal = null;
	// UNM bank: items deposited via Deposit Pass that can be withdrawn via Withdrawal Pass.
	// Maps item ID → quantity. Persists until death.
	private final Map<Integer, Integer> unmBank = new HashMap<>();
	// Cached item names for UNM bank items (populated on client thread, used in UI).
	private final Map<Integer, String> unmBankItemNames = new HashMap<>();
	// Purgatory: items that were in UNM bank at death, requiring unlock fee to retrieve.
	// Maps item ID → quantity. Cleared on subsequent deaths.
	private final Map<Integer, Integer> purgatory = new HashMap<>();
	// Cached item names for Purgatory items.
	private final Map<Integer, String> purgatoryItemNames = new HashMap<>();
	// Total gp spent on deposit/withdrawal passes this life (for Purgatory unlock cost).
	private long passSpendingThisLife = 0L;
	// Unlock cost for Purgatory (saved from prior life's pass spending).
	private long purgatoryUnlockCost = 0L;

	private Runnable onChange;

	public void setOnChange(Runnable onChange)
	{
		this.onChange = onChange;
	}

	private void fireChange()
	{
		if (onChange != null)
		{
			SwingUtilities.invokeLater(onChange);
		}
	}

	/** Loads state from per-RSN config. Call after the player has logged in. */
	public void load()
	{
		final String goal = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_GOAL);
		selectedGoal = (goal != null && !goal.isEmpty()) ? safeValueOf(goal) : null;

		progress.clear();
		for (Upgrade u : Upgrade.values())
		{
			final String s = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PROGRESS_PREFIX + u.name());
			long v = 0L;
			if (s != null)
			{
				try
				{
					v = Long.parseLong(s);
				}
				catch (NumberFormatException e)
				{
					v = 0L;
				}
			}
			progress.put(u, v);
		}

		// Unlocked set: explicit unlocked.* key, with a fallback for legacy configs where
		// only active.* was stored (any then-active upgrade is necessarily unlocked).
		unlockedUpgrades.clear();
		for (Upgrade u : Upgrade.values())
		{
			final String unlockedStr = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_UNLOCKED_PREFIX + u.name());
			final String activeStr = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_ACTIVE_PREFIX + u.name());
			if ("true".equalsIgnoreCase(unlockedStr) || "true".equalsIgnoreCase(activeStr))
			{
				unlockedUpgrades.add(u);
			}
		}

		activeUpgrades.clear();
		for (Upgrade u : Upgrade.values())
		{
			if (!unlockedUpgrades.contains(u))
			{
				continue;
			}
			final String s = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_ACTIVE_PREFIX + u.name());
			if ("true".equalsIgnoreCase(s))
			{
				activeUpgrades.add(u);
			}
		}

		consumableCharges.clear();
		consumablePurchases.clear();
		for (Upgrade u : Upgrade.values())
		{
			if (!u.isConsumable())
			{
				continue;
			}
			consumableCharges.put(u, parseIntOrZero(configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_CHARGES_PREFIX + u.name())));
			consumablePurchases.put(u, parseIntOrZero(configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PURCHASES_PREFIX + u.name())));
		}

		// Seed one GE_PASS charge only for a genuinely new profile (key was never written).
		final String gePassChargesKey = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_CHARGES_PREFIX + Upgrade.GE_PASS.name());
		if (gePassChargesKey == null)
		{
			consumableCharges.put(Upgrade.GE_PASS, 1);
		}

		// Load UNM bank: keys are "unmBank.<itemId>" with quantity as value.
		unmBank.clear();
		// ConfigManager doesn't have a direct "list keys by prefix" method, so we iterate
		// over a reasonable range of possible item IDs. This is inefficient but safe; in practice
		// the UNM bank will be small. A better approach would be to store a single
		// JSON-encoded string, but that requires Jackson/GSON.
		// For now, we'll use a simpler approach: store a single comma-separated list of
		// "itemId:quantity" pairs under a single key.
		final String unmBankData = configManager.getRSProfileConfiguration(CONFIG_GROUP, "unmBankData");
		if (unmBankData != null && !unmBankData.isEmpty())
		{
			try
			{
				final String[] pairs = unmBankData.split(",");
				for (String pair : pairs)
				{
					final String[] parts = pair.split(":");
					if (parts.length == 2)
					{
						final int itemId = Integer.parseInt(parts[0]);
						final int qty = Integer.parseInt(parts[1]);
						if (qty > 0)
						{
							unmBank.put(itemId, qty);
						}
					}
				}
			}
			catch (Exception e)
			{
				// Corrupt data; clear the bank and save to prevent future crashes.
				unmBank.clear();
				configManager.setRSProfileConfiguration(CONFIG_GROUP, "unmBankData", "");
			}
		}

		// Load Purgatory data
		purgatory.clear();
		final String purgatoryData = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PURGATORY_DATA);
		if (purgatoryData != null && !purgatoryData.isEmpty())
		{
			try
			{
				final String[] pairs = purgatoryData.split(",");
				for (String pair : pairs)
				{
					final String[] parts = pair.split(":");
					if (parts.length == 2)
					{
						final int itemId = Integer.parseInt(parts[0]);
						final int qty = Integer.parseInt(parts[1]);
						if (qty > 0)
						{
							purgatory.put(itemId, qty);
						}
					}
				}
			}
			catch (Exception e)
			{
				// Corrupt data; clear Purgatory
				purgatory.clear();
				configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PURGATORY_DATA, "");
			}
		}

		// Load Purgatory unlock cost
		final String unlockCostStr = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_PURGATORY_UNLOCK_COST);
		purgatoryUnlockCost = parseLongOrZero(unlockCostStr);

		fireChange();
	}

	private static int parseIntOrZero(String s)
	{
		if (s == null || s.isEmpty())
		{
			return 0;
		}
		try { return Integer.parseInt(s); }
		catch (NumberFormatException e) { return 0; }
	}

	private static long parseLongOrZero(String s)
	{
		if (s == null || s.isEmpty())
		{
			return 0L;
		}
		try { return Long.parseLong(s); }
		catch (NumberFormatException e) { return 0L; }
	}

	/** Persists current state to per-RSN config. */
	public void save()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_GOAL,
			selectedGoal != null ? selectedGoal.name() : "");
		for (Upgrade u : Upgrade.values())
		{
			configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PROGRESS_PREFIX + u.name(),
				String.valueOf(getProgress(u)));
			configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_ACTIVE_PREFIX + u.name(),
				String.valueOf(activeUpgrades.contains(u)));
			configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_UNLOCKED_PREFIX + u.name(),
				String.valueOf(unlockedUpgrades.contains(u)));
			if (u.isConsumable())
			{
				configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_CHARGES_PREFIX + u.name(),
					String.valueOf(getConsumableCharges(u)));
				configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PURCHASES_PREFIX + u.name(),
					String.valueOf(getConsumablePurchases(u)));
			}
		}

		// Save UNM bank as a single comma-separated "itemId:quantity" string.
		if (unmBank.isEmpty())
		{
			configManager.setRSProfileConfiguration(CONFIG_GROUP, "unmBankData", "");
		}
		else
		{
			final StringBuilder sb = new StringBuilder();
			for (Map.Entry<Integer, Integer> entry : unmBank.entrySet())
			{
				if (sb.length() > 0)
				{
					sb.append(",");
				}
				sb.append(entry.getKey()).append(":").append(entry.getValue());
			}
			configManager.setRSProfileConfiguration(CONFIG_GROUP, "unmBankData", sb.toString());
		}

		// Save Purgatory data
		if (purgatory.isEmpty())
		{
			configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PURGATORY_DATA, "");
		}
		else
		{
			final StringBuilder sb = new StringBuilder();
			for (Map.Entry<Integer, Integer> entry : purgatory.entrySet())
			{
				if (sb.length() > 0)
				{
					sb.append(",");
				}
				sb.append(entry.getKey()).append(":").append(entry.getValue());
			}
			configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PURGATORY_DATA, sb.toString());
		}

		// Save Purgatory unlock cost
		configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_PURGATORY_UNLOCK_COST, String.valueOf(purgatoryUnlockCost));
	}

	/** Wipes ALL upgrade state for the current life: progress, active set, goal, consumables. */
	public void hardWipe()
	{
		activeUpgrades.clear();
		unlockedUpgrades.clear();
		progress.replaceAll((k, v) -> 0L);
		consumableCharges.clear();
		consumablePurchases.clear();

		// Handle Purgatory: if Purgatory has items, clear them (lost forever on any death)
		if (!purgatory.isEmpty())
		{
			purgatory.clear();
			purgatoryItemNames.clear();
			purgatoryUnlockCost = 0L;
		}

		// If UNM bank has items, move them to Purgatory
		if (!unmBank.isEmpty())
		{
			purgatory.putAll(unmBank);
			purgatoryItemNames.putAll(unmBankItemNames);
			// Save pass spending as unlock cost (minimum 1 gp since OSRS doesn't allow 0 gp incineration)
			purgatoryUnlockCost = Math.max(1L, passSpendingThisLife);
		}
		unmBank.clear();
		unmBankItemNames.clear();

		// Reset pass spending tracker for the new life
		passSpendingThisLife = 0L;

		// Grant the default 1 GE pass on each new life.
		consumableCharges.put(Upgrade.GE_PASS, 1);
		temporaryGeSession = false;
		selectedGoal = null;
		save();
		fireChange();
	}

	public Set<Upgrade> getActiveUpgrades()
	{
		return Collections.unmodifiableSet(activeUpgrades);
	}

	public Set<Upgrade> getUnlockedUpgrades()
	{
		return Collections.unmodifiableSet(unlockedUpgrades);
	}

	public int getActiveCount()
	{
		return activeUpgrades.size();
	}

	public int getUnlockedCount()
	{
		return unlockedUpgrades.size();
	}

	public boolean isUnlocked(Upgrade u)
	{
		return u != null && unlockedUpgrades.contains(u);
	}

	/**
	 * Toggles a gateway upgrade on or off. Only effective for upgrades that are both
	 * (1) currently unlocked and (2) declared toggleable on the {@link Upgrade} enum.
	 */
	public void setActive(Upgrade u, boolean active)
	{
		if (u == null || !u.isToggleable() || !unlockedUpgrades.contains(u))
		{
			return;
		}
		final boolean changed = active ? activeUpgrades.add(u) : activeUpgrades.remove(u);
		if (changed)
		{
			save();
			fireChange();
		}
	}

	public Upgrade getSelectedGoal()
	{
		return selectedGoal;
	}

	public long getProgress(Upgrade u)
	{
		return progress.getOrDefault(u, 0L);
	}

	public boolean isActive(Upgrade u)
	{
		return u != null && activeUpgrades.contains(u);
	}

	// -------- Consumable / free-pass state --------

	public int getConsumableCharges(Upgrade u)
	{
		return u == null ? 0 : consumableCharges.getOrDefault(u, 0);
	}

	public int getConsumablePurchases(Upgrade u)
	{
		return u == null ? 0 : consumablePurchases.getOrDefault(u, 0);
	}

	/**
	 * Current price for buying the next charge of the given consumable. Returns
	 * {@code base + 1000 * purchases}, capped at {@link Long#MAX_VALUE} on overflow.
	 * For PURGATORY_UNLOCK, returns the saved unlock cost from the prior life (minimum 1 gp).
	 */
	public long getCurrentCost(Upgrade u)
	{
		if (u == null)
		{
			return 0L;
		}
		if (!u.isConsumable())
		{
			return u.getCost();
		}
		// PURGATORY_UNLOCK has a special cost: the total spent on passes in the prior life
		if (u == Upgrade.PURGATORY_UNLOCK)
		{
			return getPurgatoryUnlockCost();
		}
		final long base = u.getCost();
		final int purchases = getConsumablePurchases(u);
		final long increment = 1000L * purchases;
		if (base > Long.MAX_VALUE - increment)
		{
			return Long.MAX_VALUE;
		}
		return base + increment;
	}

	/** Consume one ready charge of the given consumable. Returns false if none available. */
	public boolean consumeCharge(Upgrade u)
	{
		if (u == null || !u.isConsumable())
		{
			return false;
		}
		final int have = getConsumableCharges(u);
		if (have <= 0)
		{
			return false;
		}
		consumableCharges.put(u, have - 1);
		save();
		fireChange();
		return true;
	}

	/**
	 * Opens a temporary GE session by consuming one GE_PASS charge.
	 * Returns false if no charges are available.
	 */
	public boolean consumeGePassCharge()
	{
		if (!consumeCharge(Upgrade.GE_PASS))
		{
			return false;
		}
		temporaryGeSession = true;
		fireChange();
		return true;
	}

	/**
	 * Called when the GE interface is closed. Revokes any temporary GE session
	 * (from a free pass or a GE_PASS charge). Does nothing if no session is open.
	 */
	public void revokeTemporaryGe()
	{
		if (temporaryGeSession)
		{
			temporaryGeSession = false;
			fireChange();
		}
	}

	public boolean hasTemporaryGeSession()
	{
		return temporaryGeSession;
	}

	// -------- UNM bank (Deposit/Withdrawal Pass) --------

	/** Returns an immutable copy of the UNM bank map (item ID → quantity). */
	public Map<Integer, Integer> getUnmBank()
	{
		return new HashMap<>(unmBank);
	}

	/** Returns the quantity of the given item in the UNM bank, or 0 if not present. */
	public int getUnmBankQuantity(int itemId)
	{
		return unmBank.getOrDefault(itemId, 0);
	}

	/** Returns true if the given item exists in the UNM bank with quantity > 0. */
	public boolean hasInUnmBank(int itemId)
	{
		return unmBank.containsKey(itemId) && unmBank.get(itemId) > 0;
	}

	/** Adds the given quantity of the item to the UNM bank. Saves and fires change. */
	public void addToUnmBank(int itemId, int quantity)
	{
		if (quantity <= 0)
		{
			return;
		}
		unmBank.merge(itemId, quantity, (a, b) -> a + b);
		save();
		fireChange();
	}

	/** Caches the item name for display in the UI. Call this on the client thread. */
	public void setUnmBankItemName(int itemId, String name)
	{
		if (name != null && !name.isEmpty())
		{
			unmBankItemNames.put(itemId, name);
		}
	}

	/** Returns the cached item name, or null if not cached. */
	public String getUnmBankItemName(int itemId)
	{
		return unmBankItemNames.get(itemId);
	}

	/** Removes the given quantity of the item from the UNM bank. Returns true if successful. */
	public boolean removeFromUnmBank(int itemId, int quantity)
	{
		if (quantity <= 0)
		{
			return false;
		}
		final int current = unmBank.getOrDefault(itemId, 0);
		if (current < quantity)
		{
			return false;
		}
		if (current == quantity)
		{
			unmBank.remove(itemId);
		}
		else
		{
			unmBank.put(itemId, current - quantity);
		}
		save();
		fireChange();
		return true;
	}

	// -------- Purgatory --------

	/** Returns an immutable copy of the Purgatory map (item ID → quantity). */
	public Map<Integer, Integer> getPurgatory()
	{
		return new HashMap<>(purgatory);
	}

	/** Returns the quantity of the given item in Purgatory, or 0 if not present. */
	public int getPurgatoryQuantity(int itemId)
	{
		return purgatory.getOrDefault(itemId, 0);
	}

	/** Returns true if the given item exists in Purgatory with quantity > 0. */
	public boolean hasInPurgatory(int itemId)
	{
		return purgatory.containsKey(itemId) && purgatory.get(itemId) > 0;
	}

	/** Returns true if Purgatory has any items. */
	public boolean hasPurgatoryItems()
	{
		return !purgatory.isEmpty();
	}

	/** Returns the cached item name for Purgatory, or null if not cached. */
	public String getPurgatoryItemName(int itemId)
	{
		return purgatoryItemNames.get(itemId);
	}

	/** Returns the unlock cost for Purgatory (total spent on passes in prior life). */
	public long getPurgatoryUnlockCost()
	{
		// If Purgatory has items, ensure minimum cost is 1 gp (OSRS doesn't allow 0 gp incineration)
		return purgatory.isEmpty() ? 0L : Math.max(1L, purgatoryUnlockCost);
	}

	/** Unlocks Purgatory by moving all items back to UNM bank. Returns true if successful. */
	public boolean unlockPurgatory()
	{
		if (purgatory.isEmpty())
		{
			return false;
		}
		// Move all items from Purgatory to UNM bank
		for (Map.Entry<Integer, Integer> entry : purgatory.entrySet())
		{
			final int itemId = entry.getKey();
			final int qty = entry.getValue();
			unmBank.merge(itemId, qty, (a, b) -> a + b);
		}
		// Copy item names
		unmBankItemNames.putAll(purgatoryItemNames);
		// Clear Purgatory
		purgatory.clear();
		purgatoryItemNames.clear();
		purgatoryUnlockCost = 0L;
		save();
		fireChange();
		return true;
	}

	/**
	 * Whether the player can mark the given upgrade as the current saving goal.
	 * Consumables are always selectable (they're re-purchasable). Permanent upgrades
	 * require: not already active, and parent (if any) is active.
	 * PURGATORY_UNLOCK is only selectable if Purgatory has items.
	 */
	public boolean canSelectAsGoal(Upgrade u)
	{
		if (u == null)
		{
			return true; // clearing goal is always allowed
		}
		if (u == Upgrade.PURGATORY_UNLOCK)
		{
			return hasPurgatoryItems();
		}
		if (u.isConsumable())
		{
			return true;
		}
		if (isActive(u))
		{
			return false;
		}
		return u.getParent() == null || isActive(u.getParent());
	}

	/** Returns true if the goal change was accepted. */
	public boolean selectGoal(Upgrade u)
	{
		if (u != null && !canSelectAsGoal(u))
		{
			return false;
		}
		selectedGoal = u;
		save();
		fireChange();
		return true;
	}

	/**
	 * Applies incinerated coins to the current saving goal. Caps progress at the goal's
	 * cost; any overflow is discarded (so the player isn't penalised for over-incinerating).
	 *
	 * @param burned amount of coins destroyed
	 * @return result describing how much was credited and whether the upgrade activated
	 */
	public ApplyResult applyBurned(long burned)
	{
		if (selectedGoal == null || burned <= 0)
		{
			return new ApplyResult(0, null);
		}
		final Upgrade goal = selectedGoal;
		final long curr = getProgress(goal);
		final long cost = getCurrentCost(goal);
		final long room = Math.max(0, cost - curr);
		final long applied = Math.min(burned, room);

		Upgrade activated = null;
		if (curr + applied >= cost)
		{
			if (goal == Upgrade.PURGATORY_UNLOCK)
			{
				// PURGATORY_UNLOCK doesn't grant a charge; it unlocks Purgatory directly
				unlockPurgatory();
			}
			else if (goal.isConsumable())
			{
				// Consumables grant a one-shot charge instead of a permanent unlock,
				// and advance the per-life purchase counter (+1K next price).
				consumableCharges.put(goal, getConsumableCharges(goal) + 1);
				consumablePurchases.put(goal, getConsumablePurchases(goal) + 1);
				// Track spending for deposit/withdrawal passes (for Purgatory unlock cost).
				if (goal == Upgrade.DEPOSIT_PASS || goal == Upgrade.WITHDRAWAL_PASS)
				{
					passSpendingThisLife += cost;
				}
			}
			else
			{
				unlockedUpgrades.add(goal);
				activeUpgrades.add(goal);
			}
			progress.put(goal, 0L);
			selectedGoal = null;
			activated = goal;
		}
		else
		{
			progress.put(goal, curr + applied);
		}
		save();
		fireChange();
		return new ApplyResult(applied, activated);
	}

	private Upgrade safeValueOf(String name)
	{
		try
		{
			return Upgrade.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	// -- Convenience accessors used by the plugin's enforcement gates --

	public boolean isBankingUnlocked()
	{
		return isActive(Upgrade.BANKING);
	}

	public boolean isGeUnlocked()
	{
		return isActive(Upgrade.GE_USE) || temporaryGeSession
			|| getConsumableCharges(Upgrade.GE_PASS) > 0;
	}

	public boolean isTradeUnlocked()
	{
		return isActive(Upgrade.TRADE_USE);
	}

	public boolean isGeEnforcementOff()
	{
		return isActive(Upgrade.GE_REMOVAL);
	}

	public boolean isTradeEnforcementOff()
	{
		return isActive(Upgrade.TRADE_REMOVAL);
	}

	/** Tolerance (percent) used by GE price validation when enforcement is on. */
	public int getGeTolerancePct()
	{
		return isActive(Upgrade.GE_QUARTER) ? 25 : 10;
	}

	/** Tolerance (percent) used by trade value validation when enforcement is on. */
	public int getTradeTolerancePct()
	{
		return isActive(Upgrade.TRADE_QUARTER) ? 25 : 10;
	}

	public static final class ApplyResult
	{
		public final long applied;
		public final Upgrade activatedUpgrade;

		public ApplyResult(long applied, Upgrade activatedUpgrade)
		{
			this.applied = applied;
			this.activatedUpgrade = activatedUpgrade;
		}

		public boolean wasActivated()
		{
			return activatedUpgrade != null;
		}
	}
}

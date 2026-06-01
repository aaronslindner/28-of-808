package com.ultimatepkmanmode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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

	@Inject
	private ConfigManager configManager;

	private final EnumSet<Upgrade> activeUpgrades = EnumSet.noneOf(Upgrade.class);
	private final EnumMap<Upgrade, Long> progress = new EnumMap<>(Upgrade.class);
	private Upgrade selectedGoal = null;

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

		activeUpgrades.clear();
		for (Upgrade u : Upgrade.values())
		{
			final String s = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_ACTIVE_PREFIX + u.name());
			if ("true".equalsIgnoreCase(s))
			{
				activeUpgrades.add(u);
			}
		}

		fireChange();
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
		}
	}

	/** Wipes ALL upgrade state for the current life: progress, active set, goal. */
	public void hardWipe()
	{
		activeUpgrades.clear();
		progress.replaceAll((k, v) -> 0L);
		selectedGoal = null;
		save();
		fireChange();
	}

	public Set<Upgrade> getActiveUpgrades()
	{
		return Collections.unmodifiableSet(activeUpgrades);
	}

	public int getActiveCount()
	{
		return activeUpgrades.size();
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

	/**
	 * Whether the player can mark the given upgrade as the current saving goal.
	 * Requires: not already active, and parent (if any) is active.
	 */
	public boolean canSelectAsGoal(Upgrade u)
	{
		if (u == null)
		{
			return true; // clearing goal is always allowed
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
		final long cost = goal.getCost();
		final long room = Math.max(0, cost - curr);
		final long applied = Math.min(burned, room);

		Upgrade activated = null;
		if (curr + applied >= cost)
		{
			// Activate the upgrade and clear its progress + the goal slot.
			activeUpgrades.add(goal);
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
		return isActive(Upgrade.GE_USE);
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

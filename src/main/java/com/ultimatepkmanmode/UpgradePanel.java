package com.ultimatepkmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel for the Upgrade Mode UI:
 *   - Active upgrades for this life
 *   - Current saving goal + progress bar
 *   - Available upgrades grouped by category with [Set] / [Active] / [Locked] / [Goal] state
 */
@Slf4j
public class UpgradePanel extends PluginPanel
{
	private static final Color ACTIVE_GREEN  = new Color(80, 180, 80);
	private static final Color INACTIVE_RED  = new Color(200, 80, 80);
	private static final Color GOAL_BLUE     = new Color(80, 130, 200);
	private static final Color LOCKED_GRAY   = new Color(110, 110, 110);

	private final UpgradeManager mgr;
	private boolean rulesCollapsed = false;

	public UpgradePanel(UpgradeManager mgr)
	{
		super(true);
		this.mgr = mgr;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		rebuild();
	}

	public void rebuild()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::rebuild);
			return;
		}

		try
		{
			removeAll();

			final JLabel header = new JLabel("UNM Upgrades");
			header.setFont(FontManager.getRunescapeBoldFont());
			header.setForeground(Color.WHITE);
			header.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
			add(header, BorderLayout.NORTH);

			final JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setBackground(ColorScheme.DARK_GRAY_COLOR);
			body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

			body.add(buildRulesSection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));
			body.add(buildActiveSection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));
			body.add(buildConsumablesSection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));
			body.add(buildUnmBankSection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));
			body.add(buildPurgatorySection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));
			body.add(buildGoalSection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));
			body.add(buildAvailableSection());
			body.add(Box.createVerticalStrut(8));
			body.add(separator());
			body.add(Box.createVerticalStrut(8));

			add(body, BorderLayout.CENTER);
			revalidate();
			repaint();
		}
		catch (Exception e)
		{
			removeAll();
			final JLabel error = new JLabel("Error: " + e.getMessage());
			error.setForeground(Color.RED);
			add(error, BorderLayout.CENTER);
			revalidate();
			repaint();
			log.error("Error rebuilding UpgradePanel", e);
		}
	}

	private JSeparator separator()
	{
		final JSeparator s = new JSeparator();
		s.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		return s;
	}

	private JPanel buildActiveSection()
	{
		final JPanel sec = section("Unlocked Upgrades (this life)");
		if (mgr.getUnlockedUpgrades().isEmpty())
		{
			sec.add(dim("(none \u2014 strict mode)"));
			return sec;
		}
		for (Upgrade u : Upgrade.values())
		{
			if (u.isConsumable() || !mgr.isUnlocked(u))
			{
				continue;
			}
			sec.add(buildUnlockedRow(u));
			sec.add(Box.createVerticalStrut(2));
		}
		return sec;
	}

	private JPanel buildConsumablesSection()
	{
		final JPanel sec = section("Consumables (this life)");

		// GE Pass charge count
		sec.add(buildChargeRow("GE Pass",
			mgr.getConsumableCharges(Upgrade.GE_PASS),
			mgr.hasTemporaryGeSession() ? " (Active)" : null));
		sec.add(Box.createVerticalStrut(2));

		// Bank-pass charge counts (informational)
		sec.add(buildChargeRow("Deposit Pass", mgr.getConsumableCharges(Upgrade.DEPOSIT_PASS), null));
		sec.add(Box.createVerticalStrut(2));
		sec.add(buildChargeRow("Withdrawal Pass", mgr.getConsumableCharges(Upgrade.WITHDRAWAL_PASS), null));
		return sec;
	}

	private JPanel buildUnmBankSection()
	{
		final JPanel sec = section("UNM Bank (Deposit/Withdrawal Pass)");
		try
		{
			final Map<Integer, Integer> unmBank = mgr.getUnmBank();
			if (unmBank.isEmpty())
			{
				sec.add(dim("(empty)"));
				return sec;
			}
			// Sort by item ID for consistent ordering.
			final List<Entry<Integer, Integer>> entries = new ArrayList<>(unmBank.entrySet());
			entries.sort(Comparator.comparingInt(Entry::getKey));
			for (Entry<Integer, Integer> entry : entries)
			{
				final int itemId = entry.getKey();
				final int qty = entry.getValue();
				// Use cached item name (populated on client thread during deposit).
				// Fall back to item ID if not cached (e.g., items deposited before this change).
				final String itemName = mgr.getUnmBankItemName(itemId);
				final String displayName = (itemName != null) ? itemName : "Item ID: " + itemId;
				final JPanel row = new JPanel(new BorderLayout(6, 0));
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
				final JLabel name = new JLabel("\u2022 " + displayName);
				name.setForeground(ACTIVE_GREEN);
				row.add(name, BorderLayout.CENTER);
				final JLabel count = new JLabel("x" + qty);
				count.setForeground(ACTIVE_GREEN);
				count.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
				count.setPreferredSize(new Dimension(72, 22));
				row.add(count, BorderLayout.EAST);
				sec.add(row);
				sec.add(Box.createVerticalStrut(2));
			}
		}
		catch (Exception e)
		{
			sec.add(dim("(error loading unm-bank)"));
		}
		return sec;
	}

	private JPanel buildPurgatorySection()
	{
		final JPanel sec = section("Purgatory");
		try
		{
			final Map<Integer, Integer> purgatory = mgr.getPurgatory();
			if (purgatory.isEmpty())
			{
				sec.add(dim("(empty)"));
				return sec;
			}
			// Show unlock cost
			final long unlockCost = mgr.getPurgatoryUnlockCost();
			final JLabel costLabel = new JLabel("Unlock cost: " + formatGp(unlockCost));
			costLabel.setForeground(Color.LIGHT_GRAY);
			costLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			sec.add(costLabel);
			sec.add(Box.createVerticalStrut(4));

			// Sort by item ID for consistent ordering.
			final List<Entry<Integer, Integer>> entries = new ArrayList<>(purgatory.entrySet());
			entries.sort(Comparator.comparingInt(Entry::getKey));
			for (Entry<Integer, Integer> entry : entries)
			{
				final int itemId = entry.getKey();
				final int qty = entry.getValue();
				// Use cached item name (populated on client thread during deposit).
				// Fall back to item ID if not cached.
				final String itemName = mgr.getPurgatoryItemName(itemId);
				final String displayName = (itemName != null) ? itemName : "Item ID: " + itemId;
				final JPanel row = new JPanel(new BorderLayout(6, 0));
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
				final JLabel name = new JLabel("\u2022 " + displayName);
				name.setForeground(LOCKED_GRAY);
				row.add(name, BorderLayout.CENTER);
				final JLabel count = new JLabel("x" + qty);
				count.setForeground(LOCKED_GRAY);
				count.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
				count.setPreferredSize(new Dimension(72, 22));
				row.add(count, BorderLayout.EAST);
				sec.add(row);
				sec.add(Box.createVerticalStrut(2));
			}
		}
		catch (Exception e)
		{
			sec.add(dim("(error loading purgatory)"));
		}
		return sec;
	}

	private JPanel buildChargeRow(String label, int charges, String suffix)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		final boolean active = charges > 0 || suffix != null;
		final String nameText = "\u2022 " + label + (suffix != null ? suffix : "");
		final JLabel name = new JLabel(nameText);
		name.setForeground(active ? ACTIVE_GREEN : LOCKED_GRAY);
		row.add(name, BorderLayout.CENTER);
		final JLabel count = new JLabel("x" + charges);
		count.setForeground(active ? ACTIVE_GREEN : LOCKED_GRAY);
		count.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		count.setPreferredSize(new Dimension(72, 22));
		row.add(count, BorderLayout.EAST);
		return row;
	}

	private JPanel buildUnlockedRow(Upgrade u)
	{
		final boolean active = mgr.isActive(u);
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		final JLabel name = new JLabel("\u2022 " + u.getDisplayName());
		name.setForeground(active ? ACTIVE_GREEN : INACTIVE_RED);
		row.add(name, BorderLayout.CENTER);

		final JButton btn = makeStatusButton(active);
		if (u.isToggleable())
		{
			btn.addActionListener(e -> mgr.setActive(u, !active));
		}
		else
		{
			btn.setEnabled(false);
		}
		row.add(btn, BorderLayout.EAST);
		return row;
	}

	/** Standardised Active/Inactive button matching the Available Upgrades row buttons. */
	private JButton makeStatusButton(boolean active)
	{
		final JButton btn = new JButton(active ? "Active" : "Inactive");
		btn.setFocusPainted(false);
		btn.setPreferredSize(new Dimension(72, 24));
		btn.setForeground(active ? ACTIVE_GREEN : INACTIVE_RED);
		return btn;
	}

	private JPanel buildGoalSection()
	{
		final JPanel sec = section("Saving For");
		final Upgrade goal = mgr.getSelectedGoal();
		if (goal == null)
		{
			sec.add(dim("No goal selected. Choose one below."));
			return sec;
		}

		final JLabel name = new JLabel(goal.getDisplayName());
		name.setForeground(GOAL_BLUE);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		sec.add(name);
		sec.add(Box.createVerticalStrut(2));

		final long progress = mgr.getProgress(goal);
		final long cost = mgr.getCurrentCost(goal);
		final int pct = (int) Math.min(100, (progress * 100L) / Math.max(1L, cost));

		final JLabel detail = new JLabel(formatGp(progress) + " / " + formatGp(cost) + "  (" + pct + "%)");
		detail.setForeground(Color.LIGHT_GRAY);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		sec.add(detail);
		sec.add(Box.createVerticalStrut(4));

		final JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(pct);
		bar.setStringPainted(false);
		bar.setForeground(GOAL_BLUE);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setBorderPainted(false);
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
		sec.add(bar);
		sec.add(Box.createVerticalStrut(4));

		final JButton clearBtn = new JButton("Clear goal");
		clearBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		clearBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		clearBtn.addActionListener(e -> mgr.selectGoal(null));
		sec.add(clearBtn);

		sec.add(Box.createVerticalStrut(2));
		final JLabel hint = new JLabel(
			"<html><body style='width:150px'>"
				+ "Open a bank and drag coins onto the Bank Incinerator to make progress."
				+ "</body></html>");
		hint.setForeground(Color.GRAY);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		sec.add(hint);

		return sec;
	}

	private JPanel buildRulesSection()
	{
		final JPanel sec = section("How saving works");

		// Replace the header with a clickable toggle version
		final JLabel header = new JLabel((rulesCollapsed ? "\u25B6 " : "\u25BC ") + "How saving works");
		header.setForeground(Color.WHITE);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				rulesCollapsed = !rulesCollapsed;
				rebuild();
			}
		});
		sec.removeAll();
		sec.add(header);
		sec.add(Box.createVerticalStrut(4));

		// Content (only shown if not collapsed)
		if (!rulesCollapsed)
		{
			final JLabel rules = new JLabel(
				"<html>"
					+ "\u2022 <b>Enable the Bank Incinerator</b> in the bank's settings.<br>"
					+ "\u2022 Pick a goal, then drag coins onto the Incinerator. Every coin destroyed counts.<br>"
					+ "\u2022 Gateway upgrades (Banking / GE / Trade) can be toggled <b style='color:#50b450'>Active</b> / <b style='color:#c85050'>Inactive</b> at any time.<br>"
					+ "\u2022 <b>Dying wipes ALL progress and unlocks.</b>"
					+ "</html>");
			rules.setForeground(Color.LIGHT_GRAY);
			rules.setAlignmentX(Component.LEFT_ALIGNMENT);
			sec.add(rules);
		}

		return sec;
	}

	private JPanel buildAvailableSection()
	{
		final JPanel sec = section("Available Upgrades");

		// Group upgrades by category
		final Map<Upgrade.Category, List<Upgrade>> byCat = new EnumMap<>(Upgrade.Category.class);
		for (Upgrade u : Upgrade.values())
		{
			byCat.computeIfAbsent(u.getCategory(), k -> new ArrayList<>()).add(u);
		}

		boolean first = true;
		for (Upgrade.Category cat : Upgrade.Category.values())
		{
			final List<Upgrade> list = byCat.get(cat);
			if (list == null || list.isEmpty())
			{
				continue;
			}
			if (!first)
			{
				sec.add(Box.createVerticalStrut(8));
			}
			first = false;

			final JLabel catLabel = new JLabel(cat.getDisplayName());
			catLabel.setForeground(Color.LIGHT_GRAY);
			catLabel.setFont(FontManager.getRunescapeBoldFont());
			catLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			sec.add(catLabel);
			sec.add(Box.createVerticalStrut(2));

			for (Upgrade u : list)
			{
				sec.add(buildUpgradeRow(u));
			}
		}

		return sec;
	}

	private JPanel buildUpgradeRow(Upgrade u)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

		final JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		left.setOpaque(false);

		final JLabel name = new JLabel(u.getDisplayName());
		name.setForeground(Color.WHITE);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		left.add(name);

		final String costText = u.isConsumable()
			? formatGp(mgr.getCurrentCost(u)) + "  (+1K next)"
			: formatGp(u.getCost());
		final JLabel cost = new JLabel(costText);
		cost.setForeground(Color.LIGHT_GRAY);
		cost.setAlignmentX(Component.LEFT_ALIGNMENT);
		left.add(cost);

		row.add(left, BorderLayout.CENTER);
		row.add(buildRowButton(u), BorderLayout.EAST);

		row.setToolTipText("<html><body style='width:200px'>" + u.getDescription() + "</body></html>");
		return row;
	}

	private JButton buildRowButton(Upgrade u)
	{
		final JButton btn = new JButton();
		btn.setFocusPainted(false);
		btn.setPreferredSize(new Dimension(72, 24));

		final boolean active = mgr.isActive(u);
		final boolean isGoal = mgr.getSelectedGoal() == u;
		final boolean canSelect = mgr.canSelectAsGoal(u);

		if (active && !u.isConsumable())
		{
			btn.setText("Active");
			btn.setForeground(ACTIVE_GREEN);
			btn.setEnabled(false);
		}
		else if (!u.isConsumable() && mgr.isUnlocked(u))
		{
			// Owned but currently toggled off (only possible for gateway upgrades).
			btn.setText("Inactive");
			btn.setForeground(INACTIVE_RED);
			btn.setEnabled(false);
		}
		else if (isGoal)
		{
			btn.setText("Goal");
			btn.setForeground(GOAL_BLUE);
			btn.setEnabled(false);
		}
		else if (!canSelect)
		{
			btn.setText("Locked");
			btn.setForeground(LOCKED_GRAY);
			btn.setEnabled(false);
		}
		else
		{
			btn.setText(u.isConsumable() ? "Buy" : "Set");
			btn.addActionListener(e -> mgr.selectGoal(u));
		}
		return btn;
	}

	// -- Layout helpers -------------------------------------------------

	private JPanel section(String title)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel header = new JLabel(title);
		header.setForeground(Color.WHITE);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(header);
		p.add(Box.createVerticalStrut(4));
		return p;
	}

	private JLabel dim(String text)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(Color.GRAY);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	// -- Static helpers shared with the plugin --

	public static String formatGp(long gp)
	{
		final long abs = Math.abs(gp);
		final String s;
		if (abs >= 1_000_000_000L)
		{
			s = trim(abs / 1_000_000_000.0) + "B";
		}
		else if (abs >= 1_000_000L)
		{
			s = trim(abs / 1_000_000.0) + "M";
		}
		else if (abs >= 1_000L)
		{
			s = trim(abs / 1_000.0) + "K";
		}
		else
		{
			s = Long.toString(abs);
		}
		return (gp < 0 ? "-" : "") + s + " gp";
	}

	private static String trim(double v)
	{
		if (v >= 100)
		{
			return Long.toString(Math.round(v));
		}
		return String.format(Locale.US, v >= 10 ? "%.1f" : "%.2f", v).replaceAll("\\.?0+$", "");
	}

	/** A small skull icon used as the navigation button. */
	public static BufferedImage createNavIcon()
	{
		final int s = 16;
		final BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		final int B = 0xFF000000;
		final int W = 0xFFFFFFFF;
		final int[][] skull = {
			{0,0,0,0,0,B,B,B,B,B,B,0,0,0,0,0},
			{0,0,0,0,B,W,W,W,W,W,W,B,0,0,0,0},
			{0,0,0,B,W,W,W,W,W,W,W,W,B,0,0,0},
			{0,0,B,W,W,W,W,W,W,W,W,W,W,B,0,0},
			{0,0,B,W,B,B,W,W,W,B,B,W,W,B,0,0},
			{0,0,B,W,B,B,W,W,W,B,B,W,W,B,0,0},
			{0,0,B,W,W,W,W,B,W,W,W,W,W,B,0,0},
			{0,0,B,W,W,W,W,W,W,W,W,W,W,B,0,0},
			{0,0,0,B,W,W,B,W,B,W,W,B,B,0,0,0},
			{0,0,0,0,B,W,W,W,W,W,B,0,0,0,0,0},
			{0,0,0,0,0,B,B,B,B,B,0,0,0,0,0,0},
		};
		for (int y = 0; y < skull.length; y++)
		{
			for (int x = 0; x < skull[y].length; x++)
			{
				if (skull[y][x] != 0)
				{
					img.setRGB(x, y + 2, skull[y][x]);
				}
			}
		}
		return img;
	}

}

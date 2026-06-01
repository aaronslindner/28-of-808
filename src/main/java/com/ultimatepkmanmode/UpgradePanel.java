package com.ultimatepkmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel for the Upgrade Mode UI:
 *   - Active upgrades for this life
 *   - Current saving goal + progress bar
 *   - Available upgrades grouped by category with [Set] / [Active] / [Locked] / [Goal] state
 */
public class UpgradePanel extends PluginPanel
{
	private static final Color ACTIVE_GREEN = new Color(80, 180, 80);
	private static final Color GOAL_BLUE    = new Color(80, 130, 200);
	private static final Color LOCKED_GRAY  = new Color(110, 110, 110);

	private final UpgradeManager mgr;

	public UpgradePanel(UpgradeManager mgr)
	{
		super(false);
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

		removeAll();

		final JLabel header = new JLabel("Upgrade Mode");
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		add(header, BorderLayout.NORTH);

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		body.add(buildActiveSection());
		body.add(Box.createVerticalStrut(8));
		body.add(separator());
		body.add(Box.createVerticalStrut(8));
		body.add(buildGoalSection());
		body.add(Box.createVerticalStrut(8));
		body.add(separator());
		body.add(Box.createVerticalStrut(8));
		body.add(buildAvailableSection());

		add(body, BorderLayout.CENTER);
		revalidate();
		repaint();
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
		final JPanel sec = section("Active Upgrades (this life)");
		if (mgr.getActiveUpgrades().isEmpty())
		{
			sec.add(dim("(none \u2014 strict mode)"));
		}
		else
		{
			for (Upgrade u : Upgrade.values())
			{
				if (mgr.isActive(u))
				{
					final JLabel l = new JLabel("\u2022 " + u.getDisplayName());
					l.setForeground(ACTIVE_GREEN);
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					sec.add(l);
				}
			}
		}
		return sec;
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
		final long cost = goal.getCost();
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
			"<html><body style='width:170px'>"
				+ "Open a bank and drag coins onto the incinerator to make progress."
				+ "</body></html>");
		hint.setForeground(Color.GRAY);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		sec.add(hint);

		return sec;
	}

	private JPanel buildAvailableSection()
	{
		final JPanel sec = section("Available Upgrades");

		// Group upgrades by category
		final Map<Upgrade.Category, java.util.List<Upgrade>> byCat = new EnumMap<>(Upgrade.Category.class);
		for (Upgrade u : Upgrade.values())
		{
			byCat.computeIfAbsent(u.getCategory(), k -> new java.util.ArrayList<>()).add(u);
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

		final JLabel cost = new JLabel(formatGp(u.getCost()));
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

		if (active)
		{
			btn.setText("Active");
			btn.setForeground(ACTIVE_GREEN);
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
			btn.setText("Set");
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
		return String.format(java.util.Locale.US, v >= 10 ? "%.1f" : "%.2f", v).replaceAll("\\.?0+$", "");
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

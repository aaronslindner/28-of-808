package com.ultimatepkmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class LeaderboardPanel extends PluginPanel
{
	private final JPanel listPanel = new JPanel();
	private Runnable refreshAction;

	public LeaderboardPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("UNM Leaderboard");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setForeground(Color.WHITE);
		title.setHorizontalAlignment(SwingConstants.CENTER);
		title.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		header.add(title, BorderLayout.CENTER);

		final JButton refreshBtn = new JButton("Refresh");
		refreshBtn.addActionListener(e ->
		{
			if (refreshAction != null)
			{
				refreshAction.run();
			}
		});
		header.add(refreshBtn, BorderLayout.SOUTH);

		add(header, BorderLayout.NORTH);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		add(scrollPane, BorderLayout.CENTER);

		rebuild(Collections.emptyList());
	}

	public void setRefreshAction(Runnable action)
	{
		this.refreshAction = action;
	}

	public void rebuild(List<LeaderboardEntry> entries)
	{
		listPanel.removeAll();

		if (entries.isEmpty())
		{
			final JLabel empty = new JLabel("No data yet");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			empty.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
			listPanel.add(empty);
		}
		else
		{
			int rank = 1;
			for (LeaderboardEntry entry : entries)
			{
				listPanel.add(createRow(rank++, entry));
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel createRow(int rank, LeaderboardEntry entry)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(rank % 2 == 0 ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		final JLabel nameLabel = new JLabel("#" + rank + "  " + entry.getPlayerName());
		nameLabel.setForeground(Color.WHITE);
		row.add(nameLabel, BorderLayout.WEST);

		final JLabel wealthLabel = new JLabel(UltimateNormiePlugin.formatGp(entry.getWealth()));
		wealthLabel.setForeground(new Color(255, 215, 0));
		row.add(wealthLabel, BorderLayout.EAST);

		return row;
	}
}

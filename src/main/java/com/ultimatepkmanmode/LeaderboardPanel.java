package com.ultimatepkmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
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
	private static final int PAGE_SIZE = 20;
	private final JPanel listPanel = new JPanel();
	private final JPanel footerPanel = new JPanel(new BorderLayout());
	private final JButton prevBtn = new JButton("< Prev");
	private final JButton nextBtn = new JButton("Next >");
	private IntConsumer pageCallback;
	private String playerName;
	private int currentPage = 1;
	private int totalPages = 1;

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
		refreshBtn.addActionListener(e -> requestPage(currentPage));
		header.add(refreshBtn, BorderLayout.SOUTH);

		add(header, BorderLayout.NORTH);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		add(scrollPane, BorderLayout.CENTER);

		// Footer with pagination
		footerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footerPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		prevBtn.addActionListener(e -> requestPage(currentPage - 1));
		nextBtn.addActionListener(e -> requestPage(currentPage + 1));
		footerPanel.add(prevBtn, BorderLayout.WEST);
		footerPanel.add(nextBtn, BorderLayout.EAST);
		add(footerPanel, BorderLayout.SOUTH);

		rebuild(null);
	}

	public void setPageCallback(IntConsumer callback)
	{
		this.pageCallback = callback;
	}

	private void requestPage(int page)
	{
		if (page < 1 || page > totalPages || pageCallback == null)
		{
			return;
		}
		pageCallback.accept(page);
	}

	public void setPlayerName(String name)
	{
		this.playerName = name;
	}

	public void rebuild(LeaderboardResponse response)
	{
		if (response != null)
		{
			currentPage = response.getPage();
			totalPages = response.getTotalPages();
		}

		listPanel.removeAll();

		// Player's own rank
		final String displayName = playerName != null ? playerName : "You";
		if (response != null && response.getPlayerRank() != null)
		{
			LeaderboardResponse.PlayerRank pr = response.getPlayerRank();
			listPanel.add(createPlayerRankRow(pr.getRank(), pr.getPlayerName(), pr.getWealth()));
		}
		else
		{
			listPanel.add(createPlayerRankRow(-1, displayName, -1));
		}

		// Separator
		final JPanel sep = new JPanel();
		sep.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
		sep.setPreferredSize(new Dimension(0, 2));
		listPanel.add(sep);

		final List<LeaderboardEntry> entries = response != null && response.getLeaderboard() != null
			? response.getLeaderboard()
			: Collections.emptyList();

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
			int startRank = (currentPage - 1) * PAGE_SIZE + 1;
			for (LeaderboardEntry entry : entries)
			{
				listPanel.add(createRow(startRank++, entry));
			}
		}

		// Update footer
		prevBtn.setEnabled(currentPage > 1);
		nextBtn.setEnabled(currentPage < totalPages);

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel createPlayerRankRow(int rank, String name, long wealth)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(new Color(40, 40, 60));
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

		final String rankText = rank > 0 ? "#" + rank : "???";
		final JLabel nameLabel = new JLabel(rankText + "  " + name);
		nameLabel.setForeground(new Color(100, 200, 255));
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
		row.add(nameLabel, BorderLayout.WEST);

		if (wealth >= 0)
		{
			final JLabel wealthLabel = new JLabel(UltimateNormiePlugin.formatGp(wealth));
			wealthLabel.setForeground(new Color(255, 215, 0));
			wealthLabel.setFont(wealthLabel.getFont().deriveFont(Font.BOLD));
			row.add(wealthLabel, BorderLayout.EAST);
		}
		else
		{
			final JLabel unknown = new JLabel("---");
			unknown.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			row.add(unknown, BorderLayout.EAST);
		}

		return row;
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

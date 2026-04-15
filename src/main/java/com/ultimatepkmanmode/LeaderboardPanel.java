package com.ultimatepkmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
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
	private final JButton prestigeBtn = new JButton("Prestige (1B GP)");
	private final JLabel prestigeProgress = new JLabel();
	private IntConsumer pageCallback;
	private Runnable prestigeCallback;
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

		final JPanel btnPanel = new JPanel();
		btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
		btnPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		prestigeBtn.setAlignmentX(CENTER_ALIGNMENT);
		prestigeBtn.addActionListener(e -> {
			if (prestigeCallback != null) prestigeCallback.run();
		});
		btnPanel.add(prestigeBtn);

		btnPanel.add(Box.createVerticalStrut(8));

		final JLabel prestigeDescLine1 = new JLabel("In order to prestige, you must");
		prestigeDescLine1.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		prestigeDescLine1.setFont(prestigeDescLine1.getFont().deriveFont(Font.PLAIN, 13f));
		prestigeDescLine1.setAlignmentX(CENTER_ALIGNMENT);
		prestigeDescLine1.setHorizontalAlignment(SwingConstants.CENTER);
		btnPanel.add(prestigeDescLine1);

		btnPanel.add(Box.createVerticalStrut(2));

		final JLabel prestigeDescLine2 = new JLabel("sacrifice 1B coins.");
		prestigeDescLine2.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		prestigeDescLine2.setFont(prestigeDescLine2.getFont().deriveFont(Font.PLAIN, 13f));
		prestigeDescLine2.setAlignmentX(CENTER_ALIGNMENT);
		prestigeDescLine2.setHorizontalAlignment(SwingConstants.CENTER);
		btnPanel.add(prestigeDescLine2);

		btnPanel.add(Box.createVerticalStrut(8));

		prestigeProgress.setForeground(new Color(255, 200, 0));
		prestigeProgress.setHorizontalAlignment(SwingConstants.CENTER);
		prestigeProgress.setAlignmentX(CENTER_ALIGNMENT);
		prestigeProgress.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		prestigeProgress.setVisible(false);
		btnPanel.add(prestigeProgress);

		header.add(btnPanel, BorderLayout.SOUTH);

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
		final JButton refreshBtn = new JButton("Refresh");
		refreshBtn.addActionListener(e -> requestPage(currentPage));
		footerPanel.add(prevBtn, BorderLayout.WEST);
		footerPanel.add(refreshBtn, BorderLayout.CENTER);
		footerPanel.add(nextBtn, BorderLayout.EAST);
		add(footerPanel, BorderLayout.SOUTH);

		rebuild(null);
	}

	public void setPageCallback(IntConsumer callback)
	{
		this.pageCallback = callback;
	}

	public void setPrestigeCallback(Runnable callback)
	{
		this.prestigeCallback = callback;
	}

	public void setPrestigeEnabled(boolean enabled)
	{
		prestigeBtn.setEnabled(enabled);
	}

	public void setPrestigeMode(boolean active, long incinerated, long target)
	{
		prestigeProgress.setVisible(active);
		if (active)
		{
			prestigeBtn.setEnabled(false);
			if (incinerated == 0)
			{
				prestigeBtn.setText("Prestige Active");
				prestigeProgress.setText("Open a bank and use the incinerator.");
			}
			else
			{
				prestigeBtn.setText("Incinerating...");
				prestigeProgress.setText(UltimateNormiePlugin.formatGp(incinerated)
					+ " / " + UltimateNormiePlugin.formatGp(target));
			}
		}
		else
		{
			prestigeBtn.setEnabled(true);
			prestigeBtn.setText("Prestige (1B GP)");
		}
		revalidate();
		repaint();
	}

	public void triggerRefresh()
	{
		requestPage(currentPage);
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
			listPanel.add(createPlayerRankRow(pr.getRank(), pr.getPlayerName(), pr.getWealth(), pr.getPrestige()));
		}
		else
		{
			listPanel.add(createPlayerRankRow(-1, displayName, -1, 0));
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

	private static String prestigeSkull(int prestige)
	{
		if (prestige <= 0)
		{
			return "";
		}
		return " \u2620";
	}

	private static Color prestigeColor(int prestige)
	{
		switch (prestige)
		{
			case 1: return new Color(255, 0, 0);       // Red
			case 2: return new Color(255, 127, 0);     // Orange
			case 3: return new Color(255, 255, 0);     // Yellow
			case 4: return new Color(0, 255, 0);       // Green
			case 5: return new Color(0, 0, 255);       // Blue
			case 6: return new Color(75, 0, 130);      // Indigo
			case 7: return new Color(139, 0, 255);     // Violet
			default: return prestige > 7 ? new Color(17, 17, 17) : Color.WHITE;
		}
	}

	private JPanel createPlayerRankRow(int rank, String name, long wealth, int prestige)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(new Color(40, 40, 60));
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

		final String rankText = rank > 0 ? "#" + rank : "???";
		final JLabel nameLabel = new JLabel(rankText + "  " + name + prestigeSkull(prestige));
		nameLabel.setForeground(prestige > 0 ? prestigeColor(prestige) : Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
		row.add(nameLabel, BorderLayout.WEST);

		if (wealth >= 0)
		{
			final JLabel wealthLabel = new JLabel(UltimateNormiePlugin.formatGp(wealth));
			wealthLabel.setForeground(prestige > 0 ? prestigeColor(prestige) : new Color(255, 215, 0));
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

		final int p = entry.getPrestige();
		final JLabel nameLabel = new JLabel("#" + rank + "  " + entry.getPlayerName() + prestigeSkull(p));
		nameLabel.setForeground(p > 0 ? prestigeColor(p) : Color.WHITE);
		row.add(nameLabel, BorderLayout.WEST);

		final JLabel wealthLabel = new JLabel(UltimateNormiePlugin.formatGp(entry.getWealth()));
		wealthLabel.setForeground(p > 0 ? prestigeColor(p) : new Color(255, 215, 0));
		row.add(wealthLabel, BorderLayout.EAST);

		return row;
	}
}

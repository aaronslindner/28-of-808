package com.ultimatepkmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
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
			if (incinerated > 0)
			{
				prestigeBtn.setText("Resume Prestige");
				prestigeProgress.setVisible(true);
				prestigeProgress.setText("Saved: " + UltimateNormiePlugin.formatGp(incinerated)
					+ " / " + UltimateNormiePlugin.formatGp(target));
			}
			else
			{
				prestigeBtn.setText("Prestige (1B GP)");
			}
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

	private static ImageIcon prestigeSkullIcon(int prestige)
	{
		if (prestige <= 0)
		{
			return null;
		}
		final boolean horned = prestige >= 9;
		final boolean gilded = prestige >= 10;
		final Color skullFill = prestige >= 8 ? Color.BLACK : prestigeColor(prestige);
		return new ImageIcon(createSkullImage(skullFill, 12, horned, gilded));
	}

	static BufferedImage createSkullImage(Color fill, int size)
	{
		return createSkullImage(fill, size, false, false);
	}

	static BufferedImage createSkullImage(Color fill, int size, boolean horned, boolean gilded)
	{
		final boolean inverted = fill.equals(Color.BLACK);
		final int w = 12;
		final int h = 12;
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final int GOLD = 0xFFFFD700;
		final int RED  = 0xFFFF0000;
		final int O = gilded ? GOLD : (inverted ? 0xFFFFFFFF : 0xFF000000); // outline
		final int F = inverted ? 0xFF000000 : fill.getRGB(); // fill
		final int D = gilded ? GOLD : (inverted ? 0xFFFFFFFF : 0xFF000000); // detail
		final int E = gilded ? RED : D; // eyes

		if (horned)
		{
			// Horns: 1px tip, widening down into skull sides
			fillRect(img, 0, 0, 1, 1, O);  // left tip
			fillRect(img, 0, 1, 2, 1, O);  // left widen
			fillRect(img, 1, 2, 2, 1, O);  // left base
			fillRect(img, 11, 0, 1, 1, O); // right tip
			fillRect(img, 10, 1, 2, 1, O); // right widen
			fillRect(img, 9, 2, 2, 1, O);  // right base
			// Skull body (horns merge into sides)
			fillRect(img, 3, 2, 6, 1, O);
			fillRect(img, 2, 3, 8, 1, O);
			fillRect(img, 1, 3, 1, 5, O);  // left side column
			fillRect(img, 10, 3, 1, 5, O); // right side column
			fillRect(img, 2, 4, 8, 4, O);
			fillRect(img, 2, 8, 8, 1, O);
			fillRect(img, 3, 9, 6, 1, O);

			fillRect(img, 3, 3, 6, 1, F);
			fillRect(img, 2, 4, 8, 4, F);
			fillRect(img, 3, 8, 6, 1, F);

			fillRect(img, 3, 5, 2, 2, E);
			fillRect(img, 7, 5, 2, 2, E);
			fillRect(img, 5, 7, 2, 1, D);

			fillRect(img, 4, 9, 1, 1, D);
			fillRect(img, 6, 9, 1, 1, D);
		}
		else
		{
			// outline
			fillRect(img, 3, 1, 6, 1, O);
			fillRect(img, 2, 2, 8, 1, O);
			fillRect(img, 1, 3, 10, 5, O);
			fillRect(img, 2, 8, 8, 1, O);
			fillRect(img, 3, 9, 6, 1, O);

			// fill
			fillRect(img, 3, 2, 6, 1, F);
			fillRect(img, 2, 3, 8, 5, F);
			fillRect(img, 3, 8, 6, 1, F);

			// eyes + nose
			fillRect(img, 3, 4, 2, 2, D);
			fillRect(img, 7, 4, 2, 2, D);
			fillRect(img, 5, 6, 2, 1, D);

			// teeth
			fillRect(img, 4, 9, 1, 1, D);
			fillRect(img, 6, 9, 1, 1, D);
		}

		if (size != w)
		{
			final BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			final java.awt.Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.drawImage(img, 0, 0, size, size, null);
			g.dispose();
			return scaled;
		}
		return img;
	}

	private static void fillRect(BufferedImage img, int x, int y, int w, int h, int argb)
	{
		for (int dy = 0; dy < h; dy++)
		{
			for (int dx = 0; dx < w; dx++)
			{
				img.setRGB(x + dx, y + dy, argb);
			}
		}
	}

	private static Color prestigeColor(int prestige)
	{
		switch (prestige)
		{
			case 1: return new Color(255, 0, 0);       // Red
			case 2: return new Color(255, 127, 0);     // Orange
			case 3: return new Color(255, 255, 0);     // Yellow
			case 4: return new Color(0, 255, 0);       // Green
			case 5: return new Color(0, 100, 255);     // Blue
			case 6: return new Color(75, 0, 130);      // Indigo
			case 7: return new Color(139, 0, 255);     // Violet
			case 8: return Color.BLACK;
			case 9: return Color.BLACK;
			default: return prestige >= 10 ? new Color(255, 215, 0) : Color.WHITE; // Gold
		}
	}

	private JPanel createPlayerRankRow(int rank, String name, long wealth, int prestige)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(new Color(40, 40, 60));
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

		final String rankText = rank > 0 ? "#" + rank : "???";
		final JLabel nameLabel = new JLabel(rankText + "  " + name);
		nameLabel.setForeground(prestige > 0 ? prestigeColor(prestige) : Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
		final ImageIcon skullIcon = prestigeSkullIcon(prestige);
		if (skullIcon != null)
		{
			final JLabel skullLabel = new JLabel(skullIcon);
			skullLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
			final JPanel namePanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			namePanel.setOpaque(false);
			namePanel.add(nameLabel);
			namePanel.add(skullLabel);
			row.add(namePanel, BorderLayout.WEST);
		}
		else
		{
			row.add(nameLabel, BorderLayout.WEST);
		}

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
		final JLabel nameLabel = new JLabel("#" + rank + "  " + entry.getPlayerName());
		nameLabel.setForeground(p > 0 ? prestigeColor(p) : Color.WHITE);
		final ImageIcon skullIcon = prestigeSkullIcon(p);
		if (skullIcon != null)
		{
			final JLabel skullLabel = new JLabel(skullIcon);
			skullLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
			final JPanel namePanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			namePanel.setOpaque(false);
			namePanel.add(nameLabel);
			namePanel.add(skullLabel);
			row.add(namePanel, BorderLayout.WEST);
		}
		else
		{
			row.add(nameLabel, BorderLayout.WEST);
		}

		final JLabel wealthLabel = new JLabel(UltimateNormiePlugin.formatGp(entry.getWealth()));
		wealthLabel.setForeground(p > 0 ? prestigeColor(p) : new Color(255, 215, 0));
		row.add(wealthLabel, BorderLayout.EAST);

		return row;
	}
}

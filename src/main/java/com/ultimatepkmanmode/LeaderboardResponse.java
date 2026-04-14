package com.ultimatepkmanmode;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Data;

@Data
public class LeaderboardResponse
{
	@SerializedName("player_rank")
	private PlayerRank playerRank;

	private List<LeaderboardEntry> leaderboard;

	@Data
	public static class PlayerRank
	{
		private int rank;

		@SerializedName("player_name")
		private String playerName;

		private long wealth;
	}
}

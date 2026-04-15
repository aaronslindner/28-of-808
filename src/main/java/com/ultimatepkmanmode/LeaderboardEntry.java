package com.ultimatepkmanmode;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class LeaderboardEntry
{
	@SerializedName("player_name")
	private String playerName;

	private long wealth;

	private int prestige;

	@SerializedName("updated_at")
	private String updatedAt;
}

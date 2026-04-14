package com.ultimatepkmanmode;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;

@Singleton
public class WealthCalculator
{
	private static final int COINS = 995;
	private static final int PLATINUM_TOKEN = 13204;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	private long cachedLootingBagWealth = 0;

	public long calculateWealth()
	{
		long total = 0;
		total += containerWealth(InventoryID.INV);
		total += containerWealth(InventoryID.WORN);
		total += cachedLootingBagWealth;
		return total;
	}

	public void updateLootingBagCache()
	{
		cachedLootingBagWealth = containerWealth(InventoryID.LOOTING_BAG);
	}

	public void clearLootingBagCache()
	{
		cachedLootingBagWealth = 0;
	}

	private long containerWealth(int id)
	{
		final ItemContainer container = client.getItemContainer(id);
		if (container == null)
		{
			return 0;
		}
		long total = 0;
		for (Item item : container.getItems())
		{
			final int itemId = item.getId();
			final int qty = item.getQuantity();
			if (itemId <= 0 || qty <= 0)
			{
				continue;
			}
			final int price;
			if (itemId == COINS)
			{
				price = 1;
			}
			else if (itemId == PLATINUM_TOKEN)
			{
				price = 1000;
			}
			else
			{
				int gePrice = itemManager.getItemPrice(itemId);
				if (gePrice > 0)
				{
					price = gePrice;
				}
				else
				{
					price = client.getItemDefinition(itemId).getPrice();
				}
			}
			total += (long) price * qty;
		}
		return total;
	}
}

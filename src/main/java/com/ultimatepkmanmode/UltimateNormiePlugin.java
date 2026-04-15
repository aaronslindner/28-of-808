package com.ultimatepkmanmode;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedSprite;
import net.runelite.api.SoundEffectVolume;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Ultimate Normie Mode",
	description = "Restrictions on banking, trading and the GE"
)
public class UltimateNormiePlugin extends Plugin
{
	private static final boolean DISABLE_BANKING = true;
	private static final int LIMIT_PCT = 10;
	private static final long ABSOLUTE_CAP_GP = 5_000_000;
	private static final int GE_CUSTOM_ENTRY_COOLDOWN = 2;
	private static final int VARCI_INPUT_TYPE = 5;
	private static final String LEADERBOARD_URL = "https://28-of-808-production.up.railway.app";
	private static final String LEADERBOARD_API_KEY = "Texhad99bottlesonthewall!";
	private static final long PRESTIGE_COST = 1_000L; // TODO: restore to 1_000_000_000L after testing
	private static final int COINS = 995;
	private static final int PLATINUM_TOKEN = 13204;

	private int gameTick = 0;
	private int geClickedNonSubmitTick = -1;
	private int geCustomEntryTick = -100;
	private int tradeClickedNonAcceptTick = -1;
	private boolean tradePassedFirstScreen = false;
	private boolean pendingBoop = false;
	private int tradeItemChangedTick = -1;
	private long lastWealth = 0;
	private String lastPlayerName = null;

	private int playerPrestige = 0;
	private boolean pendingPrestigeFetch = false;

	// Prestige mode state
	private boolean prestigeMode = false;
	private boolean prestigeBankOpened = false;
	private long totalCoinsSnapshot = 0;
	private long incineratedValue = 0;

	public int getLimitPct()
	{
		return LIMIT_PCT;
	}

	private static boolean isBankInteraction(String option, String target)
	{
		if (option == null)
		{
			return false;
		}
		final String t = target == null ? "" : target;

		// Block all "Bank" variants (Bank, Bank-5, Bank-10, Bank-all, Bank-x, etc.)
		if (option.startsWith("bank"))
		{
			return true;
		}

		// Block deposit boxes specifically
		if (option.startsWith("deposit") && t.contains("deposit box"))
		{
			return true;
		}

		// Block "bank-all" / "send to bank" style options on loot interfaces
		if (option.equals("bank-all") || option.contains("send to bank"))
		{
			return true;
		}

		// Block private storage in Chambers of Xeric (allow shared)
		if (option.equals("private") && t.contains("storage unit"))
		{
			return true;
		}

		// Block interactions with bank-like targets (bankers, bank booths, etc.)
		final boolean bankLikeTarget = t.contains("bank") || t.contains("banker");
		if (bankLikeTarget)
		{
			return option.contains("withdraw") || option.equals("talk-to");
		}

		return false;
	}

	public static String formatGp(long gp)
	{
		final long abs = Math.abs(gp);
		final String s;
		if (abs >= 1_000_000_000L)
		{
			s = sigFigs(abs / 1_000_000_000.0) + "B";
		}
		else if (abs >= 1_000_000L)
		{
			s = sigFigs(abs / 1_000_000.0) + "M";
		}
		else if (abs >= 1_000L)
		{
			s = sigFigs(abs / 1_000.0) + "K";
		}
		else
		{
			s = Long.toString(abs);
		}
		return (gp < 0 ? "-" : "") + s + " gp";
	}

	private static String sigFigs(double value)
	{
		if (value >= 100)
		{
			return Long.toString(Math.round(value));
		}
		else if (value >= 10)
		{
			return new DecimalFormat("0.#", java.text.DecimalFormatSymbols.getInstance()).format(value);
		}
		else
		{
			return new DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance()).format(value);
		}
	}

	public long getAbsoluteCapGp()
	{
		return ABSOLUTE_CAP_GP;
	}

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private TradeBalanceOverlay tradeBalanceOverlay;

	@Inject
	private UltimateNormieChatPromptSkullOverlay chatPromptSkullOverlay;

	@Inject
	private UltimateNormieConfig config;

	@Inject
	private WealthCalculator wealthCalculator;

	@Inject
	private LeaderboardClient leaderboardClient;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	private LeaderboardPanel leaderboardPanel;
	private NavigationButton navButton;

	private IndexedSprite[] priorModIcons;
	private int skullModIconIndex = -1;

	@Provides
	UltimateNormieConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(UltimateNormieConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(tradeBalanceOverlay);
		overlayManager.add(chatPromptSkullOverlay);
		registerChatSkullIcon(prestigeSkullColor());

		leaderboardPanel = new LeaderboardPanel();
		leaderboardPanel.setPageCallback(page ->
			leaderboardClient.fetchLeaderboard(LEADERBOARD_URL, lastPlayerName, page, response ->
			{
				leaderboardPanel.rebuild(response);
				if (response != null && response.getPlayerRank() != null)
				{
					final int newPrestige = response.getPlayerRank().getPrestige();
					if (newPrestige != playerPrestige)
					{
						playerPrestige = newPrestige;
						clientThread.invokeLater(() -> reregisterChatSkull());
					}
				}
			})
		);
		leaderboardPanel.setPrestigeCallback(this::startPrestige);
		navButton = NavigationButton.builder()
			.tooltip("UNM Leaderboard")
			.icon(createNavIcon())
			.panel(leaderboardPanel)
			.priority(10)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(tradeBalanceOverlay);
		overlayManager.remove(chatPromptSkullOverlay);
		unregisterChatSkullIcon();
		clientToolbar.removeNavigation(navButton);
		cancelPrestige();
	}

	public void startPrestige()
	{
		if (prestigeMode)
		{
			return;
		}
		if (lastWealth < PRESTIGE_COST)
		{
			return;
		}
		prestigeMode = true;
		prestigeBankOpened = false;
		totalCoinsSnapshot = 0;
		final long remaining = PRESTIGE_COST - incineratedValue;
		clientThread.invokeLater(() ->
		{
			if (incineratedValue > 0)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Prestige resumed! " + formatGp(incineratedValue) + " / " + formatGp(PRESTIGE_COST)
						+ " (" + formatGp(remaining) + " remaining). Open a bank to continue.", null);
			}
			else
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Prestige mode activated! Open a bank and use the incinerator to destroy "
						+ formatGp(PRESTIGE_COST) + " in coins.", null);
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Coins left in your bank will be withdrawn when you close the bank.", null);
		});
		leaderboardPanel.setPrestigeMode(true, incineratedValue, PRESTIGE_COST);
	}

	private void cancelPrestige()
	{
		if (!prestigeMode)
		{
			return;
		}
		prestigeMode = false;
		prestigeBankOpened = false;
		totalCoinsSnapshot = 0;
		if (incineratedValue > 0)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Prestige paused. Progress saved: " + formatGp(incineratedValue)
					+ " / " + formatGp(PRESTIGE_COST) + ".", null);
		}
		else
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Prestige cancelled.", null);
		}
		leaderboardPanel.setPrestigeMode(false, incineratedValue, PRESTIGE_COST);
	}

	private static final String CONFIG_GROUP = "ultimatepkmanmode";
	private static final String CONFIG_INCINERATED = "incineratedValue";

	private void saveIncineratedValue()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_INCINERATED, incineratedValue);
	}

	private void loadIncineratedValue()
	{
		String val = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_INCINERATED);
		if (val != null)
		{
			try
			{
				incineratedValue = Long.parseLong(val);
			}
			catch (NumberFormatException e)
			{
				incineratedValue = 0;
			}
		}
		else
		{
			incineratedValue = 0;
		}
	}

	private void completePrestige()
	{
		prestigeMode = false;
		prestigeBankOpened = false;
		incineratedValue = 0;
		saveIncineratedValue();
		totalCoinsSnapshot = 0;
		// Force-close the bank so normal restrictions resume
		client.runScript(29);
		// Reset UI immediately so the button doesn't stay stuck
		leaderboardPanel.setPrestigeMode(false, 0, PRESTIGE_COST);
		leaderboardClient.postPrestige(LEADERBOARD_URL, LEADERBOARD_API_KEY, lastPlayerName, prestige ->
		{
			playerPrestige = prestige;
			clientThread.invokeLater(() ->
			{
				reregisterChatSkull();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Prestige complete! You are now prestige " + prestige + ".", null);
			});
			leaderboardPanel.triggerRefresh();
		});
	}

	private long countCoinsIn(int inventoryId)
	{
		final ItemContainer container = client.getItemContainer(inventoryId);
		if (container == null)
		{
			return 0;
		}
		long total = 0;
		for (Item item : container.getItems())
		{
			if (item.getId() == COINS)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	private void snapshotTotalCoins()
	{
		totalCoinsSnapshot = countCoinsIn(InventoryID.BANK) + countCoinsIn(InventoryID.INV);
	}

	private void checkBankIncinerationProgress()
	{
		long currentTotal = countCoinsIn(InventoryID.BANK) + countCoinsIn(InventoryID.INV);

		// Only count decreases in the combined total (coins truly destroyed)
		long burned = totalCoinsSnapshot - currentTotal;
		if (burned > 0)
		{
			incineratedValue += burned;
			saveIncineratedValue();
			long remaining = PRESTIGE_COST - incineratedValue;
			leaderboardPanel.setPrestigeMode(true, incineratedValue, PRESTIGE_COST);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Sacrificed " + formatGp(burned) + " towards prestige.", null);
			if (remaining > 0)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Progress: " + formatGp(incineratedValue) + " / " + formatGp(PRESTIGE_COST)
						+ " (" + formatGp(remaining) + " remaining)", null);
			}
		}

		// Always update snapshot to current total
		totalCoinsSnapshot = currentTotal;

		if (incineratedValue >= PRESTIGE_COST)
		{
			completePrestige();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (DISABLE_BANKING && (event.getGroupId() == 12 || event.getGroupId() == 192))
		{
			if (prestigeMode)
			{
				prestigeBankOpened = true;
				snapshotTotalCoins();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Deposit coins, then drag them to the incinerator to destroy.", null);
				return;
			}
			client.runScript(29);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Banking is disabled.", null);
			pendingBoop = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		gameTick++;

		if (pendingBoop)
		{
			client.playSoundEffect(2277, SoundEffectVolume.MEDIUM_HIGH);
			pendingBoop = false;
		}

		// While a chatbox input is active on the GE offer screen,
		// keep the custom-entry cooldown alive so it only starts
		// counting down after the chatbox is dismissed.
		if (client.getWidget(465, 24) != null
			&& gameTick - geCustomEntryTick < 100
			&& client.getVarcIntValue(VARCI_INPUT_TYPE) != 0)
		{
			geCustomEntryTick = gameTick;
		}

		if (client.getWidget(335, 10) == null && client.getWidget(334, 13) == null)
		{
			tradePassedFirstScreen = false;
		}

		// Prestige mode: bank closed — check for coins left in bank
		if (prestigeMode && prestigeBankOpened && client.getWidget(12, 0) == null)
		{
			prestigeBankOpened = false;
			long bankCoins = countCoinsIn(InventoryID.BANK);
			if (bankCoins > 0)
			{
				incineratedValue = 0;
				saveIncineratedValue();
				cancelPrestige();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Prestige progress LOST! You closed the bank with "
						+ formatGp(bankCoins) + " coins still deposited.", null);
			}
			else
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Prestige still active. Progress: " + formatGp(incineratedValue)
						+ " / " + formatGp(PRESTIGE_COST) + ". Open a bank to continue.", null);
				leaderboardPanel.setPrestigeMode(true, incineratedValue, PRESTIGE_COST);
			}
		}

		// Leaderboard: snapshot wealth while logged in for posting on logout
		if (config.leaderboardEnabled()
			&& client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null)
		{
			lastWealth = wealthCalculator.calculateWealth();
			lastPlayerName = client.getLocalPlayer().getName();
			leaderboardPanel.setPlayerName(lastPlayerName);
			if (!prestigeMode)
			{
				leaderboardPanel.setPrestigeEnabled(lastWealth >= PRESTIGE_COST);
			}

			// Fetch prestige level on first tick after login
			if (pendingPrestigeFetch)
			{
				pendingPrestigeFetch = false;
				leaderboardPanel.triggerRefresh();
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			if (prestigeMode)
			{
				prestigeMode = false;
				prestigeBankOpened = false;
				totalCoinsSnapshot = 0;
				leaderboardPanel.setPrestigeMode(false, incineratedValue, PRESTIGE_COST);
			}
		}

		// Load saved progress on login
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loadIncineratedValue();
			leaderboardPanel.setPrestigeMode(false, incineratedValue, PRESTIGE_COST);
			pendingPrestigeFetch = true;
		}

		if ((event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
			&& config.leaderboardEnabled()
			&& lastPlayerName != null)
		{
			leaderboardClient.postWealth(LEADERBOARD_URL, LEADERBOARD_API_KEY, lastPlayerName, lastWealth);
			lastPlayerName = null;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer() && incineratedValue > 0)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"You died! Prestige progress has been lost.", null);
			incineratedValue = 0;
			saveIncineratedValue();
			if (prestigeMode)
			{
				cancelPrestige();
			}
			else
			{
				leaderboardPanel.setPrestigeMode(false, 0, PRESTIGE_COST);
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.LOOTING_BAG)
		{
			wealthCalculator.updateLootingBagCache();
		}

		if (prestigeMode && prestigeBankOpened && event.getContainerId() == InventoryID.BANK)
		{
			checkBankIncinerationProgress();
		}

		if (client.getWidget(335, 10) != null)
		{
			tradePassedFirstScreen = false;
			tradeItemChangedTick = gameTick;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = Text.standardize(event.getMenuOption());
		final String target = Text.standardize(event.getMenuTarget());

		if (option.equals("destroy") && target.contains("looting bag"))
		{
			wealthCalculator.clearLootingBagCache();
		}

		// Prestige mode: before the first bank visit (no progress yet),
		// cancel if player does anything other than walking to a bank
		if (prestigeMode && !prestigeBankOpened && incineratedValue == 0)
		{
			final boolean isBenign = option.equals("walk here")
				|| option.equals("cancel")
				|| option.equals("examine")
				|| isBankInteraction(option, target);
			if (!isBenign)
			{
				cancelPrestige();
			}
		}

		// Prestige mode: block trading and GE while active
		if (prestigeMode)
		{
			final boolean isTrade = option.contains("trade");
			final boolean isGe = option.contains("exchange") || option.contains("collect")
				|| option.contains("offer") || option.contains("buy") || option.contains("sell");
			if (isTrade || isGe)
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"In order to prestige, you must sacrifice 1B coins.", null);
				pendingBoop = true;
				return;
			}
		}

		if (DISABLE_BANKING && isBankInteraction(option, target))
		{
			if (!prestigeMode)
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Banking is disabled.", null);
				pendingBoop = true;
				return;
			}
		}

		// Prestige mode: in bank, block close if coins remain in bank
		if (prestigeMode && client.getWidget(12, 0) != null && option.equals("close"))
		{
			long bankCoins = countCoinsIn(InventoryID.BANK);
			if (bankCoins > 0)
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"You still have " + formatGp(bankCoins)
						+ " coins in the bank. Withdraw or incinerate them first!", null);
				pendingBoop = true;
				return;
			}
		}

		// Prestige mode: in bank, block withdraw/deposit/destroy of non-coin items
		if (prestigeMode && client.getWidget(12, 0) != null)
		{
			final boolean isCoins = target.contains("coins");
			final boolean isWithdraw = option.startsWith("withdraw");
			final boolean isDeposit = option.startsWith("deposit");
			final boolean isDestroy = option.equals("destroy");
			if (isDestroy && isCoins && incineratedValue >= PRESTIGE_COST)
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"You have already sacrificed enough! Withdraw your remaining coins.", null);
				pendingBoop = true;
				return;
			}
			if ((isWithdraw || isDeposit || isDestroy) && !isCoins)
			{
				event.consume();
				if (isDestroy)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Only coins may be incinerated during prestige.", null);
				}
				pendingBoop = true;
				return;
			}
		}

		final boolean isGeOfferOpen = client.getWidget(465, 24) != null;
		if (isGeOfferOpen)
		{
			final boolean isSubmitLike = option.equals("confirm")
				|| option.equals("submit")
				|| option.equals("place")
				|| option.equals("yes")
				|| option.equals("continue")
				|| option.equals("ok");

			if (isSubmitLike)
			{
				// Block if a price/quantity change happened within the last 1 tick
				if (gameTick - geClickedNonSubmitTick < 1)
				{
					event.consume();
					return;
				}

				// Block if a custom price/quantity chatbox was recently opened;
				// the widget text lags behind the actual entered value.
				if (gameTick - geCustomEntryTick < GE_CUSTOM_ENTRY_COOLDOWN)
				{
					event.consume();
					return;
				}

				final String reason = validateGeOffer();
				if (reason != null)
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", reason, null);
					pendingBoop = true;
					geClickedNonSubmitTick = gameTick;
				}
				return;
			}

			// Track custom price/quantity entry separately with a longer cooldown
			if (option.equals("enter price") || option.equals("enter quantity"))
			{
				geCustomEntryTick = gameTick;
			}

			// Any other click while the GE offer screen is open can change the offer.
			geClickedNonSubmitTick = gameTick;
		}

		final Widget tradeScreen1 = client.getWidget(335, 10);
		final Widget tradeScreen2 = client.getWidget(334, 13);
		final boolean isTradeOpen = tradeScreen1 != null || tradeScreen2 != null;
		if (isTradeOpen)
		{
			if (option.contains("accept"))
			{
				// Block if another trade click happened this exact tick (same-tick exploit guard)
				if (tradeClickedNonAcceptTick == gameTick)
				{
					event.consume();
					return;
				}

				// First trade screen: validate from offer widgets
				if (tradeScreen1 != null)
				{
					// Block if items changed within the last 5 ticks (~3s stabilisation window)
					if (gameTick - tradeItemChangedTick < 5)
					{
						event.consume();
						return;
					}
					final String reason = validateTrade();
					if (reason != null)
					{
						tradePassedFirstScreen = false;
						event.consume();
						client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", reason, null);
						pendingBoop = true;
					}
					else
					{
						tradePassedFirstScreen = true;
					}
					return;
				}

				// Second trade screen: block if first screen validation failed
				if (tradeScreen2 != null)
				{
					if (!tradePassedFirstScreen)
					{
						event.consume();
					}
				}
				return;
			}

			// Any other click while the trade screen is open can change the offer.
			tradeClickedNonAcceptTick = gameTick;
		}
	}

	private String validateGeOffer()
	{
		final Widget offer = client.getWidget(465, 26);
		if (offer == null)
		{
			return null;
		}

		final List<Widget> widgets = flattenWidgetTree(offer);

		// Find the actual item being traded (skip GE placeholder 6512)
		Integer itemId = null;
		for (Widget w : widgets)
		{
			final int id = w.getItemId();
			if (id > 0 && id != 6512)
			{
				itemId = id;
				break;
			}
		}
		if (itemId == null)
		{
			return null;
		}

		// Find the per-item price from text containing "coins"
		Integer price = null;
		for (Widget w : widgets)
		{
			final String t = w.getText();
			if (t != null && t.contains("coin"))
			{
				final Integer parsed = parseFirstInteger(t);
				if (parsed != null)
				{
					price = parsed;
					break;
				}
			}
		}
		if (price == null)
		{
			return null;
		}

		final int market = itemManager.getItemPrice(itemId);
		if (market <= 0)
		{
			return "GE offer blocked: market price unavailable.";
		}

		final long deltaPct = (long) Math.floor(market * (LIMIT_PCT / 100.0));
		final long delta = Math.min(deltaPct, ABSOLUTE_CAP_GP);
		final long min = market - delta;
		final long max = market + delta;
		if (price < min || price > max)
		{
			final long clamped = price < min ? min : max;
			return "GE offer blocked: Try " + String.format("%,d", clamped) + " gp.";
		}

		return null;
	}

	private long computeOfferValue(Widget offerRoot)
	{
		long total = 0;
		final Widget[] children = offerRoot.getChildren();
		if (children == null)
		{
			return 0;
		}
		for (Widget w : children)
		{
			if (w == null || w.isHidden())
			{
				continue;
			}
			final int id = w.getItemId();
			final int qty = w.getItemQuantity();
			if (id <= 0 || qty <= 0)
			{
				continue;
			}
			final int price = (id == 995) ? 1 : (id == 13204) ? 1000 : itemManager.getItemPrice(id);
			if (price <= 0)
			{
				return -1;
			}
			total += (long) price * qty;
		}
		return total;
	}

	private String findUnpricedItem(Widget offerRoot)
	{
		final Widget[] children = offerRoot.getChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget w : children)
		{
			if (w == null || w.isHidden())
			{
				continue;
			}
			final int id = w.getItemId();
			final int qty = w.getItemQuantity();
			if (id <= 0 || qty <= 0)
			{
				continue;
			}
			if (id == 995 || id == 13204)
			{
				continue;
			}
			final int price = itemManager.getItemPrice(id);
			if (price <= 0)
			{
				final ItemComposition comp = itemManager.getItemComposition(id);
				return comp != null ? comp.getName() : ("item #" + id);
			}
		}
		return null;
	}

	private String validateTrade()
	{
		final Widget ourOffer = client.getWidget(335, 25);
		final Widget theirOffer = client.getWidget(335, 28);
		if (ourOffer == null || theirOffer == null)
		{
			return null;
		}

		final long ourValue = computeOfferValue(ourOffer);
		final long theirValue = computeOfferValue(theirOffer);

		if (ourValue < 0 || theirValue < 0)
		{
			String itemName = findUnpricedItem(ourOffer);
			if (itemName == null)
			{
				itemName = findUnpricedItem(theirOffer);
			}
			return "Trade blocked: " + (itemName != null ? itemName : "an item") + " has no known price.";
		}

		if (ourValue == 0 && theirValue == 0)
		{
			return null;
		}

		if (theirValue == 0)
		{
			return "Trade blocked: Your offer (" + String.format("%,d", ourValue) + " gp) is too high. Your trade partner has not offered anything of value yet.";
		}

		final long pctDelta = (long) Math.floor(theirValue * (LIMIT_PCT / 100.0));
		final long allowed = Math.min(pctDelta, ABSOLUTE_CAP_GP);
		final long min = theirValue - allowed;
		final long max = theirValue + allowed;

		if (ourValue < min || ourValue > max)
		{
			final String direction = ourValue < min ? "low" : "high";
			return "Trade blocked: Your offer (" + String.format("%,d", ourValue) + " gp) is too " + direction + ". Try an offer between " + String.format("%,d", min) + " - " + String.format("%,d", max) + " gp.";
		}

		return null;
	}

	private List<Widget> flattenWidgetTree(Widget root)
	{
		final List<Widget> out = new ArrayList<>();
		final ArrayDeque<Widget> q = new ArrayDeque<>();
		q.add(root);
		while (!q.isEmpty())
		{
			final Widget w = q.removeFirst();
			out.add(w);
			final Widget[] children = w.getChildren();
			if (children != null)
			{
				for (Widget c : children)
				{
					if (c != null)
					{
						q.addLast(c);
					}
				}
			}
			final Widget[] staticChildren = w.getStaticChildren();
			if (staticChildren != null)
			{
				for (Widget c : staticChildren)
				{
					if (c != null)
					{
						q.addLast(c);
					}
				}
			}
		}
		return out;
	}

	private static Integer parseFirstInteger(String text)
	{
		if (text == null)
		{
			return null;
		}
		final String clean = Text.removeTags(text).replace('\u00A0', ' ').replace(",", "");
		final Matcher m = Pattern.compile("(\\d+)").matcher(clean);
		if (!m.find())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(m.group(1));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (skullModIconIndex < 0)
		{
			return;
		}

		if (event.getType() == ChatMessageType.PRIVATECHAT
			|| event.getType() == ChatMessageType.PRIVATECHATOUT
			|| event.getType() == ChatMessageType.MODPRIVATECHAT)
		{
			return;
		}

		if (event.getMessageNode() == null)
		{
			return;
		}

		final String localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (localName == null || event.getName() == null)
		{
			return;
		}

		final String name = Text.removeTags(event.getName()).replace('\u00A0', ' ');
		if (!localName.equals(name))
		{
			return;
		}

		event.getMessageNode().setName("<img=" + skullModIconIndex + "> " + event.getName());
		client.refreshChat();
	}

	private void registerChatSkullIcon(int fillColor)
	{
		final IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons == null)
		{
			return;
		}

		priorModIcons = modIcons;
		skullModIconIndex = modIcons.length;

		final IndexedSprite skull = createSkullIndexedSprite(fillColor);
		final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
		newModIcons[skullModIconIndex] = skull;
		client.setModIcons(newModIcons);
	}

	private void unregisterChatSkullIcon()
	{
		if (priorModIcons != null)
		{
			client.setModIcons(priorModIcons);
		}
		priorModIcons = null;
		skullModIconIndex = -1;
	}

	private void reregisterChatSkull()
	{
		if (skullModIconIndex < 0)
		{
			return;
		}
		final int color = prestigeSkullColor();
		final IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons != null && skullModIconIndex < modIcons.length)
		{
			modIcons[skullModIconIndex] = createSkullIndexedSprite(color);
			client.setModIcons(modIcons);
		}
		// Update the overlay skull (by display name) to match
		chatPromptSkullOverlay.updateSkullColor(prestigeSkullAwtColor(), playerPrestige >= 9, playerPrestige >= 10);
	}

	private java.awt.Color prestigeSkullAwtColor()
	{
		final int c = prestigeSkullColor();
		if (c <= 0x000003)
		{
			return java.awt.Color.BLACK;
		}
		return new java.awt.Color(c);
	}

	private int prestigeSkullColor()
	{
		switch (playerPrestige)
		{
			case 0: return 0xFFFFFF; // White (non-prestige)
			case 1: return 0xFF0000; // Red
			case 2: return 0xFF7F00; // Orange
			case 3: return 0xFFFF00; // Yellow
			case 4: return 0x00FF00; // Green
			case 5: return 0x0064FF; // Blue
			case 6: return 0x4B0082; // Indigo
			case 7: return 0x8B00FF; // Violet
			case 8: return 0x000001;  // Inverted (white outline, black fill)
			case 9: return 0x000002;  // Inverted + horns
			default: return 0x000003; // Gilded horned (gold outline, black fill, red eyes)
		}
	}

	private IndexedSprite createSkullIndexedSprite(int fillColor)
	{
		final boolean inverted = fillColor >= 0x000001 && fillColor <= 0x000003;
		final boolean horned = fillColor == 0x000002 || fillColor == 0x000003;
		final boolean gilded = fillColor == 0x000003;
		final int w = 12;
		final int h = 12;
		final int[] argb = new int[w * h];
		Arrays.fill(argb, 0);

		final int GOLD = 0xFFFFD700;
		final int RED  = 0xFFFF0000;
		final int OUTLINE = gilded ? GOLD : (inverted ? 0xFFFFFFFF : 0xFF000000);
		final int FILL    = inverted ? 0xFF000000 : (0xFF000000 | fillColor);
		final int DETAIL  = gilded ? GOLD : (inverted ? 0xFFFFFFFF : 0xFF000000);
		final int EYES    = gilded ? RED : DETAIL;

		if (horned)
		{
			// Horns: 1px tip, widening down into skull sides
			fill(argb, w, 0, 0, 1, 1, OUTLINE);  // left tip
			fill(argb, w, 0, 1, 2, 1, OUTLINE);  // left widen
			fill(argb, w, 1, 2, 2, 1, OUTLINE);  // left base
			fill(argb, w, 11, 0, 1, 1, OUTLINE); // right tip
			fill(argb, w, 10, 1, 2, 1, OUTLINE); // right widen
			fill(argb, w, 9, 2, 2, 1, OUTLINE);  // right base
			// Skull body (horns merge into sides)
			fill(argb, w, 3, 2, 6, 1, OUTLINE);
			fill(argb, w, 2, 3, 8, 1, OUTLINE);
			fill(argb, w, 1, 3, 1, 5, OUTLINE);  // left side column
			fill(argb, w, 10, 3, 1, 5, OUTLINE); // right side column
			fill(argb, w, 2, 4, 8, 4, OUTLINE);
			fill(argb, w, 2, 8, 8, 1, OUTLINE);
			fill(argb, w, 3, 9, 6, 1, OUTLINE);

			fill(argb, w, 3, 3, 6, 1, FILL);
			fill(argb, w, 2, 4, 8, 4, FILL);
			fill(argb, w, 3, 8, 6, 1, FILL);

			fill(argb, w, 3, 5, 2, 2, EYES);
			fill(argb, w, 7, 5, 2, 2, EYES);
			fill(argb, w, 5, 7, 2, 1, DETAIL);

			fill(argb, w, 4, 9, 1, 1, DETAIL);
			fill(argb, w, 6, 9, 1, 1, DETAIL);
		}
		else
		{
			fill(argb, w, 3, 1, 6, 1, OUTLINE);
			fill(argb, w, 2, 2, 8, 1, OUTLINE);
			fill(argb, w, 1, 3, 10, 5, OUTLINE);
			fill(argb, w, 2, 8, 8, 1, OUTLINE);
			fill(argb, w, 3, 9, 6, 1, OUTLINE);

			fill(argb, w, 3, 2, 6, 1, FILL);
			fill(argb, w, 2, 3, 8, 5, FILL);
			fill(argb, w, 3, 8, 6, 1, FILL);

			fill(argb, w, 3, 4, 2, 2, DETAIL);
			fill(argb, w, 7, 4, 2, 2, DETAIL);
			fill(argb, w, 5, 6, 2, 1, DETAIL);

			fill(argb, w, 4, 9, 1, 1, DETAIL);
			fill(argb, w, 6, 9, 1, 1, DETAIL);
		}

		// Build palette from unique colors used
		final java.util.LinkedHashMap<Integer, Byte> paletteMap = new java.util.LinkedHashMap<>();
		paletteMap.put(0, (byte) 0); // transparent
		for (int c : argb)
		{
			if ((c >>> 24) != 0 && !paletteMap.containsKey(c & 0x00FFFFFF))
			{
				paletteMap.put(c & 0x00FFFFFF, (byte) paletteMap.size());
			}
		}
		final int[] palette = new int[paletteMap.size()];
		int idx = 0;
		for (int key : paletteMap.keySet())
		{
			palette[idx++] = key;
		}

		final IndexedSprite sprite = client.createIndexedSprite();
		sprite.setWidth(w);
		sprite.setHeight(h);
		sprite.setOriginalWidth(w);
		sprite.setOriginalHeight(h);
		sprite.setOffsetX(0);
		sprite.setOffsetY(0);

		sprite.setPalette(palette);

		final byte[] pixels = new byte[w * h];
		for (int i = 0; i < argb.length; i++)
		{
			final int c = argb[i];
			if ((c >>> 24) == 0)
			{
				pixels[i] = 0;
			}
			else
			{
				final Byte pi = paletteMap.get(c & 0x00FFFFFF);
				pixels[i] = pi != null ? pi : 0;
			}
		}
		sprite.setPixels(pixels);
		return sprite;
	}

	private static BufferedImage createNavIcon()
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

	private static void fill(int[] argb, int w, int x, int y, int rw, int rh, int color)
	{
		for (int yy = y; yy < y + rh; yy++)
		{
			for (int xx = x; xx < x + rw; xx++)
			{
				final int idx = yy * w + xx;
				if (idx >= 0 && idx < argb.length)
				{
					argb[idx] = color;
				}
			}
		}
	}
}

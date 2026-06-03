package com.ultimatepkmanmode;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedSprite;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.SoundEffectVolume;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Ultimate Normie Mode",
	description = "Strict UIM-style restrictions on banking, trading, and the GE, with a roguelike Upgrade Mode"
)
public class UltimateNormiePlugin extends Plugin
{
	private static final long ABSOLUTE_CAP_GP = 5_000_000;
	private static final int GE_CUSTOM_ENTRY_COOLDOWN = 2;
	private static final int VARCI_INPUT_TYPE = 5;
	private static final int COINS = 995;
	private static final int PLATINUM_TOKEN = 13204;
	// Sound played when an upgrade activates. OSRS sound effect 2396 is the level-up
	// fanfare commonly referenced by plugins. Note: the in-game level-up jingle proper
	// is delivered via the music subsystem rather than playSoundEffect; this is the
	// closest SFX equivalent. If silent or wrong, try 3813 (TOWN_CRIER_BELL_DING).
	private static final int SOUND_UPGRADE_UNLOCKED = 2396;

	// Tick state
	private int gameTick = 0;
	private int geClickedNonSubmitTick = -1;
	private int geCustomEntryTick = -100;
	private int tradeClickedNonAcceptTick = -1;
	private boolean tradePassedFirstScreen = false;
	private boolean pendingBoop = false;
	private int tradeItemChangedTick = -1;

	// Upgrade-mode bank-saving tracking. We just need the previous coin totals so
	// that container-changed events can detect destruction (incineration) by seeing
	// the bank coin count drop with no matching inventory increase.
	private boolean savingBankOpen = false;
	private long lastBankCoins = 0;
	private long lastInvCoins = 0;

	// Chat skull
	private int skullModIconIndex = -1;
	private int lastUnlockedCount = -1;

	// Set when the player dies; wipe message is printed after the death chat fires.
	private boolean pendingDeathWipeMessage = false;
	private boolean pendingPurgatoryWipeMessage = false;
	private boolean pendingUnmBankToPurgatoryMessage = false;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private TradeBalanceOverlay tradeBalanceOverlay;

	@Inject
	private WealthCalculator wealthCalculator;

	@Inject
	private UpgradeManager upgradeManager;

	@Inject
	private ChatPromptSkullOverlay chatPromptSkullOverlay;

	private UpgradePanel upgradePanel;
	private NavigationButton navButton;

	/** Used by the trade-balance overlay to render the active tolerance. */
	public int getLimitPct()
	{
		return upgradeManager.isTradeUnlocked() ? upgradeManager.getTradeTolerancePct() : 10;
	}

	public long getAbsoluteCapGp()
	{
		return ABSOLUTE_CAP_GP;
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(tradeBalanceOverlay);
		overlayManager.add(chatPromptSkullOverlay);

		try
		{
			upgradePanel = new UpgradePanel(upgradeManager);
		}
		catch (Exception e)
		{
			log.error("Failed to create UpgradePanel", e);
			throw e;
		}
		upgradeManager.setOnChange(() ->
		{
			try
			{
				upgradePanel.rebuild();
				// Skull tier reflects how many upgrades the player has UNLOCKED this life,
				// not how many are currently toggled active. Toggling a gateway off must not
				// regress the skull \u2014 the unlock is a permanent (until-death) milestone.
				final int newCount = upgradeManager.getUnlockedCount();
				if (newCount != lastUnlockedCount)
				{
					lastUnlockedCount = newCount;
					chatPromptSkullOverlay.invalidate();
					clientThread.invokeLater(this::reregisterChatSkull);
				}
			}
			catch (Exception e)
			{
				log.error("Error in onChange callback", e);
			}
		});

		navButton = NavigationButton.builder()
			.tooltip("UNM Upgrades")
			.icon(UpgradePanel.createNavIcon())
			.panel(upgradePanel)
			.priority(10)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(this::registerChatSkullIcon);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(tradeBalanceOverlay);
		overlayManager.remove(chatPromptSkullOverlay);
		clientToolbar.removeNavigation(navButton);
		upgradeManager.setOnChange(null);
		clientThread.invokeLater(this::unregisterChatSkullIcon);
		resetSavingSession();
	}

	private void resetSavingSession()
	{
		savingBankOpen = false;
		lastBankCoins = 0;
		lastInvCoins = 0;
	}

	// ----------------- Helpers -----------------

	private static boolean isBankInteraction(String option, String target)
	{
		if (option == null)
		{
			return false;
		}
		final String t = target == null ? "" : target;

		if (option.startsWith("bank"))
		{
			return true;
		}
		if (option.startsWith("deposit") && t.contains("deposit box"))
		{
			return true;
		}
		if (option.equals("bank-all") || option.contains("send to bank"))
		{
			return true;
		}
		if (option.equals("private") && t.contains("storage unit"))
		{
			return true;
		}
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

	private void startSavingSession()
	{
		lastBankCoins = countCoinsIn(InventoryID.BANK);
		lastInvCoins = countCoinsIn(InventoryID.INV);
	}

	/**
	 * Re-reads bank+inv coin totals after a container change and credits any incinerated
	 * coins to the current saving goal. We detect incineration as: the bank coin count
	 * dropped while the total (bank+inv) also dropped \u2014 i.e., coins left the bank without
	 * landing in the inventory.
	 */
	private void onSavingContainerChanged()
	{
		final long bankNow = countCoinsIn(InventoryID.BANK);
		final long invNow = countCoinsIn(InventoryID.INV);
		final long bankDelta = bankNow - lastBankCoins;
		final long destroyed = (lastBankCoins + lastInvCoins) - (bankNow + invNow);

		if (destroyed > 0 && bankDelta < 0)
		{
			final UpgradeManager.ApplyResult r = upgradeManager.applyBurned(destroyed);
			if (r.applied > 0)
			{
				final String dest = r.activatedUpgrade != null ? r.activatedUpgrade.getDisplayName() : "your goal";
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Sacrificed " + formatGp(r.applied) + " toward " + dest + ".", null);
			}
			if (r.wasActivated())
			{
				client.runScript(29); // close the bank
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Upgrade unlocked: " + r.activatedUpgrade.getDisplayName() + "!", null);
				client.playSoundEffect(SOUND_UPGRADE_UNLOCKED, SoundEffectVolume.HIGH);
			}
		}

		lastBankCoins = bankNow;
		lastInvCoins = invNow;
	}

	// ----------------- Subscribers -----------------

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// The RS-profile-scoped config (where unlocks/progress are persisted) is only
		// guaranteed to be loaded once this event fires; LOGGED_IN can arrive first.
		// Reloading here ensures unlocks survive a client restart.
		upgradeManager.load();
		lastUnlockedCount = upgradeManager.getUnlockedCount();
		clientThread.invokeLater(this::reregisterChatSkull);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			upgradeManager.load();
			lastUnlockedCount = upgradeManager.getUnlockedCount();
			clientThread.invokeLater(this::reregisterChatSkull);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		final int g = event.getGroupId();
		final boolean isBankWidget = g == 12 || g == 192;

		if (isBankWidget)
		{
			// If a goal is selected and the main bank is opening, start the saving session
			// for coin tracking. This must run BEFORE the banking-unlocked early return so
			// that users who already own Banking Unlock can still progress later upgrades.
			final boolean anyBankPass = upgradeManager.getConsumableCharges(Upgrade.DEPOSIT_PASS) > 0
				|| upgradeManager.getConsumableCharges(Upgrade.WITHDRAWAL_PASS) > 0;
			if (g == 12 && (upgradeManager.getSelectedGoal() != null || anyBankPass) && !savingBankOpen)
			{
				savingBankOpen = true;
				startSavingSession();
				final Upgrade goalU = upgradeManager.getSelectedGoal();
				if (goalU != null)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Saving for " + goalU.getDisplayName()
							+ ". Make sure the Bank Incinerator is enabled, then drag coins onto it to make progress.", null);
				}
				else
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Bank open. You may redeem your pending one-shot passes.", null);
				}
			}

			if (upgradeManager.isBankingUnlocked())
			{
				return; // banking unlocked: bank acts normally (saving session still tracks coins)
			}
			// Banking locked: allow the main bank when actively saving for a goal OR when
			// a one-shot deposit/withdrawal pass is pending. Deposit boxes (192) have no
			// incinerator, so always block when banking is locked.
			if (g == 12 && (upgradeManager.getSelectedGoal() != null || anyBankPass))
			{
				return;
			}

			client.runScript(29);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Banking is disabled.", null);
			pendingBoop = true;
			return;
		}

		// GE offer screen
		if (g == 465)
		{
			if (upgradeManager.isActive(Upgrade.GE_USE))
			{
				// Permanent GE Use upgrade active — allow normally.
			}
			else if (upgradeManager.hasTemporaryGeSession())
			{
				// Already inside a temporary session from a GE pass.
			}
			else if (upgradeManager.consumeGePassCharge())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"GE Pass consumed. GE access will be revoked when you close the Grand Exchange.", null);
			}
			else
			{
				client.runScript(29);
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Grand Exchange is disabled.", null);
				pendingBoop = true;
				return;
			}
		}

		// Trade screens
		if ((g == 335 || g == 334) && !upgradeManager.isTradeUnlocked())
		{
			client.runScript(29);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Trading is disabled.", null);
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

		// While a chatbox input is active on the GE offer screen, keep the
		// custom-entry cooldown alive so it counts down only after dismissal.
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

		// Detect GE interface fully closed and revoke any temporary GE session.
		// Both group 383 (main GE window) and 465 (offer sub-screen) must be gone.
		if (upgradeManager.hasTemporaryGeSession() && !upgradeManager.isActive(Upgrade.GE_USE)
			&& client.getWidget(383, 0) == null && client.getWidget(465, 0) == null)
		{
			upgradeManager.revokeTemporaryGe();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Grand Exchange closed. GE access revoked.", null);
		}

		// If the player cleared their goal mid-session (and no consumable charges remain),
		// force-close the bank cleanly.
		final boolean anyPendingCharge = upgradeManager.getConsumableCharges(Upgrade.DEPOSIT_PASS) > 0
			|| upgradeManager.getConsumableCharges(Upgrade.WITHDRAWAL_PASS) > 0;
		if (savingBankOpen
			&& !upgradeManager.isBankingUnlocked()
			&& upgradeManager.getSelectedGoal() == null
			&& !anyPendingCharge
			&& client.getWidget(12, 0) != null)
		{
			client.runScript(29);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Goal cleared. Banking closed.", null);
			resetSavingSession();
		}

		// Detect bank-close while saving. With the simplified model nothing punitive
		// happens \u2014 we just stop tracking until the bank is reopened.
		if (savingBankOpen && client.getWidget(12, 0) == null)
		{
			resetSavingSession();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.LOOTING_BAG)
		{
			wealthCalculator.updateLootingBagCache();
		}

		if (savingBankOpen
			&& (event.getContainerId() == InventoryID.BANK
				|| event.getContainerId() == InventoryID.INV))
		{
			onSavingContainerChanged();
		}

		if (client.getWidget(335, 10) != null)
		{
			tradePassedFirstScreen = false;
			tradeItemChangedTick = gameTick;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		// Check if Purgatory has items before wipe (to show message after death)
		pendingPurgatoryWipeMessage = !upgradeManager.getPurgatory().isEmpty();
		// Check if UNM bank has items before wipe (to show message after death)
		pendingUnmBankToPurgatoryMessage = !upgradeManager.getUnmBank().isEmpty();
		upgradeManager.hardWipe();
		resetSavingSession();
		pendingDeathWipeMessage = true;
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

		final boolean banking = upgradeManager.isBankingUnlocked();
		final boolean geUnlocked = upgradeManager.isGeUnlocked();
		final boolean tradeUnlocked = upgradeManager.isTradeUnlocked();
		final Upgrade goal = upgradeManager.getSelectedGoal();

		// Block "trade with" at the source if trade isn't unlocked.
		if (!tradeUnlocked && option.equals("trade with"))
		{
			event.consume();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Trading is disabled.", null);
			pendingBoop = true;
			return;
		}

		// Banking interactions.
		final boolean hasBankPasses = upgradeManager.getConsumableCharges(Upgrade.DEPOSIT_PASS) > 0
			|| upgradeManager.getConsumableCharges(Upgrade.WITHDRAWAL_PASS) > 0;
		if (isBankInteraction(option, target))
		{
			if (banking)
			{
				// Banking upgrade active: allow normally.
			}
			else if (goal != null && !target.contains("deposit box"))
			{
				// Saving mode: allow opening main bank for incineration. In-bank ops filtered below.
			}
			else if (hasBankPasses && !target.contains("deposit box"))
			{
				// One-shot bank pass(es) pending: let the player open the main bank to redeem.
				// In-bank ops filtered below.
			}
			else
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Banking is disabled.", null);
				pendingBoop = true;
				return;
			}
		}

		// GE menu blocks (when GE not unlocked).
		if (!geUnlocked)
		{
			if (option.startsWith("collect-"))
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Grand Exchange is disabled.", null);
				pendingBoop = true;
				return;
			}
			// Note: the bare "grand exchange" substring used to be checked here too, but it
			// false-fires on Spirit Tree teleports whose menu target/option contains "Grand
			// Exchange" as a destination name. The clerk/booth/banker checks below already
			// cover the real GE NPCs and objects.
			final boolean targetIsGeOrBanker = target.contains("banker")
				|| target.contains("bank chest")
				|| target.contains("bank booth")
				|| target.contains("exchange clerk")
				|| target.contains("exchange booth");
			if (targetIsGeOrBanker
				&& (option.equals("collect") || option.equals("exchange") || option.equals("history")))
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Grand Exchange is disabled.", null);
				pendingBoop = true;
				return;
			}
		}

		// In-bank operations while banking is locked. Only coin ops (and incineration of
		// coins) are normally allowed; one-shot deposit/withdrawal passes can be redeemed
		// here to allow a single non-coin deposit or withdrawal action.
		if (!banking && (goal != null || hasBankPasses) && client.getWidget(12, 0) != null)
		{
			final boolean isCoins = target.contains("coins");
			final boolean isWithdraw = option.startsWith("withdraw");
			final boolean isDeposit = option.startsWith("deposit");
			final boolean isDestroy = option.equals("destroy");

			if (isDeposit && !isCoins)
			{
				final boolean hasDepositPass = upgradeManager.getConsumableCharges(Upgrade.DEPOSIT_PASS) > 0;
				if (!hasDepositPass)
				{
					event.consume();
					pendingBoop = true;
					return;
				}
				// Only "deposit-1" is permitted when using a Deposit Pass.
				if (!option.equals("deposit-1"))
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Deposit Pass only allows depositing one item.", null);
					pendingBoop = true;
					return;
				}
				final int itemId = event.getItemId();
				if (upgradeManager.consumeCharge(Upgrade.DEPOSIT_PASS))
				{
					// Add the item to the UNMbank for later withdrawal.
					if (itemId > 0)
					{
						upgradeManager.addToUnmBank(itemId, 1);
						// Cache the item name for UI display (this is on the client thread).
						final net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
						if (comp != null)
						{
							upgradeManager.setUnmBankItemName(itemId, comp.getName());
						}
					}
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Deposit Pass consumed. Item added to UNM bank.", null);
					return; // allow the deposit through
				}
				event.consume();
				pendingBoop = true;
				return;
			}
			if (isWithdraw && !isCoins)
			{
				final boolean hasWithdrawalPass = upgradeManager.getConsumableCharges(Upgrade.WITHDRAWAL_PASS) > 0;
				if (!hasWithdrawalPass)
				{
					event.consume();
					pendingBoop = true;
					return;
				}
				// Only "withdraw-1" is permitted when using a Withdrawal Pass.
				if (!option.equals("withdraw-1"))
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Withdrawal Pass only allows withdrawing one item.", null);
					pendingBoop = true;
					return;
				}
				final int itemId = event.getItemId();
				// Check if the item exists in the UNM bank.
				if (!upgradeManager.hasInUnmBank(itemId))
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Item not in UNM bank. You can only withdraw items deposited via Deposit Pass.", null);
					pendingBoop = true;
					return;
				}
				if (upgradeManager.consumeCharge(Upgrade.WITHDRAWAL_PASS))
				{
					// Remove the item from the UNM bank.
					if (itemId > 0)
					{
						upgradeManager.removeFromUnmBank(itemId, 1);
					}
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Withdrawal Pass consumed. Item removed from UNM bank.", null);
					return; // allow the withdraw through
				}
				event.consume();
				pendingBoop = true;
				return;
			}
			if (isDestroy && !isCoins)
			{
				event.consume();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Only coins may be incinerated while saving.", null);
				pendingBoop = true;
				return;
			}
		}

		// GE offer-submit validation.
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
				if (!geUnlocked)
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Grand Exchange is disabled.", null);
					pendingBoop = true;
					return;
				}
				if (upgradeManager.isGeEnforcementOff())
				{
					return; // submission allowed without validation
				}
				if (gameTick - geClickedNonSubmitTick < 1)
				{
					event.consume();
					return;
				}
				if (gameTick - geCustomEntryTick < GE_CUSTOM_ENTRY_COOLDOWN)
				{
					event.consume();
					return;
				}
				final String reason = validateGeOffer(upgradeManager.getGeTolerancePct());
				if (reason != null)
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", reason, null);
					pendingBoop = true;
					geClickedNonSubmitTick = gameTick;
				}
				return;
			}

			if (option.equals("enter price") || option.equals("enter quantity"))
			{
				geCustomEntryTick = gameTick;
			}
			geClickedNonSubmitTick = gameTick;
		}

		// Trade accept validation.
		final Widget tradeScreen1 = client.getWidget(335, 10);
		final Widget tradeScreen2 = client.getWidget(334, 13);
		final boolean isTradeOpen = tradeScreen1 != null || tradeScreen2 != null;
		if (isTradeOpen)
		{
			if (option.contains("accept"))
			{
				if (!tradeUnlocked)
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Trading is disabled.", null);
					pendingBoop = true;
					return;
				}
				if (upgradeManager.isTradeEnforcementOff())
				{
					return;
				}
				if (tradeClickedNonAcceptTick == gameTick)
				{
					event.consume();
					return;
				}
				if (tradeScreen1 != null)
				{
					if (gameTick - tradeItemChangedTick < 5)
					{
						event.consume();
						return;
					}
					final String reason = validateTrade(upgradeManager.getTradeTolerancePct());
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
				if (tradeScreen2 != null && !tradePassedFirstScreen)
				{
					event.consume();
				}
				return;
			}
			tradeClickedNonAcceptTick = gameTick;
		}
	}

	// ----------------- Validation -----------------

	private String validateGeOffer(int tolerancePct)
	{
		final Widget offer = client.getWidget(465, 26);
		if (offer == null)
		{
			return null;
		}

		final List<Widget> widgets = flattenWidgetTree(offer);

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

		final long deltaPct = (long) Math.floor(market * (tolerancePct / 100.0));
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
			final int price = (id == COINS) ? 1 : (id == PLATINUM_TOKEN) ? 1000 : itemManager.getItemPrice(id);
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
			if (id == COINS || id == PLATINUM_TOKEN)
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

	private String validateTrade(int tolerancePct)
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
			return "Trade blocked: Your offer (" + String.format("%,d", ourValue)
				+ " gp) is too high. Your trade partner has not offered anything of value yet.";
		}

		final long pctDelta = (long) Math.floor(theirValue * (tolerancePct / 100.0));
		final long allowed = Math.min(pctDelta, ABSOLUTE_CAP_GP);
		final long min = theirValue - allowed;
		final long max = theirValue + allowed;

		if (ourValue < min || ourValue > max)
		{
			final String direction = ourValue < min ? "low" : "high";
			return "Trade blocked: Your offer (" + String.format("%,d", ourValue) + " gp) is too " + direction
				+ ". Try an offer between " + String.format("%,d", min) + " - " + String.format("%,d", max) + " gp.";
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

	// ----------------- Chat skull -----------------

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Print wipe message immediately after "Oh dear, you are dead!" fires.
		// The death message can arrive as GAMEMESSAGE or SPAM depending on game state.
		if (pendingDeathWipeMessage
			&& (event.getType() == ChatMessageType.GAMEMESSAGE
				|| event.getType() == ChatMessageType.SPAM)
			&& event.getMessage().toLowerCase().contains("oh dear, you are dead"))
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"All UNM progress and active upgrades have been wiped.", null);
			// Only show Purgatory message if UNM bank had items (checked before hardWipe)
			if (pendingUnmBankToPurgatoryMessage)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Your UNM bank has been sent to Purgatory.", null);
			}
			// Show Purgatory wipe message if Purgatory had items
			if (pendingPurgatoryWipeMessage)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Your Purgatory items have been wiped and are no longer retrievable.", null);
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Please enjoy one free GE Pass. Use it wisely.", null);
			pendingDeathWipeMessage = false;
			pendingPurgatoryWipeMessage = false;
			pendingUnmBankToPurgatoryMessage = false;
		}

		if (skullModIconIndex < 0)
		{
			return;
		}
		if (event.getMessageNode() == null)
		{
			return;
		}
		// Skip private/clan/etc. message types where prepending the skull would interfere.
		if (event.getType() == ChatMessageType.PRIVATECHAT
			|| event.getType() == ChatMessageType.PRIVATECHATOUT
			|| event.getType() == ChatMessageType.MODPRIVATECHAT)
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
		event.getMessageNode().setName("<img=" + skullModIconIndex + ">" + event.getName());
	}

	private void registerChatSkullIcon()
	{
		final IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons == null)
		{
			return;
		}
		skullModIconIndex = modIcons.length;
		final IndexedSprite skull = createSkullIndexedSprite();
		final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
		newModIcons[skullModIconIndex] = skull;
		client.setModIcons(newModIcons);
	}

	private void unregisterChatSkullIcon()
	{
		if (skullModIconIndex >= 0)
		{
			final IndexedSprite[] current = client.getModIcons();
			if (current != null && skullModIconIndex < current.length)
			{
				client.setModIcons(Arrays.copyOf(current, skullModIconIndex));
			}
		}
		skullModIconIndex = -1;
	}

	private void reregisterChatSkull()
	{
		if (skullModIconIndex < 0)
		{
			return;
		}
		final IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons != null && skullModIconIndex < modIcons.length)
		{
			modIcons[skullModIconIndex] = createSkullIndexedSprite();
			client.setModIcons(modIcons);
		}
	}

	/**
	 * Renders a 12x12 skull icon. One distinct visual per active upgrade (0..7):
	 *   0 white | 1 yellow | 2 orange | 3 red | 4 red+horns | 5 red+horns+gold-trim |
	 *   6 black | 7 black+horns+red-eyes
	 */
	private IndexedSprite createSkullIndexedSprite()
	{
		final int unlocked = upgradeManager.getUnlockedCount();
		final int tier = Math.max(0, Math.min(unlocked, 7));
		final boolean horned = (tier == 4) || (tier == 5) || (tier == 7);
		final boolean gilded = tier == 5;
		final boolean blackTheme = tier >= 6;
		final boolean redEyes = tier == 7;
		final int fillColor = pickSkullFillColor(tier);

		final int w = 12;
		final int h = 12;
		final int[] argb = new int[w * h];
		Arrays.fill(argb, 0);

		final int GOLD = 0xFFFFD700;
		final int RED  = 0xFFFF0000;
		final int OUTLINE = gilded ? GOLD : 0xFF000000;
		final int FILL    = 0xFF000000 | fillColor;
		// Black-themed skulls need contrasting (white) interior detail so the eye sockets
		// and teeth are visible against the black fill; everything else keeps black detail.
		final int DETAIL  = gilded ? GOLD : (blackTheme ? 0xFFFFFFFF : 0xFF000000);
		final int EYES    = redEyes ? RED : DETAIL;

		if (horned)
		{
			fill(argb, w, 0, 0, 1, 1, OUTLINE);
			fill(argb, w, 0, 1, 2, 1, OUTLINE);
			fill(argb, w, 1, 2, 2, 1, OUTLINE);
			fill(argb, w, 11, 0, 1, 1, OUTLINE);
			fill(argb, w, 10, 1, 2, 1, OUTLINE);
			fill(argb, w, 9, 2, 2, 1, OUTLINE);
			fill(argb, w, 3, 2, 6, 1, OUTLINE);
			fill(argb, w, 2, 3, 8, 1, OUTLINE);
			fill(argb, w, 1, 3, 1, 5, OUTLINE);
			fill(argb, w, 10, 3, 1, 5, OUTLINE);
			fill(argb, w, 2, 4, 8, 4, OUTLINE);
			fill(argb, w, 2, 8, 8, 1, OUTLINE);
			fill(argb, w, 3, 9, 6, 1, OUTLINE);

			fill(argb, w, 3, 3, 6, 1, FILL);
			fill(argb, w, 2, 4, 8, 4, FILL);
			fill(argb, w, 3, 8, 6, 1, FILL);

			fill(argb, w, 3, 5, 2, 2, EYES);
			fill(argb, w, 7, 5, 2, 2, EYES);
			fill(argb, w, 5, 7, 2, 1, DETAIL);
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
		}

		// Build the palette keyed on the full ARGB value so opaque black (0xFF000000)
		// does NOT collide with the reserved transparent slot at palette index 0. The
		// previous (RGB-only) keying mapped any pure-black pixel to the transparent
		// index, which made the whole tier-6/7 black skull render as an empty sprite
		// (and partial breakage on lower tiers' black outlines/details).
		final java.util.LinkedHashMap<Integer, Byte> paletteMap = new java.util.LinkedHashMap<>();
		paletteMap.put(0, (byte) 0); // ARGB=0 -> palette index 0 = transparent
		for (int c : argb)
		{
			if (!paletteMap.containsKey(c))
			{
				paletteMap.put(c, (byte) paletteMap.size());
			}
		}
		final int[] palette = new int[paletteMap.size()];
		int idx = 0;
		for (int key : paletteMap.keySet())
		{
			palette[idx++] = key & 0x00FFFFFF; // palette stores RGB only; alpha lives in the index
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
			final Byte pi = paletteMap.get(argb[i]);
			pixels[i] = pi != null ? pi : 0;
		}
		sprite.setPixels(pixels);
		return sprite;
	}

	static int pickSkullFillColor(int tier)
	{
		switch (tier)
		{
			case 0: return 0xFFFFFF; // white
			case 1: return 0xFFEE66; // yellow
			case 2: return 0xFF9933; // orange
			case 6:
			case 7: return 0x000000; // black
			default: return 0xCC0000; // red (3-5)
		}
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

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

	// Tick state
	private int gameTick = 0;
	private int geClickedNonSubmitTick = -1;
	private int geCustomEntryTick = -100;
	private int tradeClickedNonAcceptTick = -1;
	private boolean tradePassedFirstScreen = false;
	private boolean pendingBoop = false;
	private int tradeItemChangedTick = -1;

	// Upgrade-mode bank-saving tracking
	private boolean savingBankOpen = false;
	private long totalCoinsSnapshot = 0;

	// Chat skull
	private int skullModIconIndex = -1;
	private int lastActiveCount = -1;

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

		upgradePanel = new UpgradePanel(upgradeManager);
		upgradeManager.setOnChange(() ->
		{
			upgradePanel.rebuild();
			final int newCount = upgradeManager.getActiveCount();
			if (newCount != lastActiveCount)
			{
				lastActiveCount = newCount;
				clientThread.invokeLater(this::reregisterChatSkull);
			}
		});

		navButton = NavigationButton.builder()
			.tooltip("Upgrade Mode")
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
		clientToolbar.removeNavigation(navButton);
		upgradeManager.setOnChange(null);
		clientThread.invokeLater(this::unregisterChatSkullIcon);
		savingBankOpen = false;
		totalCoinsSnapshot = 0;
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

	private void snapshotTotalCoins()
	{
		totalCoinsSnapshot = countCoinsIn(InventoryID.BANK) + countCoinsIn(InventoryID.INV);
	}

	private void checkIncinerationProgress()
	{
		final long current = countCoinsIn(InventoryID.BANK) + countCoinsIn(InventoryID.INV);
		final long burned = totalCoinsSnapshot - current;
		if (burned > 0)
		{
			final UpgradeManager.ApplyResult r = upgradeManager.applyBurned(burned);
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
			}
		}
		totalCoinsSnapshot = current;
	}

	// ----------------- Subscribers -----------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			upgradeManager.load();
			lastActiveCount = upgradeManager.getActiveCount();
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
			if (upgradeManager.isBankingUnlocked())
			{
				return; // banking unlocked: bank acts normally
			}
			// Saving mode: allow main bank widget (group 12) for incineration only.
			// Deposit boxes (192) have no incinerator, so always block when banking is locked.
			if (upgradeManager.getSelectedGoal() != null && g == 12)
			{
				if (!savingBankOpen)
				{
					savingBankOpen = true;
					snapshotTotalCoins();
					final Upgrade goal = upgradeManager.getSelectedGoal();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Saving for " + goal.getDisplayName()
							+ ". Drag coins onto the incinerator to make progress.", null);
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Closing the bank with coins still inside will reset your goal progress.", null);
				}
				return;
			}
			client.runScript(29);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Banking is disabled.", null);
			pendingBoop = true;
			return;
		}

		// GE offer screen
		if (g == 465 && !upgradeManager.isGeUnlocked())
		{
			client.runScript(29);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Grand Exchange is disabled.", null);
			pendingBoop = true;
			return;
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

		// Detect bank-close while saving. If coins remain inside, wipe goal progress.
		if (savingBankOpen && client.getWidget(12, 0) == null)
		{
			savingBankOpen = false;
			final long remaining = countCoinsIn(InventoryID.BANK);
			if (remaining > 0 && upgradeManager.getSelectedGoal() != null)
			{
				final Upgrade goal = upgradeManager.getSelectedGoal();
				final long lost = upgradeManager.getProgress(goal);
				upgradeManager.wipeGoalProgress();
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Goal progress LOST! You closed the bank with " + formatGp(remaining)
						+ " coins still inside (forfeited " + formatGp(lost) + " toward "
						+ goal.getDisplayName() + ").", null);
				pendingBoop = true;
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

		if (savingBankOpen && event.getContainerId() == InventoryID.BANK)
		{
			checkIncinerationProgress();
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
		final boolean hadAnything = upgradeManager.getActiveCount() > 0
			|| upgradeManager.getSelectedGoal() != null;
		upgradeManager.hardWipe();
		savingBankOpen = false;
		totalCoinsSnapshot = 0;
		if (hadAnything)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"You died! All Upgrade Mode progress and active upgrades have been wiped.", null);
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
			final boolean targetIsGeOrBanker = target.contains("banker")
				|| target.contains("bank chest")
				|| target.contains("bank booth")
				|| target.contains("grand exchange")
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

		// While saving (bank widget 12 open): only coin operations are allowed.
		if (!banking && goal != null && client.getWidget(12, 0) != null)
		{
			final boolean isCoins = target.contains("coins");
			final boolean isWithdraw = option.startsWith("withdraw");
			final boolean isDeposit = option.startsWith("deposit");
			final boolean isDestroy = option.equals("destroy");
			if ((isWithdraw || isDeposit || isDestroy) && !isCoins)
			{
				event.consume();
				if (isDestroy)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"Only coins may be incinerated while saving.", null);
				}
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
	 * Renders a 12x12 skull icon scaled by the number of active upgrades:
	 *   0      -> base white skull
	 *   1-2    -> colored (red) skull
	 *   3-4    -> colored skull with horns
	 *   5+     -> gilded (gold-trim, red-eye) horned skull
	 */
	private IndexedSprite createSkullIndexedSprite()
	{
		final int active = upgradeManager.getActiveCount();
		final boolean horned = active >= 3;
		final boolean gilded = active >= 5;
		final int fillColor = active == 0 ? 0xFFFFFF : 0xFF0000;

		final int w = 12;
		final int h = 12;
		final int[] argb = new int[w * h];
		Arrays.fill(argb, 0);

		final int GOLD = 0xFFFFD700;
		final int RED  = 0xFFFF0000;
		final int OUTLINE = gilded ? GOLD : 0xFF000000;
		final int FILL    = 0xFF000000 | fillColor;
		final int DETAIL  = gilded ? GOLD : 0xFF000000;
		final int EYES    = gilded ? RED : DETAIL;

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

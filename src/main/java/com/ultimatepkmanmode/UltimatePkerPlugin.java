package com.ultimatepkmanmode;

import com.google.inject.Provides;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Ultimate Pker",
	description = "Restrictions on banking, trading and the GE"
)
public class UltimatePkerPlugin extends Plugin
{
	private int gameTick = 0;
	private int geClickedNonSubmitTick = -1;
	private int tradeClickedNonAcceptTick = -1;
	private boolean tradePassedFirstScreen = false;

	public int getLimitPct()
	{
		return config.limitPercent();
	}

	private static boolean isBankInteraction(String option, String target)
	{
		if (option == null)
		{
			return false;
		}
		final String t = target == null ? "" : target;
		final boolean bankLikeTarget = t.contains("bank") || t.contains("banker") || t.contains("deposit") || t.contains("chest");
		if (!bankLikeTarget)
		{
			return false;
		}
		return option.contains("bank") || option.contains("deposit") || option.contains("withdraw") || option.contains("collect");
	}

	public static String formatGp(long gp)
	{
		final long abs = Math.abs(gp);
		final DecimalFormat df = new DecimalFormat("0.#");
		df.setRoundingMode(RoundingMode.HALF_UP);
		final String s;
		if (abs >= 1_000_000_000L)
		{
			s = df.format(abs / 1_000_000_000.0) + "B";
		}
		else if (abs >= 1_000_000L)
		{
			s = df.format(abs / 1_000_000.0) + "M";
		}
		else if (abs >= 1_000L)
		{
			s = df.format(abs / 1_000.0) + "K";
		}
		else
		{
			s = Long.toString(abs);
		}
		return (gp < 0 ? "-" : "") + s + " gp";
	}

	public long getAbsoluteCapGp()
	{
		return config.absoluteCapGp();
	}

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private UltimatePkerConfig config;

	@Inject
	private TradeBalanceOverlay tradeBalanceOverlay;

	@Inject
	private UltimatePkerChatPromptSkullOverlay chatPromptSkullOverlay;

	private IndexedSprite[] priorModIcons;
	private int skullModIconIndex = -1;

	@Provides
	UltimatePkerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(UltimatePkerConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(tradeBalanceOverlay);
		overlayManager.add(chatPromptSkullOverlay);
		registerChatSkullIcon();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(tradeBalanceOverlay);
		overlayManager.remove(chatPromptSkullOverlay);
		unregisterChatSkullIcon();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		gameTick++;

		// Reset first-screen flag when neither trade screen is open
		if (client.getWidget(335, 10) == null && client.getWidget(334, 13) == null)
		{
			tradePassedFirstScreen = false;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = Text.standardize(event.getMenuOption());
		final String target = Text.standardize(event.getMenuTarget());

		if (config.disableBanking() && isBankInteraction(option, target))
		{
			event.consume();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Banking is disabled.", null);
			return;
		}

		final int limitPct = config.limitPercent();
		if (limitPct <= 0)
		{
			return;
		}

		final boolean isGeOfferOpen = client.getWidget(465, 24) != null;
		if (isGeOfferOpen)
		{
			final boolean isSubmitLike = option.equals("confirm")
				|| option.equals("submit")
				|| option.equals("offer")
				|| option.equals("place")
				|| option.equals("yes")
				|| option.equals("continue")
				|| option.equals("ok");

			if (isSubmitLike)
			{
				// Block if another GE click happened this exact tick (same-tick exploit guard)
				if (geClickedNonSubmitTick == gameTick)
				{
					event.consume();
					return;
				}

				final String reason = validateGeOffer(limitPct);
				if (reason != null)
				{
					event.consume();
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", reason, null);
				}
				return;
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
					final String reason = validateTrade(limitPct);
					if (reason != null)
					{
						tradePassedFirstScreen = false;
						event.consume();
						client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", reason, null);
					}
					else
					{
						tradePassedFirstScreen = true;
					}
					return;
				}

				// Second trade screen: block if first screen validation failed
				if (tradeScreen2 != null && !tradePassedFirstScreen)
				{
					event.consume();
				}
				return;
			}

			// Any other click while the trade screen is open can change the offer.
			tradeClickedNonAcceptTick = gameTick;
		}
	}

	private String validateGeOffer(int limitPct)
	{
		final Widget offer = client.getWidget(465, 24);
		if (offer == null)
		{
			return null;
		}

		final List<Widget> widgets = flattenWidgetTree(offer);
		Integer itemId = null;
		for (Widget w : widgets)
		{
			final int id = w.getItemId();
			if (id > 0)
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
		Integer qty = null;
		for (int i = 0; i < widgets.size(); i++)
		{
			final Widget w = widgets.get(i);
			final String t = w.getText();
			if (t == null)
			{
				continue;
			}
			final String st = Text.standardize(t);

			if (price == null && st.contains("price per item"))
			{
				for (int j = i; j < Math.min(i + 6, widgets.size()); j++)
				{
					final Integer parsed = parseFirstInteger(widgets.get(j).getText());
					if (parsed != null)
					{
						price = parsed;
						break;
					}
				}
				continue;
			}

			if (qty == null && st.equals("quantity:"))
			{
				for (int j = i; j < Math.min(i + 6, widgets.size()); j++)
				{
					final Integer parsed = parseFirstInteger(widgets.get(j).getText());
					if (parsed != null)
					{
						qty = parsed;
						break;
					}
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

		final long capGp = config.absoluteCapGp();
		final long deltaPct = (long) Math.floor(market * (limitPct / 100.0));
		final long delta = Math.min(deltaPct, capGp);
		final long min = market - delta;
		final long max = market + delta;
		if (price < min || price > max)
		{
			return "GE offer blocked: price must be within +/-" + limitPct + "% (max +/-" + formatGp(capGp) + ") of market (" + formatGp(market) + ").";
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

	private String validateTrade(int limitPct)
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

		final long capGp = config.absoluteCapGp();
		final long pctDelta = (long) Math.floor(theirValue * (limitPct / 100.0));
		final long allowed = Math.min(pctDelta, capGp);
		final long min = theirValue - allowed;
		final long max = theirValue + allowed;

		if (ourValue < min || ourValue > max)
		{
			return "Trade blocked: your offer (" + formatGp(ourValue) + ") must be within +/-" + limitPct + "% (max +/-" + formatGp(capGp) + ") of their offer (" + formatGp(theirValue) + ").";
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

	private void registerChatSkullIcon()
	{
		final IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons == null)
		{
			return;
		}

		priorModIcons = modIcons;
		skullModIconIndex = modIcons.length;

		final IndexedSprite skull = createSkullIndexedSprite();
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

	private IndexedSprite createSkullIndexedSprite()
	{
		final int w = 12;
		final int h = 12;
		final int[] argb = new int[w * h];
		Arrays.fill(argb, 0);

		final int BLACK = 0xFF000000;
		final int WHITE = 0xFFFFFFFF;

		fill(argb, w, 3, 1, 6, 1, BLACK);
		fill(argb, w, 2, 2, 8, 1, BLACK);
		fill(argb, w, 1, 3, 10, 5, BLACK);
		fill(argb, w, 2, 8, 8, 1, BLACK);
		fill(argb, w, 3, 9, 6, 1, BLACK);

		fill(argb, w, 3, 2, 6, 1, WHITE);
		fill(argb, w, 2, 3, 8, 5, WHITE);
		fill(argb, w, 3, 8, 6, 1, WHITE);

		fill(argb, w, 3, 4, 2, 2, BLACK);
		fill(argb, w, 7, 4, 2, 2, BLACK);
		fill(argb, w, 5, 6, 2, 1, BLACK);

		fill(argb, w, 4, 9, 1, 1, BLACK);
		fill(argb, w, 6, 9, 1, 1, BLACK);

		final IndexedSprite sprite = client.createIndexedSprite();
		sprite.setWidth(w);
		sprite.setHeight(h);
		sprite.setOriginalWidth(w);
		sprite.setOriginalHeight(h);
		sprite.setOffsetX(0);
		sprite.setOffsetY(0);

		final int[] palette = new int[]{0, 0x000000, 0xFFFFFF};
		sprite.setPalette(palette);

		final byte[] pixels = new byte[w * h];
		for (int i = 0; i < argb.length; i++)
		{
			final int c = argb[i];
			if ((c >>> 24) == 0)
			{
				pixels[i] = 0;
			}
			else if ((c & 0x00FFFFFF) == 0x000000)
			{
				pixels[i] = 2;
			}
			else
			{
				pixels[i] = 1;
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

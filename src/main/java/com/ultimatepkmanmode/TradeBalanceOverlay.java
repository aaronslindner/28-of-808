package com.ultimatepkmanmode;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.QuantityFormatter;

public class TradeBalanceOverlay extends Overlay
{
	private final Client client;
	private final ItemManager itemManager;
	private final UltimatePkerPlugin plugin;

	@Inject
	public TradeBalanceOverlay(Client client, ItemManager itemManager, UltimatePkerPlugin plugin)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Widget tradeFrame = client.getWidget(335, 3);
		if (tradeFrame == null)
		{
			return null;
		}

		final Rectangle b = tradeFrame.getBounds();
		if (b == null)
		{
			return null;
		}

		final Widget ourOffer = client.getWidget(335, 25);
		final Widget theirOffer = client.getWidget(335, 28);
		if (ourOffer == null || theirOffer == null)
		{
			return null;
		}

		final long ourValue = computeOfferValue(ourOffer);
		final long theirValue = computeOfferValue(theirOffer);

		final int w = b.width;
		final int h = 36;
		final int x = b.x;
		final int y = b.y + b.height - h + 26;

		drawBar(graphics, x, y, w, h, ourValue, theirValue);
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
				continue;
			}
			total += (long) price * qty;
		}
		return total;
	}

	private void drawBar(Graphics2D g, int x, int y, int w, int h, long ourValue, long theirValue)
	{
		final Color bg = new Color(74, 67, 55, 255);
		final Color borderOuter = new Color(52, 45, 35, 255);
		final Color borderInner = new Color(130, 116, 90, 255);
		final Color text = new Color(255, 152, 31, 255);
		final Color textMuted = new Color(210, 190, 150, 220);
		final Color line = new Color(210, 190, 150, 200);
		final Color tick = new Color(210, 190, 150, 230);
		final Color tickCenter = new Color(210, 190, 150, 230);

		g.setColor(bg);
		g.fillRect(x, y, w, h);
		g.setColor(borderOuter);
		g.drawRect(x, y, w - 1, h - 1);
		g.setColor(borderInner);
		g.drawRect(x + 1, y + 1, w - 3, h - 3);

		// Subtle drop shadow to match OSRS interface shadow
		g.setColor(new Color(0, 0, 0, 90));
		g.drawLine(x + 2, y + h, x + w - 3, y + h);
		g.setColor(new Color(0, 0, 0, 45));
		g.drawLine(x + 2, y + h + 1, x + w - 3, y + h + 1);

		g.setColor(text);
		g.drawString("Trade balance", x + 8, y + 14);

		final String left = "You: " + QuantityFormatter.formatNumber(ourValue);
		final String right = "Them: " + QuantityFormatter.formatNumber(theirValue);

		final long pctDelta = (long) Math.floor(theirValue * (plugin.getLimitPct() / 100.0));
		final long allowed = Math.min(pctDelta, plugin.getAbsoluteCapGp());
		final long scaleBase = Math.max(ourValue, theirValue);
		final long pctDeltaFallback = (long) Math.floor(scaleBase * (plugin.getLimitPct() / 100.0));
		final long allowedTicks = allowed > 0 ? allowed : Math.min(pctDeltaFallback, plugin.getAbsoluteCapGp());
		final String favored;
		if (ourValue == theirValue)
		{
			favored = "Favored: Even";
		}
		else if (theirValue > ourValue)
		{
			favored = "Favored: You";
		}
		else
		{
			favored = "Favored: Them";
		}

		g.setColor(textMuted);
		g.drawString(left, x + 110, y + 14);
		g.drawString(right, x + 240, y + 14);
		final int favoredW = g.getFontMetrics().stringWidth(favored);
		g.drawString(favored, x + w - favoredW - 10, y + 14);

		// Balance number line
		final int pad = 10;
		final int lineY = y + h - 10;
		final int lineX1 = x + pad;
		final int lineX2 = x + w - pad;
		g.setColor(line);
		g.drawLine(lineX1, lineY, lineX2, lineY);

		final int centerX = (lineX1 + lineX2) / 2;
		g.setColor(tickCenter);
		g.drawLine(centerX, lineY - 6, centerX, lineY + 6);

		g.setColor(tick);
		final int halfSpanPx = (lineX2 - lineX1) / 2;
		final long displayRange = allowed > 0 ? Math.min(Long.MAX_VALUE / 4, allowed * 3) : 1;
		final double tickRatio = allowedTicks > 0 && displayRange > 0 ? (allowedTicks / (double) displayRange) : 0.0;
		final int tickOffset = (int) Math.round(halfSpanPx * tickRatio);
		g.drawLine(centerX - tickOffset, lineY - 3, centerX - tickOffset, lineY + 3);
		g.drawLine(centerX + tickOffset, lineY - 3, centerX + tickOffset, lineY + 3);

		// Marker for (their - our) scaled to displayRange so it can move outside the acceptable ticks.
		// Positive means you are favored (you receive more).
		final long diff = theirValue - ourValue;
		final long clamped = Math.max(-displayRange, Math.min(displayRange, diff));
		final double ratio = clamped / (double) displayRange;
		final int markerX = centerX + (int) Math.round(halfSpanPx * ratio);
		g.setColor(new Color(220, 90, 18, 245));
		g.fillOval(markerX - 4, lineY - 4, 8, 8);
		g.setColor(new Color(120, 10, 10, 230));
		g.drawOval(markerX - 4, lineY - 4, 8, 8);
	}
}

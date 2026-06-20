package com.ultimatepkmanmode;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws a small skull badge to the left of the chat-input field (widget 162.57)
 * whose appearance scales with the player's active upgrade count, matching the
 * chat-message skull icon registered in {@link UltimateNormiePlugin}.
 */
public class ChatPromptSkullOverlay extends Overlay
{
	private static final int ICON_SIZE = 12;

	private final Client client;
	private final UpgradeManager upgradeManager;

	private volatile BufferedImage skull;
	private int builtForUnlockedCount = -1;

	@Inject
	public ChatPromptSkullOverlay(Client client, UpgradeManager upgradeManager)
	{
		this.client = client;
		this.upgradeManager = upgradeManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	/** Call when the unlocked-upgrade count changes so the next render rebuilds the image. */
	public void invalidate()
	{
		builtForUnlockedCount = -1;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Widget input = client.getWidget(162, 57);
		if (input == null || input.isHidden())
		{
			return null;
		}

		final Rectangle b = input.getBounds();
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			return null;
		}

		final int unlocked = upgradeManager.getUnlockedCount();
		if (skull == null || builtForUnlockedCount != unlocked)
		{
			skull = buildSkull(unlocked);
			builtForUnlockedCount = unlocked;
		}

		final int x = b.x - ICON_SIZE + 2;
		final int y = b.y + (b.height - ICON_SIZE) / 2;

		final Object oldHint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.drawImage(skull, x, y, null);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			oldHint != null ? oldHint : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		return null;
	}

	/**
	 * Builds the 12x12 skull bitmap. Mirrors the IndexedSprite drawn for the chat-name
	 * prefix in {@link UltimateNormiePlugin#createSkullIndexedSprite}. One distinct
	 * visual per active upgrade (0..7).
	 */
	static BufferedImage buildSkull(int active)
	{
		final int tier = Math.max(0, Math.min(active, 7));
		final boolean horned = (tier == 7);
		final boolean gilded = false;
		final boolean blackTheme = tier >= 6;
		final boolean redEyes = false;
		final int fill = UltimateNormiePlugin.pickSkullFillColor(tier);

		final int GOLD = 0xFFFFD700;
		final int RED  = 0xFFFF0000;
		final int OUTLINE = gilded ? GOLD : 0xFF000000;
		final int FILL    = 0xFF000000 | fill;
		final int DETAIL  = gilded ? GOLD : (blackTheme ? 0xFFFFFFFF : 0xFF000000);
		final int EYES    = redEyes ? RED : DETAIL;

		final int w = ICON_SIZE;
		final int h = ICON_SIZE;
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

		if (horned)
		{
			fillRect(img, 0, 0, 1, 1, OUTLINE);
			fillRect(img, 0, 1, 2, 1, OUTLINE);
			fillRect(img, 1, 2, 2, 1, OUTLINE);
			fillRect(img, 11, 0, 1, 1, OUTLINE);
			fillRect(img, 10, 1, 2, 1, OUTLINE);
			fillRect(img, 9, 2, 2, 1, OUTLINE);
			fillRect(img, 3, 2, 6, 1, OUTLINE);
			fillRect(img, 2, 3, 8, 1, OUTLINE);
			fillRect(img, 1, 3, 1, 5, OUTLINE);
			fillRect(img, 10, 3, 1, 5, OUTLINE);
			fillRect(img, 2, 4, 8, 4, OUTLINE);
			fillRect(img, 2, 8, 8, 1, OUTLINE);
			fillRect(img, 3, 9, 6, 1, OUTLINE);

			fillRect(img, 3, 3, 6, 1, FILL);
			fillRect(img, 2, 4, 8, 4, FILL);
			fillRect(img, 3, 8, 6, 1, FILL);

			fillRect(img, 3, 5, 2, 2, EYES);
			fillRect(img, 7, 5, 2, 2, EYES);
			fillRect(img, 5, 7, 2, 1, DETAIL);
		}
		else
		{
			fillRect(img, 3, 1, 6, 1, OUTLINE);
			fillRect(img, 2, 2, 8, 1, OUTLINE);
			fillRect(img, 1, 3, 10, 5, OUTLINE);
			fillRect(img, 2, 8, 8, 1, OUTLINE);
			fillRect(img, 3, 9, 6, 1, OUTLINE);

			fillRect(img, 3, 2, 6, 1, FILL);
			fillRect(img, 2, 3, 8, 5, FILL);
			fillRect(img, 3, 8, 6, 1, FILL);

			fillRect(img, 3, 4, 2, 2, DETAIL);
			fillRect(img, 7, 4, 2, 2, DETAIL);
			fillRect(img, 5, 6, 2, 1, DETAIL);
		}
		return img;
	}

	private static void fillRect(BufferedImage img, int x, int y, int w, int h, int argb)
	{
		final int maxX = Math.min(x + w, img.getWidth());
		final int maxY = Math.min(y + h, img.getHeight());
		for (int yy = Math.max(0, y); yy < maxY; yy++)
		{
			for (int xx = Math.max(0, x); xx < maxX; xx++)
			{
				img.setRGB(xx, yy, argb);
			}
		}
	}
}

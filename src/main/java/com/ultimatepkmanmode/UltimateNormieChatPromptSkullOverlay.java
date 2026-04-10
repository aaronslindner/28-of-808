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
import net.runelite.client.util.ImageUtil;

public class UltimateNormieChatPromptSkullOverlay extends Overlay
{
	private static final int ICON_SIZE = 12;

	private final Client client;
	private final BufferedImage skull;

	@Inject
	public UltimateNormieChatPromptSkullOverlay(Client client)
	{
		this.client = client;
		this.skull = createSkullIcon();
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Widget input = client.getWidget(162, 57);
		if (input == null)
		{
			return null;
		}

		final Rectangle b = input.getBounds();
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			return null;
		}

		final int x = b.x - ICON_SIZE + 2;
		final int y = b.y + (b.height - ICON_SIZE) / 2;

		final Object oldHint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.drawImage(skull, x, y, null);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			oldHint != null ? oldHint : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		return null;
	}

	private BufferedImage createSkullIcon()
	{
		final BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g.setColor(new java.awt.Color(255, 255, 255, 255));
			g.fillRect(3, 1, 6, 1);
			g.fillRect(2, 2, 8, 1);
			g.fillRect(1, 3, 10, 5);
			g.fillRect(2, 8, 8, 1);
			g.fillRect(3, 9, 6, 1);
			g.setColor(new java.awt.Color(0, 0, 0, 255));
			g.fillRect(3, 2, 6, 1);
			g.fillRect(2, 3, 8, 5);
			g.fillRect(3, 8, 6, 1);
			g.setColor(new java.awt.Color(255, 255, 255, 255));
			g.fillRect(3, 4, 2, 2);
			g.fillRect(7, 4, 2, 2);
			g.fillRect(5, 6, 2, 1);
			g.fillRect(4, 9, 1, 1);
			g.fillRect(6, 9, 1, 1);
		}
		finally
		{
			g.dispose();
		}

		return ImageUtil.resizeImage(img, ICON_SIZE, ICON_SIZE);
	}
}

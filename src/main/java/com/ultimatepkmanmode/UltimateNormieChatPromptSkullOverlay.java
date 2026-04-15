package com.ultimatepkmanmode;

import java.awt.Color;
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

public class UltimateNormieChatPromptSkullOverlay extends Overlay
{
	private static final int ICON_SIZE = 12;

	private final Client client;
	private volatile BufferedImage skull;

	@Inject
	public UltimateNormieChatPromptSkullOverlay(Client client)
	{
		this.client = client;
		this.skull = LeaderboardPanel.createSkullImage(Color.WHITE, ICON_SIZE);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	public void updateSkullColor(Color fill, boolean horned, boolean gilded)
	{
		this.skull = LeaderboardPanel.createSkullImage(fill, ICON_SIZE, horned, gilded);
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

}

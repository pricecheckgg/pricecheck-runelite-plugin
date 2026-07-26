package gg.pricecheck.runelite;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the plugin's own Grand Exchange lines in a language the game cannot.
 *
 * The client's fonts carry no Japanese glyphs, so writing kana into a widget
 * paints nothing at all. Instead the widget keeps an EMPTY string - it still
 * owns its bounds, its right-click action and its hover - and this overlay
 * paints the translated line over the top in a face that can draw it.
 *
 * English never comes through here: the helper writes the text into the widget
 * itself, exactly as before.
 */
class GeLabelOverlay extends Overlay
{
	// Keyed on the widget, weakly: the GE rebuilds its children constantly, and
	// a dropped widget must not keep its label alive.
	private static final Map<Widget, String> LABELS =
		Collections.synchronizedMap(new WeakHashMap<>());

	private static final Font BASE = new Font(Font.SANS_SERIF, Font.BOLD, 11);

	static void note(Widget w, String text)
	{
		if (w != null && text != null && !text.isEmpty())
		{
			LABELS.put(w, text);
		}
	}

	static void clear()
	{
		LABELS.clear();
	}

	GeLabelOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!I18n.japanese() || LABELS.isEmpty())
		{
			return null;
		}
		final Object aa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		try
		{
			synchronized (LABELS)
			{
				final java.util.Iterator<Map.Entry<Widget, String>> it = LABELS.entrySet().iterator();
				while (it.hasNext())
				{
					final Map.Entry<Widget, String> e = it.next();
					final Widget w = e.getKey();
					// A widget the GE has torn down keeps no bounds; drop it
					// rather than wait for the collector, so nothing can paint
					// over whatever the client puts there next.
					if (w == null || w.getBounds() == null)
					{
						it.remove();
						continue;
					}
					paint(g, w, e.getValue());
				}
			}
		}
		finally
		{
			if (aa != null)
			{
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
			}
		}
		return null;
	}

	private void paint(Graphics2D g, Widget w, String text)
	{
		if (w == null || w.isHidden())
		{
			return;
		}
		final Rectangle b = w.getBounds();
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			return;
		}
		final Font f = I18n.font(BASE);
		g.setFont(f);
		final FontMetrics fm = g.getFontMetrics();
		// Sit on the line the widget's own text would have used.
		final int y = b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2;
		// The widget's live colour, so the hover highlight still reads.
		g.setColor(new Color(w.getTextColor() & 0xFFFFFF));
		g.drawString(text, b.x, y);
	}
}

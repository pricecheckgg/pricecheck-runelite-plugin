package gg.pricecheck.runelite;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The in-client sidebar view. Shows an auth/status line and the live ranked
 * flips. Server-fed only; empty unless the key + subscription are both valid.
 */
class PriceCheckPanel extends PluginPanel
{
	private static final Color CONFIRMED = new Color(0x5d, 0xf2, 0x9a);
	private static final Color SUBTLE = new Color(0x9a, 0x91, 0x7c);
	private static final Color GOLD = new Color(0xe6, 0xc6, 0x67);

	private final JLabel status = new JLabel();
	private final JPanel list = new JPanel();

	PriceCheckPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		status.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		status.setForeground(SUBTLE);
		status.setText("Enter your plugin key in the config.");

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));

		add(status, BorderLayout.NORTH);
		add(list, BorderLayout.CENTER);
	}

	void update(PriceCheckApiClient.FlipsResult result, int minEvPerHrK)
	{
		SwingUtilities.invokeLater(() -> render(result, minEvPerHrK));
	}

	private void render(PriceCheckApiClient.FlipsResult result, int minEvPerHrK)
	{
		list.removeAll();
		switch (result.state)
		{
			case NO_KEY:
				status.setText("Enter your plugin key in the config.");
				break;
			case INVALID_KEY:
				status.setText("Key not recognised. Regenerate it at premium.pricecheck.gg.");
				break;
			case NO_SUBSCRIPTION:
				status.setText("Your subscription isn't active.");
				break;
			case ERROR:
				status.setText("Couldn't reach PriceCheck. Retrying...");
				break;
			case OK:
				int shown = 0;
				for (FlipData f : result.flips)
				{
					if (minEvPerHrK > 0 && f.getEvPerHr() < minEvPerHrK * 1000L)
					{
						continue;
					}
					list.add(rowFor(f));
					shown++;
				}
				status.setText(shown + " live flips - ranked by EV/hr");
				break;
			default:
				break;
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel rowFor(FlipData f)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel name = new JLabel(f.getName());
		name.setFont(name.getFont().deriveFont(Font.BOLD));
		name.setForeground(f.isConfirmed() ? CONFIRMED : Color.WHITE);

		final JLabel line = new JLabel(fmt(f.getBuy()) + " to " + fmt(f.getSell())
			+ "   +" + fmt(f.getProfit()));
		line.setForeground(SUBTLE);

		final JLabel ev = new JLabel(fmt(f.getEvPerHr()) + "/hr");
		ev.setForeground(GOLD);

		final JPanel bottom = new JPanel(new BorderLayout());
		bottom.setOpaque(false);
		bottom.add(line, BorderLayout.WEST);
		bottom.add(ev, BorderLayout.EAST);

		row.add(name, BorderLayout.NORTH);
		row.add(bottom, BorderLayout.SOUTH);
		return row;
	}

	private static String fmt(long n)
	{
		final long a = Math.abs(n);
		if (a >= 1_000_000_000L)
		{
			return String.format("%.2fb", n / 1e9);
		}
		if (a >= 1_000_000L)
		{
			return String.format("%.2fm", n / 1e6);
		}
		if (a >= 1_000L)
		{
			return String.format("%.1fk", n / 1e3);
		}
		return String.valueOf(n);
	}
}

package gg.pricecheck.runelite;

import java.awt.Color;

/** One brand palette shared by the panel and every overlay. */
final class Palette
{
	private Palette() {}

	static final Color GOLD = new Color(0xe6, 0xc6, 0x67);
	static final Color GREEN = new Color(0x5d, 0xf2, 0x9a);
	static final Color RED = new Color(0xf2, 0x6b, 0x6d);
	static final Color AMBER = new Color(0xe0, 0xa5, 0x4c);
	static final Color SUBTLE = new Color(0x9a, 0x91, 0x7c);       // on the dark panel
	static final Color LIGHT = new Color(0xd3, 0xcc, 0xba);        // readable small text on the dark panel
	static final Color SUBTLE_CANVAS = new Color(0xb8, 0xb0, 0x98); // on the game canvas
	static final Color INK = new Color(8, 10, 14, 235);            // overlay surface
	static final Color HALO = new Color(0, 0, 0, 140);             // dark rim under coloured strokes
}

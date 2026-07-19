package gg.pricecheck.runelite;

import java.util.IdentityHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.gameval.NpcID;

/**
 * Opt-in fun: dresses the Grand Exchange clerks in PriceCheck gold. Purely
 * cosmetic and purely local, nothing leaves the client. Model face colours
 * are packed HSL; outfit faces move to the brand hue while keeping each
 * face's own luminance so the garment shading survives, and the skin hue
 * band is left alone. Originals are snapshotted per model instance so
 * flipping the toggle off dresses the clerks back.
 */
final class GeClerkCosmetic
{
	private static final int GOLD_HUE = 9;
	private static final int GOLD_SAT = 5;
	// NPC skin lives in this hue band; faces there stay untouched.
	private static final int SKIN_HUE_LO = 4;
	private static final int SKIN_HUE_HI = 8;
	// Animated models can be rebuilt per frame; the snapshot map is a small
	// ring that clears rather than growing without bound.
	private static final int MAX_TRACKED = 64;

	private final Map<Model, int[][]> originals = new IdentityHashMap<>();

	private static boolean isClerk(int id)
	{
		return id == NpcID.GE_CLERK_1 || id == NpcID.GE_CLERK_2
			|| id == NpcID.GE_CLERK_3 || id == NpcID.GE_CLERK_4;
	}

	void apply(Client client)
	{
		if (originals.size() > MAX_TRACKED)
		{
			originals.clear();
		}
		for (final NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !isClerk(npc.getId()))
			{
				continue;
			}
			final Model m = npc.getModel();
			if (m == null || originals.containsKey(m))
			{
				continue;
			}
			final int[] f1 = m.getFaceColors1();
			final int[] f2 = m.getFaceColors2();
			final int[] f3 = m.getFaceColors3();
			originals.put(m, new int[][]{f1.clone(), f2.clone(), f3.clone()});
			for (int i = 0; i < f1.length; i++)
			{
				if (f3[i] == -2)
				{
					continue;   // hidden face
				}
				f1[i] = golden(f1[i]);
				if (f3[i] != -1)
				{
					f2[i] = golden(f2[i]);
					f3[i] = golden(f3[i]);
				}
			}
		}
	}

	/** Brand the colour unless it reads as skin: keep the luminance, take the hue. */
	private static int golden(int hsl)
	{
		if (hsl < 0)
		{
			return hsl;
		}
		final int hue = JagexColor.unpackHue((short) hsl);
		final int sat = JagexColor.unpackSaturation((short) hsl);
		final int lum = JagexColor.unpackLuminance((short) hsl);
		if (sat >= 1 && hue >= SKIN_HUE_LO && hue <= SKIN_HUE_HI)
		{
			return hsl;
		}
		return JagexColor.packHSL(GOLD_HUE, Math.max(GOLD_SAT, sat >= 6 ? 6 : GOLD_SAT), lum);
	}

	void restore()
	{
		if (originals.isEmpty())
		{
			return;
		}
		for (final Map.Entry<Model, int[][]> e : originals.entrySet())
		{
			final Model m = e.getKey();
			final int[][] o = e.getValue();
			copyBack(o[0], m.getFaceColors1());
			copyBack(o[1], m.getFaceColors2());
			copyBack(o[2], m.getFaceColors3());
		}
		originals.clear();
	}

	private static void copyBack(int[] from, int[] to)
	{
		if (from != null && to != null && from.length == to.length)
		{
			System.arraycopy(from, 0, to, 0, from.length);
		}
	}
}

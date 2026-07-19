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

	/** Ids are the fast path; the name match catches every clerk variant the
	 *  id list misses (the booth staff wear several different NPC ids). */
	static boolean isClerk(NPC npc)
	{
		final int id = npc.getId();
		if (id == NpcID.GE_CLERK_1 || id == NpcID.GE_CLERK_2
			|| id == NpcID.GE_CLERK_3 || id == NpcID.GE_CLERK_4)
		{
			return true;
		}
		final String name = npc.getName();
		return name != null && name.startsWith("Grand Exchange") && name.contains("lerk");
	}

	void apply(Client client)
	{
		if (originals.size() > MAX_TRACKED)
		{
			originals.clear();
		}
		for (final NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !isClerk(npc))
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
			carveBadge(m, f1, f2, f3);
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
		// Rich gold: full saturation with the luminance lifted onto a band
		// that keeps the shading but never sinks into olive.
		final int lifted = 34 + lum * 58 / 127;
		return JagexColor.packHSL(GOLD_HUE, 7, lifted);
	}

	// The dark ink the badge is carved in, against the gold robe.
	private static final int BADGE_INK_HSL = JagexColor.packHSL(9, 3, 14);

	/**
	 * The badge: a dark emblem cut into the chest-region faces of the robe.
	 * Real model geometry, not an overlay. Faces are picked by centroid: the
	 * vertical chest band a little below the shoulders, centred laterally.
	 * Low-poly models make it a crest-shaped patch; text is not a thing
	 * triangles can say at this face density.
	 */
	private static void carveBadge(Model m, int[] f1, int[] f2, int[] f3)
	{
		final float[] vx = m.getVerticesX();
		final float[] vy = m.getVerticesY();
		final int[] i1 = m.getFaceIndices1();
		final int[] i2 = m.getFaceIndices2();
		final int[] i3 = m.getFaceIndices3();
		if (vx == null || i1 == null)
		{
			return;
		}
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float maxAbsX = 1;
		for (int v = 0; v < vx.length; v++)
		{
			minY = Math.min(minY, vy[v]);
			maxY = Math.max(maxY, vy[v]);
			maxAbsX = Math.max(maxAbsX, Math.abs(vx[v]));
		}
		final float h = Math.max(1, maxY - minY);
		// Model y grows downward, so the chest band hangs just below the top.
		final float chestLo = minY + h * 0.26f;
		final float chestHi = minY + h * 0.40f;
		final float halfW = maxAbsX * 0.30f;
		for (int i = 0; i < i1.length && i < f1.length; i++)
		{
			if (f3[i] == -2)
			{
				continue;
			}
			final float cy = (vy[i1[i]] + vy[i2[i]] + vy[i3[i]]) / 3f;
			final float cx = (vx[i1[i]] + vx[i2[i]] + vx[i3[i]]) / 3f;
			if (cy < chestLo || cy > chestHi || Math.abs(cx) > halfW)
			{
				continue;
			}
			f1[i] = BADGE_INK_HSL;
			if (f3[i] != -1)
			{
				f2[i] = BADGE_INK_HSL;
				f3[i] = BADGE_INK_HSL;
			}
		}
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

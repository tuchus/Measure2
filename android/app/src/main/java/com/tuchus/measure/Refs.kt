package com.tuchus.measure

/**
 * Things people often have to hand, with their real sizes in millimetres.
 * With [corners], you mark all four corners and [mm] by [mm2] is the rectangle; this corrects for photos taken at an angle.
 * A size of 0 means "type your own".
 */
data class Ref(val label: String, val mm: Float, val mm2: Float = 0f, val corners: Boolean = false)

object Refs {
    val all = listOf(
        Ref("Bank card, all 4 corners (best for angled photos)", 85.6f, 53.98f, true),
        Ref("A4 paper, all 4 corners", 297f, 210f, true),
        Ref("US Letter, all 4 corners", 279.4f, 215.9f, true),
        Ref("Something rectangular, all 4 corners (type its sides)", 0f, 0f, true),
        Ref("Bank card, long side (85.6 mm)", 85.6f),
        Ref("Bank card, short side (54 mm)", 53.98f),
        Ref("A4 paper, long side (297 mm)", 297f),
        Ref("A4 paper, short side (210 mm)", 210f),
        Ref("US Letter, long side (11 in)", 279.4f),
        Ref("US Letter, short side (8.5 in)", 215.9f),
        Ref("US quarter, across (24.26 mm)", 24.26f),
        Ref("£1 coin, across (23.43 mm)", 23.43f),
        Ref("€1 coin, across (23.25 mm)", 23.25f),
        Ref("Galaxy S21 Ultra, height (165.1 mm)", 165.1f),
        Ref("Something else, one length (type it)", 0f),
    )
}

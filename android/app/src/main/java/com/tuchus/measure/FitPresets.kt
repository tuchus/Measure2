package com.tuchus.measure

/** Common things people want to fit somewhere. Sizes in centimetres: width, depth, height. */
data class FitPreset(val label: String, val w: Float, val d: Float, val h: Float)

object FitPresets {
    val all = listOf(
        FitPreset("Type your own size", 0f, 0f, 0f),
        FitPreset("Three-seat sofa", 210f, 90f, 85f),
        FitPreset("Two-seat sofa", 160f, 90f, 85f),
        FitPreset("Armchair", 85f, 85f, 90f),
        FitPreset("Double bed", 140f, 200f, 50f),
        FitPreset("King bed", 160f, 200f, 50f),
        FitPreset("Single bed", 90f, 190f, 50f),
        FitPreset("Fridge freezer", 60f, 65f, 180f),
        FitPreset("American fridge", 91f, 72f, 178f),
        FitPreset("Washing machine", 60f, 60f, 85f),
        FitPreset("Dishwasher", 60f, 60f, 85f),
        FitPreset("Wardrobe", 100f, 60f, 200f),
        FitPreset("Chest of drawers", 80f, 45f, 90f),
        FitPreset("Dining table for 6", 180f, 90f, 75f),
        FitPreset("Desk", 120f, 60f, 75f),
        FitPreset("Bookcase", 80f, 30f, 180f),
        FitPreset("65 inch TV", 145f, 7f, 84f),
        FitPreset("55 inch TV", 123f, 7f, 71f),
        FitPreset("Upright piano", 150f, 60f, 125f),
        FitPreset("Moving box, large", 60f, 45f, 45f),
        FitPreset("Suitcase, large", 50f, 30f, 75f),
    )
}

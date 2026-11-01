package com.udi.flickkick

import kotlin.math.hypot

/**
 * מזהה "פליק" – האצבע המורה נטענת על האגודל ומשתחררת בתנופה.
 *
 * הקלט: 21 נקודות ציון של היד (x,y מנורמלים 0..1, תמונה במראה).
 * כל המרחקים מנורמלים לפי גודל כף היד (שורש כף היד ← בסיס האצבע האמצעית),
 * כך שהזיהוי לא תלוי במרחק היד מהמצלמה.
 *
 * מכונת מצבים:
 *   NO_HAND → OPEN → LOADED → (שחרור מהיר) → בעיטה → OPEN
 */
class FlickDetector(private val onFlick: (power: Float) -> Unit) {

    enum class State { NO_HAND, OPEN, LOADED }

    companion object {
        // מרחק קצה-אגודל ↔ קצה-מורה (ביחס לגודל כף היד)
        const val LOAD_RATIO = 0.40f      // מתחת לזה = האצבע "טעונה" על האגודל
        const val RELEASE_RATIO = 0.75f   // מעל זה = האצבע שוחררה
        const val LOAD_FRAMES = 3         // כמה פריימים רצופים צריך כדי להיחשב טעון
        const val MAX_RELEASE_MS = 260L   // שחרור איטי מזה אינו פליק

        // מהירות קצה האצבע (גדלי-יד לשנייה) → עוצמה
        const val MIN_SPEED = 3.0f
        const val MAX_SPEED = 18.0f

        const val COOLDOWN_MS = 600L

        private const val WRIST = 0
        private const val THUMB_TIP = 4
        private const val INDEX_TIP = 8
        private const val MIDDLE_MCP = 9
    }

    @Volatile var state = State.NO_HAND
        private set
    @Volatile var lastRatio = 1f
        private set
    @Volatile var lastSpeed = 0f
        private set
    /** זמן (uptime) שבו נדחה פליק איטי מדי – להצגת "שחרר מהר יותר" */
    @Volatile var feedbackAt = 0L
        private set

    private var loadFrames = 0
    private var loadTipX = 0f
    private var loadTipY = 0f
    private var loadTime = 0L
    private var cooldownUntil = 0L

    fun onFrame(lm: FloatArray?, timeMs: Long) {
        if (lm == null) {
            state = State.NO_HAND
            loadFrames = 0
            return
        }

        val scale = dist(lm, WRIST, MIDDLE_MCP).coerceAtLeast(1e-3f)
        val ratio = dist(lm, THUMB_TIP, INDEX_TIP) / scale
        lastRatio = ratio
        val tipX = lm[INDEX_TIP * 2]
        val tipY = lm[INDEX_TIP * 2 + 1]

        when (state) {
            State.NO_HAND, State.OPEN -> {
                state = State.OPEN
                if (ratio < LOAD_RATIO && timeMs >= cooldownUntil) {
                    loadFrames++
                    if (loadFrames >= LOAD_FRAMES) {
                        state = State.LOADED
                        mark(tipX, tipY, timeMs)
                    }
                } else {
                    loadFrames = 0
                }
            }

            State.LOADED -> {
                when {
                    // עדיין לחוץ – מעדכנים את נקודת הזינוק
                    ratio < LOAD_RATIO * 1.25f -> mark(tipX, tipY, timeMs)

                    // שוחרר – בודקים אם זה היה פליק מהיר
                    ratio > RELEASE_RATIO -> {
                        val dtMs = (timeMs - loadTime).coerceAtLeast(1L)
                        val move = hypot(tipX - loadTipX, tipY - loadTipY) / scale
                        val speed = move / (dtMs / 1000f)
                        lastSpeed = speed
                        if (dtMs <= MAX_RELEASE_MS && speed >= MIN_SPEED) {
                            val power = ((speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED)).coerceIn(0f, 1f)
                            cooldownUntil = timeMs + COOLDOWN_MS
                            onFlick(power)
                        } else {
                            feedbackAt = timeMs
                        }
                        state = State.OPEN
                        loadFrames = 0
                    }

                    // באמצע תנועה – אם נתקע שם יותר מדי זמן, מבטלים
                    timeMs - loadTime > MAX_RELEASE_MS * 2 -> {
                        state = State.OPEN
                        loadFrames = 0
                    }
                }
            }
        }
    }

    private fun mark(x: Float, y: Float, t: Long) {
        loadTipX = x
        loadTipY = y
        loadTime = t
    }

    private fun dist(lm: FloatArray, a: Int, b: Int): Float =
        hypot(lm[a * 2] - lm[b * 2], lm[a * 2 + 1] - lm[b * 2 + 1])
}

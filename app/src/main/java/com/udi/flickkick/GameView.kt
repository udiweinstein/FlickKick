package com.udi.flickkick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*
import kotlin.random.Random

/**
 * מגרש תלת-ממדי מדומה (הטלה פרספקטיבית) עם שער, שוער וכדור.
 * יחידות העולם במטרים: x = רוחב, y = גובה, z = עומק (0 = נקודת הפנדל).
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    data class Kick(val power: Float, val aimX: Float? = null, val aimY: Float? = null)

    private enum class Phase { READY, FLYING, RESULT, GAME_OVER }
    private enum class Outcome { GOAL, SAVED, POST, BAR, OVER, WIDE, WEAK }

    // ---------- קבועי העולם ----------
    private val goalZ = 11f
    private val goalHW = 3.66f          // חצי רוחב שער
    private val goalH = 2.44f
    private val netDepth = 1.8f
    private val postR = 0.06f
    private val ballR = 0.11f
    private val keeperZ = goalZ - 0.5f
    private val camZ = -5f
    private val camH = 1.5f
    private val gravity = 9.81f
    private val shotsPerRound = 10

    // ---------- קלט מבחוץ (חוטים אחרים) ----------
    @Volatile var handPts: FloatArray? = null
    @Volatile var errorText: String? = null
    @Volatile var cameraDenied = false
    var detector: FlickDetector? = null
    private val kicks = ConcurrentLinkedQueue<Kick>()
    fun queueKick(k: Kick) { kicks.add(k) }

    // ---------- לולאת משחק ----------
    @Volatile private var running = false
    private var thread: Thread? = null

    // ---------- הטלה ----------
    private var W = 1f
    private var H = 1f
    private var focal = 1f
    private var horizonY = 1f
    private var sx = 0f
    private var sy = 0f
    private var sc = 0f

    private fun proj(x: Float, y: Float, z: Float) {
        val d = max(z - camZ, 0.25f)
        sc = focal / d
        sx = W / 2f + x * sc
        sy = horizonY + (camH - y) * sc
    }

    // ---------- מצב משחק ----------
    private var phase = Phase.READY
    private var outcome: Outcome? = null
    private var time = 0f
    private var resultT = 0f
    private var flightT = 0f
    private var overT = 0f

    private var shots = 0
    private var goals = 0
    private var streak = 0
    private val prefs = context.getSharedPreferences("flickkick", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var lastPower = -1f

    // כוונת (נשלטת ע"י מיקום היד)
    private var aimX = 0f
    private var aimY = 0.5f
    private var targetAimX = 0f
    private var targetAimY = 0.5f

    // כדור
    private var bx = 0f
    private var by = ballR
    private var bz = 0f
    private var bvx = 0f
    private var bvy = 0f
    private var bvz = 0f
    private var bSpin = 0f
    private val trail = ArrayDeque<FloatArray>()

    // שוער
    private var kx = 0f
    private var kTarget = 0f
    private var kReact = 0f
    private var kDecided = false
    private var kDiving = false
    private var kDiveDir = 1f
    private var kDiveY = 1f
    private var kDiveProg = 0f

    private val level get() = goals

    // ---------- צבעים ו-Paint ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val textShadow = Paint(text).apply { color = 0xAA000000.toInt() }
    private val path = Path()
    private val rect = RectF()
    private var crowd = FloatArray(0)
    private var crowdColors = IntArray(0)
    private var skyShader: Shader? = null
    private var standShader: Shader? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // =====================================================================
    // Surface lifecycle
    // =====================================================================
    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this, "GameLoop").also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        W = width.toFloat()
        H = height.toFloat()
        focal = W * 1.45f
        horizonY = H * 0.40f
        proj(0f, 0f, 60f)
        val farY = sy
        skyShader = LinearGradient(0f, 0f, 0f, H * 0.14f, 0xFF0B1A3A.toInt(), 0xFF27406E.toInt(), Shader.TileMode.CLAMP)
        standShader = LinearGradient(0f, H * 0.12f, 0f, farY, 0xFF1B1F2B.toInt(), 0xFF2D3242.toInt(), Shader.TileMode.CLAMP)
        // קהל: נקודות צבעוניות ביציע
        val n = 900
        val rnd = Random(7)
        crowd = FloatArray(n * 2)
        crowdColors = IntArray(n)
        val palette = intArrayOf(0xFFE53935.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E88E5.toInt(),
            0xFFFFD54F.toInt(), 0xFF8D6E63.toInt(), 0xFF43A047.toInt())
        for (i in 0 until n) {
            crowd[i * 2] = rnd.nextFloat() * W
            crowd[i * 2 + 1] = H * 0.13f + rnd.nextFloat() * (farY - H * 0.13f - W * 0.06f)
            crowdColors[i] = palette[rnd.nextInt(palette.size)]
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.033f)
            last = now
            update(dt)
            val c = try { holder.lockHardwareCanvas() } catch (_: Exception) { null }
            if (c == null) { Thread.sleep(16); continue }
            try { draw(c) } finally {
                try { holder.unlockCanvasAndPost(c) } catch (_: Exception) {}
            }
        }
    }

    // =====================================================================
    // לוגיקה
    // =====================================================================
    private fun update(dt: Float) {
        time += dt

        // כוונת לפי מיקום כף היד בתמונה (מרכז כף היד = ממוצע שורש-כף-היד ובסיס האצבע האמצעית)
        handPts?.let { p ->
            val px = (p[0] + p[18]) / 2f
            val py = (p[1] + p[19]) / 2f
            targetAimX = ((px - 0.5f) / 0.3f).coerceIn(-1.25f, 1.25f)
            targetAimY = (1f - (py - 0.25f) / 0.5f).coerceIn(0f, 1.15f)
        }
        val k = 1f - exp(-dt * 10f)
        aimX += (targetAimX - aimX) * k
        aimY += (targetAimY - aimY) * k

        while (true) {
            val kick = kicks.poll() ?: break
            when (phase) {
                Phase.READY -> shoot(kick)
                Phase.GAME_OVER -> if (overT > 1f) newRound()
                else -> {}
            }
        }

        when (phase) {
            Phase.READY -> {
                kx += (0.25f * sin(time * 1.3f) - kx) * (1f - exp(-dt * 3f))
            }
            Phase.FLYING, Phase.RESULT -> {
                flightT += dt
                stepBall(dt)
                stepKeeper(dt)
                if (phase == Phase.FLYING && flightT > 3.5f) setOutcome(Outcome.WEAK)
                if (phase == Phase.RESULT) {
                    resultT += dt
                    if (resultT > 1.9f) nextShot()
                }
            }
            Phase.GAME_OVER -> overT += dt
        }
    }

    private fun gauss(): Float {
        // Box–Muller
        val u = Random.nextFloat().coerceAtLeast(1e-6f)
        val v = Random.nextFloat()
        return sqrt(-2f * ln(u)) * cos(2f * PI.toFloat() * v)
    }

    private fun aimToWorldX(a: Float) = a * 3.4f
    private fun aimToWorldY(a: Float) = 0.15f + a * 2.15f

    private fun shoot(kick: Kick) {
        val p = kick.power.coerceIn(0f, 1f)
        val ax = kick.aimX ?: aimX
        val ay = kick.aimY ?: aimY
        lastPower = p

        // בעיטה חזקה = מהירה יותר, אבל פחות מדויקת
        val spread = 0.12f + 0.75f * p * p
        val tx = aimToWorldX(ax) + gauss() * spread
        val ty = (aimToWorldY(ay) + gauss() * spread * 0.6f).coerceAtLeast(0.05f)
        val vz = 13f + 17f * p
        val t = (goalZ - bz) / vz
        bvz = vz
        bvx = (tx - bx) / t
        bvy = (ty - by + 0.5f * gravity * t * t) / t

        shots++
        flightT = 0f
        outcome = null
        phase = Phase.FLYING
        trail.clear()

        kDecided = false
        kDiving = false
        kDiveProg = 0f
        kReact = (0.30f - level * 0.02f).coerceAtLeast(0.12f) * (1.1f - 0.4f * p)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun stepKeeper(dt: Float) {
        if (!kDecided) {
            kReact -= dt
            if (kReact <= 0f && bvz > 0f) {
                kDecided = true
                val tLeft = ((keeperZ - bz) / bvz).coerceAtLeast(0f)
                var px = bx + bvx * tLeft
                val py = by + bvy * tLeft - 0.5f * gravity * tLeft * tLeft
                val err = (1.1f - level * 0.08f).coerceAtLeast(0.25f)
                px += gauss() * err * 0.6f
                kTarget = px.coerceIn(-3.3f, 3.3f)
                if (abs(kTarget - kx) > 0.55f || py < 0.45f) {
                    kDiving = true
                    kDiveDir = if (kTarget >= kx) 1f else -1f
                    kDiveY = py.coerceIn(0.35f, 2.0f)
                }
            }
        }
        if (kDecided) {
            val speed = (2.6f + level * 0.25f).coerceAtMost(5.5f) * if (kDiving) 1.6f else 1f
            val diff = kTarget - kx
            kx += sign(diff) * min(speed * dt, abs(diff))
            if (kDiving) kDiveProg = min(1f, kDiveProg + dt / 0.35f)
        }
    }

    /** מרכז גוף השוער + זווית ההטיה (רדיאנים, חיובי = נוטה ימינה) */
    private fun keeperPose(): FloatArray {
        val e = kDiveProg * kDiveProg * (3 - 2 * kDiveProg)
        val angle = if (kDiving) kDiveDir * Math.toRadians(75.0).toFloat() * e else 0f
        val cy = 1.1f + (kDiveY - 1.1f) * e
        return floatArrayOf(kx, cy, angle)
    }

    private fun keeperHits(x: Float, y: Float): Boolean {
        val (cx, cy, a) = keeperPose()
        val dx = x - cx
        val dy = y - cy
        val ux = sin(a)
        val uy = cos(a)
        val along = dx * ux + dy * uy
        val across = dx * uy - dy * ux
        val halfW = if (kDiving) 0.34f else 0.45f
        return abs(along) < 1.1f + ballR && abs(across) < halfW + ballR
    }

    private fun stepBall(dt: Float) {
        val px = bx; val py = by; val pz = bz
        bvy -= gravity * dt
        bx += bvx * dt; by += bvy * dt; bz += bvz * dt
        bSpin += (abs(bvz) + abs(bvx)) * dt * 2.5f

        // קרקע
        if (by < ballR) {
            by = ballR
            if (bvy < 0f) bvy = -bvy * 0.45f
            if (bvy < 0.6f) bvy = 0f
        }
        if (by <= ballR + 0.001f) {
            val f = 1f - 1.2f * dt
            bvx *= f; bvz *= f
        }

        // שובל
        trail.addLast(floatArrayOf(bx, by, bz))
        while (trail.size > 10) trail.removeFirst()

        if (outcome == null) {
            // מישור השוער
            if (pz < keeperZ && bz >= keeperZ) {
                val f = (keeperZ - pz) / (bz - pz)
                val ix = px + (bx - px) * f
                val iy = py + (by - py) * f
                if (keeperHits(ix, iy)) {
                    bz = keeperZ - 0.02f
                    bvz = -abs(bvz) * 0.3f
                    bvy = abs(bvy) * 0.3f + 1.5f
                    bvx = kDiveDir * 2f + gauss()
                    setOutcome(Outcome.SAVED)
                    return
                }
            }
            // קו השער
            if (pz < goalZ && bz >= goalZ) {
                val f = (goalZ - pz) / (bz - pz)
                val ix = px + (bx - px) * f
                val iy = py + (by - py) * f
                val ax = abs(ix)
                val innerX = goalHW - postR - ballR * 0.6f
                val innerY = goalH - postR - ballR * 0.6f
                when {
                    ax < innerX && iy < innerY -> setOutcome(Outcome.GOAL)
                    ax < goalHW + postR + ballR * 0.8f && iy < goalH + postR + ballR * 0.8f -> {
                        bz = goalZ - 0.02f
                        bvz = -abs(bvz) * 0.45f
                        if (iy > goalH - postR - ballR) {
                            bvy = if (iy > goalH) abs(bvy) * 0.5f + 1f else -abs(bvy) * 0.5f
                            setOutcome(Outcome.BAR)
                        } else {
                            bvx = if (ax < goalHW) -sign(ix) * (abs(bvx) * 0.5f + 1f) else sign(ix) * (abs(bvx) * 0.5f + 1f)
                            setOutcome(Outcome.POST)
                        }
                    }
                    ax <= goalHW -> setOutcome(Outcome.OVER)
                    else -> setOutcome(Outcome.WIDE)
                }
            }
        }

        // בתוך הרשת
        if (outcome == Outcome.GOAL && bz > goalZ) {
            bx = bx.coerceIn(-goalHW + ballR, goalHW - ballR)
            if (by > goalH - ballR) { by = goalH - ballR; bvy = -abs(bvy) * 0.2f }
            val back = goalZ + netDepth - ballR
            if (bz > back) {
                bz = back
                bvz = -bvz * 0.08f
                bvx *= 0.3f
            }
        }
    }

    private fun setOutcome(o: Outcome) {
        outcome = o
        phase = Phase.RESULT
        resultT = 0f
        if (o == Outcome.GOAL) {
            goals++; streak++
            haptic(HapticFeedbackConstants.LONG_PRESS)
        } else streak = 0
    }

    private fun nextShot() {
        resetBall()
        if (shots >= shotsPerRound) {
            if (goals > best) {
                best = goals
                prefs.edit().putInt("best", best).apply()
            }
            phase = Phase.GAME_OVER
            overT = 0f
        } else phase = Phase.READY
    }

    private fun newRound() {
        shots = 0; goals = 0; streak = 0; lastPower = -1f
        resetBall()
        phase = Phase.READY
    }

    private fun resetBall() {
        bx = 0f; by = ballR; bz = 0f
        bvx = 0f; bvy = 0f; bvz = 0f
        trail.clear()
        kDiving = false; kDiveProg = 0f; kDecided = false
        outcome = null
    }

    private fun haptic(type: Int) {
        post { performHapticFeedback(type) }
    }

    // =====================================================================
    // מגע (גיבוי ולבדיקה באמולטור): החלקה כלפי מעלה = בעיטה
    // =====================================================================
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime }
            MotionEvent.ACTION_UP -> {
                if (phase == Phase.GAME_OVER) { queueKick(Kick(0.5f)); return true }
                val dy = downY - e.y
                val dx = e.x - downX
                val dtS = ((e.eventTime - downT).coerceAtLeast(1L)) / 1000f
                if (dy > W * 0.08f) {
                    val v = hypot(dx, dy) / dtS
                    val power = ((v - W) / (W * 5f)).coerceIn(0f, 1f)
                    val ax = (dx / (W * 0.35f)).coerceIn(-1.25f, 1.25f)
                    val ay = (dy / (H * 0.45f)).coerceIn(0f, 1.15f)
                    queueKick(Kick(power, ax, ay))
                }
            }
        }
        return true
    }

    // =====================================================================
    // ציור
    // =====================================================================
    private fun drawField(c: Canvas) {
        proj(0f, 0f, 60f)
        val farY = sy

        fill.shader = skyShader
        c.drawRect(0f, 0f, W, H * 0.14f, fill)
        fill.shader = standShader
        c.drawRect(0f, H * 0.12f, W, farY, fill)
        fill.shader = null

        // קהל
        val r = W * 0.006f
        for (i in crowdColors.indices) {
            fill.color = crowdColors[i]
            val wob = if (outcome == Outcome.GOAL && phase == Phase.RESULT) sin(time * 18f + i) * r * 1.5f else 0f
            c.drawCircle(crowd[i * 2], crowd[i * 2 + 1] + wob, r, fill)
        }

        // שלטי פרסום
        val boardH = W * 0.05f
        val boardTop = farY - boardH
        val segW = W / 4f
        for (i in 0 until 4) {
            fill.color = if (i % 2 == 0) 0xFF0D47A1.toInt() else 0xFFC62828.toInt()
            c.drawRect(i * segW, boardTop, (i + 1) * segW, farY, fill)
            text.textSize = boardH * 0.55f
            c.drawText("FLICK KICK", i * segW + segW / 2, farY - boardH * 0.3f, text)
        }

        // פסי דשא
        var z = -4.6f
        var i = 0
        while (z < 60f) {
            val z2 = z + 2.5f
            fill.color = if (i % 2 == 0) 0xFF2E7D32.toInt() else 0xFF388E3C.toInt()
            path.reset()
            proj(-60f, 0f, z); path.moveTo(sx, sy)
            proj(60f, 0f, z); path.lineTo(sx, sy)
            proj(60f, 0f, z2); path.lineTo(sx, sy)
            proj(-60f, 0f, z2); path.lineTo(sx, sy); path.close()
            c.drawPath(path, fill)
            z = z2; i++
        }

        // קווים
        stroke.color = 0xDDFFFFFF.toInt()
        line3(c, -30f, 0f, goalZ, 30f, 0f, goalZ, 0.08f)
        // רחבת 5.5 מ'
        val sixW = 9.16f
        val sixZ = goalZ - 5.5f
        line3(c, -sixW, 0f, goalZ, -sixW, 0f, sixZ, 0.08f)
        line3(c, sixW, 0f, goalZ, sixW, 0f, sixZ, 0.08f)
        line3(c, -sixW, 0f, sixZ, sixW, 0f, sixZ, 0.08f)
        // רחבת 16.5 מ' (רק החלק שמול המצלמה)
        val boxW = 20.16f
        line3(c, -boxW, 0f, goalZ, -boxW, 0f, -4f, 0.08f)
        line3(c, boxW, 0f, goalZ, boxW, 0f, -4f, 0.08f)
        // נקודת הפנדל
        proj(0f, 0f, 0f)
        fill.color = 0xDDFFFFFF.toInt()
        c.drawOval(sx - 0.15f * sc, sy - 0.05f * sc, sx + 0.15f * sc, sy + 0.05f * sc, fill)
    }

    private fun line3(c: Canvas, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, w: Float) {
        proj(x1, y1, z1); val ax = sx; val ay = sy; val s1 = sc
        proj(x2, y2, z2)
        stroke.strokeWidth = max(1.5f, w * (s1 + sc) / 2f)
        c.drawLine(ax, ay, sx, sy, stroke)
    }

    private fun drawNet(c: Canvas) {
        val back = goalZ + netDepth
        val backH = goalH * 0.75f
        // רקע הרשת
        fill.color = 0x22FFFFFF
        path.reset()
        proj(-goalHW, 0f, back); path.moveTo(sx, sy)
        proj(-goalHW, backH, back); path.lineTo(sx, sy)
        proj(goalHW, backH, back); path.lineTo(sx, sy)
        proj(goalHW, 0f, back); path.lineTo(sx, sy)
        path.close()
        c.drawPath(path, fill)

        stroke.color = 0x88FFFFFF.toInt()
        // קווים אנכיים בגב הרשת + גג
        var x = -goalHW
        while (x <= goalHW + 0.01f) {
            line3(c, x, 0f, back, x, backH, back, 0.015f)
            line3(c, x, backH, back, x, goalH, goalZ, 0.015f)
            x += 0.35f
        }
        var y = 0f
        while (y <= backH + 0.01f) {
            line3(c, -goalHW, y, back, goalHW, y, back, 0.015f)
            y += 0.3f
        }
        // צדדים
        for (sxSide in floatArrayOf(-goalHW, goalHW)) {
            var zz = goalZ
            while (zz <= back + 0.01f) {
                val h = goalH + (backH - goalH) * ((zz - goalZ) / netDepth)
                line3(c, sxSide, 0f, zz, sxSide, h, zz, 0.015f)
                zz += 0.3f
            }
            line3(c, sxSide, 0f, back, sxSide, 0f, goalZ, 0.02f)
        }
    }

    private fun drawGoalFrame(c: Canvas) {
        stroke.color = Color.WHITE
        line3(c, -goalHW, 0f, goalZ, -goalHW, goalH, goalZ, postR * 2)
        line3(c, goalHW, 0f, goalZ, goalHW, goalH, goalZ, postR * 2)
        line3(c, -goalHW, goalH, goalZ, goalHW, goalH, goalZ, postR * 2)
    }

    private fun drawKeeper(c: Canvas) {
        val (cx, cy, a) = keeperPose()
        // צל
        proj(kx, 0f, keeperZ)
        fill.color = 0x55000000
        c.drawOval(sx - 0.5f * sc, sy - 0.08f * sc, sx + 0.5f * sc, sy + 0.08f * sc, fill)

        proj(cx, cy, keeperZ)
        c.save()
        c.translate(sx, sy)
        c.rotate(Math.toDegrees(a.toDouble()).toFloat())
        c.scale(sc, -sc) // מעכשיו: מטרים, y כלפי מעלה, מרכז הגוף ב-(0,0)

        val e = kDiveProg
        // רגליים
        fill.color = 0xFF212121.toInt()
        c.drawRect(-0.24f, -1.1f, -0.06f, -0.3f, fill)
        c.drawRect(0.06f, -1.1f, 0.24f, -0.3f, fill)
        fill.color = 0xFFE8F534.toInt()
        c.drawRect(-0.25f, -1.1f, -0.05f, -0.95f, fill) // גרביים
        c.drawRect(0.05f, -1.1f, 0.25f, -0.95f, fill)
        // מכנסיים
        fill.color = 0xFF111111.toInt()
        c.drawRect(-0.27f, -0.45f, 0.27f, -0.15f, fill)
        // חולצה
        fill.color = 0xFFE8F534.toInt()
        c.drawRect(-0.3f, -0.2f, 0.3f, 0.45f, fill)
        fill.color = 0xFF111111.toInt()
        c.drawRect(-0.3f, 0.05f, 0.3f, 0.12f, fill)
        // ידיים – פתוחות לצדדים בעמידה, מעל הראש בזינוק
        stroke.color = 0xFFE8F534.toInt()
        stroke.strokeWidth = 0.13f
        val handX = 0.6f + (0.18f - 0.6f) * e
        val handY = 0.85f + (1.05f - 0.85f) * e
        c.drawLine(-0.26f, 0.38f, -handX, handY, stroke)
        c.drawLine(0.26f, 0.38f, handX, handY, stroke)
        fill.color = 0xFF66BB6A.toInt()
        c.drawCircle(-handX, handY, 0.1f, fill)
        c.drawCircle(handX, handY, 0.1f, fill)
        // ראש
        fill.color = 0xFFC68642.toInt()
        c.drawCircle(0f, 0.62f, 0.14f, fill)
        fill.color = 0xFF3E2723.toInt()
        c.drawRect(-0.14f, 0.66f, 0.14f, 0.76f, fill)
        c.restore()
    }

    private fun drawBall(c: Canvas) {
        // צל
        proj(bx, 0f, bz)
        val sh = (1f - (by / 4f)).coerceIn(0.2f, 1f)
        fill.color = Color.argb((110 * sh).toInt(), 0, 0, 0)
        c.drawOval(sx - ballR * 1.3f * sc, sy - ballR * 0.4f * sc, sx + ballR * 1.3f * sc, sy + ballR * 0.4f * sc, fill)

        // שובל
        if (phase != Phase.READY) {
            var idx = 0
            for (p in trail) {
                proj(p[0], p[1], p[2])
                fill.color = Color.argb(15 + idx * 8, 255, 255, 255)
                c.drawCircle(sx, sy, ballR * sc * 0.8f, fill)
                idx++
            }
        }

        proj(bx, by, bz)
        val r = max(2f, ballR * sc)
        fill.color = Color.WHITE
        c.drawCircle(sx, sy, r, fill)
        // טלאים מסתובבים
        fill.color = 0xFF1A1A1A.toInt()
        for (k in 0 until 5) {
            val ang = bSpin + k * (2 * PI.toFloat() / 5)
            val dx = cos(ang) * r * 0.62f
            val dy = sin(ang * 0.7f) * r * 0.62f
            c.drawCircle(sx + dx, sy + dy, r * 0.2f, fill)
        }
        c.drawCircle(sx + cos(bSpin * 0.5f) * r * 0.1f, sy, r * 0.24f, fill)
        stroke.color = 0x55000000
        stroke.strokeWidth = max(1f, r * 0.08f)
        c.drawCircle(sx, sy, r, stroke)
    }

    private fun drawCrosshair(c: Canvas) {
        val st = detector?.state
        if (phase != Phase.READY) return
        if (handPts == null && !cameraDenied) return
        if (cameraDenied) return
        proj(aimToWorldX(aimX), aimToWorldY(aimY), goalZ)
        val loaded = st == FlickDetector.State.LOADED
        val pulse = if (loaded) 1f + 0.15f * sin(time * 14f) else 1f
        val r = 0.35f * sc * pulse
        stroke.color = if (loaded) 0xFF00E676.toInt() else 0xCCFFFFFF.toInt()
        stroke.strokeWidth = W * 0.007f
        c.drawCircle(sx, sy, r, stroke)
        c.drawLine(sx - r * 1.4f, sy, sx - r * 0.5f, sy, stroke)
        c.drawLine(sx + r * 0.5f, sy, sx + r * 1.4f, sy, stroke)
        c.drawLine(sx, sy - r * 1.4f, sx, sy - r * 0.5f, stroke)
        c.drawLine(sx, sy + r * 0.5f, sx, sy + r * 1.4f, stroke)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
        text.textSize = size
        textShadow.textSize = size
        c.drawText(s, x + size * 0.05f, y + size * 0.05f, textShadow)
        text.color = color
        c.drawText(s, x, y, text)
        text.color = Color.WHITE
    }

    private fun drawHud(c: Canvas) {
        val top = H * 0.045f
        // לוח תוצאות
        fill.color = 0xAA000000.toInt()
        rect.set(W * 0.04f, top, W * 0.96f, top + W * 0.13f)
        c.drawRoundRect(rect, W * 0.03f, W * 0.03f, fill)
        val y1 = top + W * 0.055f
        val y2 = top + W * 0.105f
        label(c, "שערים", W * 0.8f, y1, W * 0.035f, 0xFFB0BEC5.toInt())
        label(c, "$goals", W * 0.8f, y2, W * 0.055f)
        label(c, "בעיטה", W * 0.5f, y1, W * 0.035f, 0xFFB0BEC5.toInt())
        label(c, "${min(shots + if (phase == Phase.READY) 1 else 0, shotsPerRound)}/$shotsPerRound", W * 0.5f, y2, W * 0.055f)
        label(c, "שיא", W * 0.2f, y1, W * 0.035f, 0xFFB0BEC5.toInt())
        label(c, "$best", W * 0.2f, y2, W * 0.055f, 0xFFFFD54F.toInt())

        // תוצאת בעיטה
        if (phase == Phase.RESULT) {
            val (msg, col) = when (outcome) {
                Outcome.GOAL -> (if (streak >= 3) "גול! רצף $streak 🔥" else "גוֹל!") to 0xFF00E676.toInt()
                Outcome.SAVED -> "השוער הציל!" to 0xFFFFD54F.toInt()
                Outcome.POST -> "פגיעה בקורה!" to 0xFFFF8A65.toInt()
                Outcome.BAR -> "פגיעה במשקוף!" to 0xFFFF8A65.toInt()
                Outcome.OVER -> "מעל המשקוף" to 0xFFEF5350.toInt()
                Outcome.WIDE -> "החוצה" to 0xFFEF5350.toInt()
                Outcome.WEAK -> "חלשה מדי" to 0xFFEF5350.toInt()
                null -> "" to Color.WHITE
            }
            val s = 1f + 0.25f * exp(-resultT * 6f)
            label(c, msg, W / 2, H * 0.30f, W * 0.11f * s, col)
        }

        // מד עוצמה של הבעיטה האחרונה
        if (lastPower >= 0f) {
            val bx0 = W * 0.06f
            val by0 = H * 0.66f
            val bw = W * 0.05f
            val bh = H * 0.16f
            fill.color = 0x88000000.toInt()
            c.drawRect(bx0, by0, bx0 + bw, by0 + bh, fill)
            fill.color = when {
                lastPower > 0.8f -> 0xFFEF5350.toInt()
                lastPower > 0.45f -> 0xFFFFD54F.toInt()
                else -> 0xFF66BB6A.toInt()
            }
            c.drawRect(bx0, by0 + bh * (1 - lastPower), bx0 + bw, by0 + bh, fill)
            label(c, "עוצמה", bx0 + bw / 2, by0 + bh + W * 0.045f, W * 0.032f)
        }

        drawHandBox(c)

        // הנחיה בתחתית
        val hint = when {
            errorText != null -> errorText!!
            cameraDenied -> "אין הרשאת מצלמה – החלק אצבע על המסך כלפי מעלה"
            phase == Phase.GAME_OVER -> ""
            phase != Phase.READY -> ""
            handPts == null -> "הכנס את כף היד למצלמה הקדמית"
            detector?.state == FlickDetector.State.LOADED -> "מוכן! שחרר את האצבע בתנופה ⚡"
            else -> "כוון ביד • הצמד את המורה לאגודל"
        }
        if (hint.isNotEmpty()) {
            fill.color = 0xAA000000.toInt()
            rect.set(W * 0.04f, H * 0.905f, W * 0.96f, H * 0.955f)
            c.drawRoundRect(rect, W * 0.03f, W * 0.03f, fill)
            label(c, hint, W / 2, H * 0.94f, W * 0.04f)
        }
        val fbAt = detector?.feedbackAt ?: 0L
        if (phase == Phase.READY && android.os.SystemClock.uptimeMillis() - fbAt < 1300) {
            label(c, "שחרר מהר יותר!", W / 2, H * 0.88f, W * 0.045f, 0xFFFFD54F.toInt())
        }
    }

    private val handConn = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12,
        9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17
    )

    private fun drawHandBox(c: Canvas) {
        val bw = W * 0.26f
        val bh = bw * 4f / 3f
        val left = W - bw - W * 0.04f
        val top = H * 0.88f - bh
        fill.color = 0x99000000.toInt()
        rect.set(left, top, left + bw, top + bh)
        c.drawRoundRect(rect, W * 0.02f, W * 0.02f, fill)
        val p = handPts ?: run {
            label(c, "✋?", left + bw / 2, top + bh / 2, W * 0.07f, 0x88FFFFFF.toInt())
            return
        }
        val loaded = detector?.state == FlickDetector.State.LOADED
        stroke.color = 0xCCFFFFFF.toInt()
        stroke.strokeWidth = W * 0.005f
        var i = 0
        while (i < handConn.size) {
            val a = handConn[i]; val b = handConn[i + 1]
            c.drawLine(left + p[a * 2] * bw, top + p[a * 2 + 1] * bh,
                left + p[b * 2] * bw, top + p[b * 2 + 1] * bh, stroke)
            i += 2
        }
        for (k in 0 until 21) {
            fill.color = if (k == 4 || k == 8) {
                if (loaded) 0xFF00E676.toInt() else 0xFFFF7043.toInt()
            } else 0xFFFFFFFF.toInt()
            val rr = if (k == 4 || k == 8) W * 0.012f else W * 0.006f
            c.drawCircle(left + p[k * 2] * bw, top + p[k * 2 + 1] * bh, rr, fill)
        }
    }

    private fun drawGameOver(c: Canvas) {
        fill.color = 0xCC000000.toInt()
        c.drawRect(0f, 0f, W, H, fill)
        label(c, "סוף הסבב", W / 2, H * 0.32f, W * 0.1f)
        label(c, "כבשת $goals מתוך $shotsPerRound", W / 2, H * 0.42f, W * 0.07f, 0xFF00E676.toInt())
        label(c, "שיא: $best", W / 2, H * 0.49f, W * 0.06f, 0xFFFFD54F.toInt())
        if (overT > 1f) label(c, "בעט (או הקש על המסך) לסבב חדש", W / 2, H * 0.6f, W * 0.045f)
    }

    private fun draw(c: Canvas) {
        drawField(c)
        drawNet(c)
        if (bz > goalZ) drawBall(c)
        drawGoalFrame(c)
        if (bz in keeperZ..goalZ) drawBall(c)
        drawKeeper(c)
        if (bz < keeperZ) drawBall(c)
        drawCrosshair(c)
        drawHud(c)
        if (phase == Phase.GAME_OVER) drawGameOver(c)
    }
}

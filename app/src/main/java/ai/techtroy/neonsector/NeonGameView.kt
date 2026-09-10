package ai.techtroy.neonsector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The complete, offline game renderer and game loop. It intentionally uses only
 * Android's Canvas APIs: no network access, ads, account, or third-party engine.
 */
class NeonGameView(context: Context) : View(context) {
    private enum class Screen { TITLE, PLAYING, LAB, GAME_OVER }
    private enum class EntityKind { BLOCK, DRONE, MINE, ENERGY, RIFT }

    private data class Entity(
        val lane: Int,
        var y: Float,
        val kind: EntityKind,
        val variant: Int,
        var spin: Float = 0f
    )

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
        val color: Int,
        val radius: Float
    )

    private data class Sector(val name: String, val tag: String, val primary: Int, val secondary: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val random = Random(System.currentTimeMillis())
    private val prefs = context.getSharedPreferences("neon_sector_run", Context.MODE_PRIVATE)

    private val sectors = listOf(
        Sector("GHOST GRID", "CITY FRINGE", Color.rgb(80, 239, 255), Color.rgb(190, 76, 255)),
        Sector("ORBITAL RAIN", "UPPER ATMOSPHERE", Color.rgb(108, 255, 188), Color.rgb(47, 134, 255)),
        Sector("ION REEF", "UNMAPPED CURRENT", Color.rgb(255, 196, 74), Color.rgb(255, 74, 142)),
        Sector("VOID FOUNDRY", "DEEP SIGNAL", Color.rgb(255, 89, 89), Color.rgb(163, 84, 255))
    )

    private var screen = Screen.TITLE
    private var widthF = 1f
    private var heightF = 1f
    private var nowSeconds = 0f
    private var previousNanos = 0L
    private var screenPulse = 0f
    private var hitFlash = 0f
    private var playerLane = 1
    private var playerVisualLane = 1f
    private var scoreDistance = 0f
    private var runEnergy = 0
    private var shieldCharges = 0
    private var spawnClock = 0f
    private var lastSpawnLane = -1
    private var lastSpawnKind = EntityKind.BLOCK
    private var maxSectorRun = 0
    private var entities = mutableListOf<Entity>()
    private var particles = mutableListOf<Particle>()

    private var bits = prefs.getInt(KEY_BITS, 0)
    private var bestScore = prefs.getInt(KEY_BEST, 0)
    private var charted = prefs.getInt(KEY_CHARTED, 0)
    private var reactorLevel = prefs.getInt(KEY_REACTOR, 0).coerceIn(0, 3)
    private var shieldLevel = prefs.getInt(KEY_SHIELD, 0).coerceIn(0, 3)
    private var salvageLevel = prefs.getInt(KEY_SALVAGE, 0).coerceIn(0, 3)
    private var lastRunScore = 0
    private var lastBanked = 0
    private var newBest = false

    private val startButton = RectF()
    private val labButton = RectF()
    private val backButton = RectF()
    private val runAgainButton = RectF()
    private val titleButton = RectF()
    private val upgradeButtons = Array(3) { RectF() }
    private var touchDownX = 0f
    private var touchDownY = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        isFocusable = true
        contentDescription = "Neon Sector Run. Tap left or right to dodge obstacles and collect energy."
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        widthF = max(1, w).toFloat()
        heightF = max(1, h).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val nanos = System.nanoTime()
        if (previousNanos == 0L) previousNanos = nanos
        val rawDt = (nanos - previousNanos) / 1_000_000_000f
        val dt = rawDt.coerceIn(0f, 0.035f)
        previousNanos = nanos
        nowSeconds += dt
        screenPulse = max(0f, screenPulse - dt)
        hitFlash = max(0f, hitFlash - dt * 1.8f)

        if (screen == Screen.PLAYING) updateGame(dt)
        drawBackground(canvas)
        when (screen) {
            Screen.TITLE -> drawTitle(canvas)
            Screen.PLAYING -> drawGame(canvas)
            Screen.LAB -> drawLab(canvas)
            Screen.GAME_OVER -> drawGameOver(canvas)
        }
        postInvalidateOnAnimation()
    }

    private fun updateGame(dt: Float) {
        scoreDistance += dt * (19f + min(18f, scoreDistance / 90f))
        maxSectorRun = sectorIndex()
        val fallSpeed = heightF * (0.39f + min(0.18f, scoreDistance / 2900f))
        val spawnInterval = max(0.31f, 0.78f - scoreDistance / 1600f)
        spawnClock += dt
        if (spawnClock >= spawnInterval) {
            spawnClock -= spawnInterval
            spawnEntity()
        }

        playerVisualLane += (playerLane - playerVisualLane) * min(1f, dt * 15f)
        val playerX = laneX(playerVisualLane)
        addParticle(playerX, playerY() + heightF * 0.045f, random.nextFloat() * 18f - 9f, 42f + random.nextFloat() * 32f,
            0.24f, Color.argb(140, 112, 241, 255), heightF * 0.006f)

        val playerHitY = playerY()
        val iterator = entities.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            entity.y += fallSpeed * dt
            entity.spin += dt * (if (entity.lane % 2 == 0) 1.8f else -1.8f)
            if (entity.y > heightF + heightF * 0.12f) {
                iterator.remove()
                continue
            }
            if (abs(entity.y - playerHitY) < heightF * 0.062f && entity.lane == playerLane) {
                when (entity.kind) {
                    EntityKind.ENERGY -> {
                        runEnergy += 1
                        scoreDistance += 10f
                        burst(laneX(entity.lane.toFloat()), entity.y, Color.rgb(112, 241, 255), 10)
                        iterator.remove()
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    EntityKind.RIFT -> {
                        runEnergy += 4
                        scoreDistance += 35f
                        burst(laneX(entity.lane.toFloat()), entity.y, sector().primary, 22)
                        iterator.remove()
                        screenPulse = 0.28f
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    else -> {
                        iterator.remove()
                        crash()
                        return
                    }
                }
            }
        }

        val pIterator = particles.iterator()
        while (pIterator.hasNext()) {
            val particle = pIterator.next()
            particle.x += particle.vx * dt
            particle.y += particle.vy * dt
            particle.vy += heightF * 0.18f * dt
            particle.life -= dt
            if (particle.life <= 0f) pIterator.remove()
        }
    }

    private fun spawnEntity() {
        var lane = random.nextInt(3)
        // Never make the same tight lane pattern repeat at the beginning of a run.
        if (lane == lastSpawnLane && random.nextFloat() < 0.6f) lane = (lane + 1 + random.nextInt(2)) % 3
        val roll = random.nextFloat()
        val kind = when {
            roll < 0.13f -> EntityKind.ENERGY
            roll < 0.17f && scoreDistance > 120f -> EntityKind.RIFT
            roll < 0.46f -> EntityKind.BLOCK
            roll < 0.76f -> EntityKind.DRONE
            else -> EntityKind.MINE
        }
        entities.add(Entity(lane, -heightF * 0.12f, kind, random.nextInt(4)))
        lastSpawnLane = lane
        lastSpawnKind = kind
    }

    private fun startRun() {
        screen = Screen.PLAYING
        scoreDistance = 0f
        runEnergy = 0
        shieldCharges = shieldLevel
        playerLane = 1
        playerVisualLane = 1f
        spawnClock = 0.28f
        lastSpawnLane = -1
        lastSpawnKind = EntityKind.BLOCK
        maxSectorRun = 0
        entities.clear()
        particles.clear()
        hitFlash = 0f
        newBest = false
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun crash() {
        if (shieldCharges > 0) {
            shieldCharges -= 1
            hitFlash = 0.22f
            burst(laneX(playerLane.toFloat()), playerY(), Color.rgb(130, 230, 255), 30)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        burst(laneX(playerLane.toFloat()), playerY(), Color.WHITE, 40)
        lastRunScore = finalScore()
        lastBanked = max(1, ((runEnergy + lastRunScore / 90f) * (1f + salvageLevel * 0.35f)).toInt())
        bits += lastBanked
        if (lastRunScore > bestScore) {
            bestScore = lastRunScore
            newBest = true
        }
        charted = max(charted, maxSectorRun)
        prefs.edit()
            .putInt(KEY_BITS, bits)
            .putInt(KEY_BEST, bestScore)
            .putInt(KEY_CHARTED, charted)
            .apply()
        screen = Screen.GAME_OVER
        screenPulse = 0.34f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun movePlayer(direction: Int) {
        val target = (playerLane + direction).coerceIn(0, 2)
        if (target != playerLane) {
            playerLane = target
            burst(laneX(playerVisualLane), playerY(), sector().primary, 7)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun buyUpgrade(index: Int) {
        val level = when (index) {
            0 -> reactorLevel
            1 -> shieldLevel
            else -> salvageLevel
        }
        val price = upgradePrice(index, level)
        if (level >= 3 || bits < price) {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        bits -= price
        when (index) {
            0 -> reactorLevel++
            1 -> shieldLevel++
            else -> salvageLevel++
        }
        prefs.edit()
            .putInt(KEY_BITS, bits)
            .putInt(KEY_REACTOR, reactorLevel)
            .putInt(KEY_SHIELD, shieldLevel)
            .putInt(KEY_SALVAGE, salvageLevel)
            .apply()
        screenPulse = 0.18f
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun finalScore(): Int = (scoreDistance * (1f + reactorLevel * 0.25f)).toInt()
    private fun sectorIndex(): Int = min(sectors.lastIndex, (scoreDistance / 210f).toInt())
    private fun sector(): Sector = sectors[sectorIndex()]
    private fun playerY(): Float = heightF * 0.755f
    private fun laneX(lane: Float): Float {
        val left = widthF * 0.22f
        val right = widthF * 0.78f
        return left + (right - left) * (lane / 2f)
    }

    private fun drawBackground(canvas: Canvas) {
        val sector = if (screen == Screen.PLAYING || screen == Screen.GAME_OVER) sector() else sectors[charted.coerceIn(0, sectors.lastIndex)]
        paint.shader = LinearGradient(0f, 0f, 0f, heightF, Color.rgb(5, 8, 23), Color.rgb(12, 10, 34), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, widthF, heightF, paint)
        paint.shader = null

        // A deterministic moving starfield makes every sector feel like a place rather than a plain menu.
        for (i in 0 until 54) {
            val x = ((i * 83.31f + 41f) % widthF)
            val y = ((i * 137.17f + nowSeconds * (10f + i % 5 * 7f)) % heightF)
            val alpha = 35 + (i * 41 % 95)
            paint.color = Color.argb(alpha, Color.red(sector.primary), Color.green(sector.primary), Color.blue(sector.primary))
            canvas.drawCircle(x, y, if (i % 6 == 0) 2.4f else 1.15f, paint)
        }

        val horizon = heightF * 0.30f
        paint.color = Color.argb(40, Color.red(sector.secondary), Color.green(sector.secondary), Color.blue(sector.secondary))
        for (i in 0..8) {
            val y = horizon + i * i * heightF * 0.013f + (nowSeconds * 32f % 22f)
            canvas.drawRect(widthF * 0.08f, y, widthF * 0.92f, y + 1.4f, paint)
        }
        val center = widthF / 2f
        for (i in -6..6) {
            paint.color = Color.argb(40, Color.red(sector.primary), Color.green(sector.primary), Color.blue(sector.primary))
            val topX = center + i * widthF * 0.03f
            val bottomX = center + i * widthF * 0.135f
            paint.strokeWidth = 1.6f
            canvas.drawLine(topX, horizon, bottomX, heightF, paint)
        }

        if (screenPulse > 0f || hitFlash > 0f) {
            val a = ((screenPulse + hitFlash) * 100).toInt().coerceIn(0, 65)
            paint.color = Color.argb(a, Color.red(sector.primary), Color.green(sector.primary), Color.blue(sector.primary))
            canvas.drawRect(0f, 0f, widthF, heightF, paint)
        }
    }

    private fun drawTitle(canvas: Canvas) {
        val sector = sectors[charted.coerceIn(0, sectors.lastIndex)]
        val cy = heightF * 0.27f
        drawOrbitMark(canvas, widthF / 2f, cy - heightF * 0.065f, heightF * 0.082f, sector.primary, sector.secondary)
        label(canvas, "NEON", widthF / 2f, cy + heightF * 0.035f, heightF * 0.052f, sector.primary, true)
        label(canvas, "SECTOR RUN", widthF / 2f, cy + heightF * 0.095f, heightF * 0.052f, Color.WHITE, true)
        label(canvas, "DODGE  ·  DISCOVER  ·  BUILD", widthF / 2f, cy + heightF * 0.145f, heightF * 0.015f, Color.rgb(167, 190, 219), true)

        val card = RectF(widthF * 0.105f, heightF * 0.50f, widthF * 0.895f, heightF * 0.62f)
        drawPanel(canvas, card, sector.primary, 0.55f)
        label(canvas, "NEXT DESTINATION", card.centerX(), card.top + heightF * 0.032f, heightF * 0.014f, Color.rgb(151, 175, 204), true)
        label(canvas, sector.name, card.centerX(), card.top + heightF * 0.077f, heightF * 0.028f, Color.WHITE, true)
        label(canvas, "CHARTED ${charted + 1} / ${sectors.size} SECTORS   •   BEST $bestScore", card.centerX(), card.bottom - heightF * 0.018f, heightF * 0.014f, sector.primary, true)

        startButton.set(widthF * 0.105f, heightF * 0.665f, widthF * 0.895f, heightF * 0.755f)
        drawButton(canvas, startButton, "START RUN", "TAP LEFT / RIGHT TO DODGE", sector.primary, true)
        labButton.set(widthF * 0.105f, heightF * 0.778f, widthF * 0.895f, heightF * 0.852f)
        drawButton(canvas, labButton, "FABRICATION BAY", "$bits BITS READY TO BUILD", sector.secondary, false)
        label(canvas, "OFFLINE • NO ADS • YOUR DEVICE, YOUR RUN", widthF / 2f, heightF * 0.927f, heightF * 0.0125f, Color.rgb(104, 123, 157), true)
    }

    private fun drawGame(canvas: Canvas) {
        val sector = sector()
        drawRoad(canvas, sector)
        for (entity in entities) drawEntity(canvas, entity, sector)
        drawParticles(canvas)
        drawPlayer(canvas, sector)

        val hud = RectF(widthF * 0.045f, heightF * 0.032f, widthF * 0.955f, heightF * 0.135f)
        drawPanel(canvas, hud, sector.primary, 0.72f)
        label(canvas, sector.tag, hud.left + widthF * 0.03f, hud.top + heightF * 0.030f, heightF * 0.012f, Color.rgb(170, 196, 220), false)
        label(canvas, sector.name, hud.left + widthF * 0.03f, hud.bottom - heightF * 0.020f, heightF * 0.024f, Color.WHITE, false)
        label(canvas, "${finalScore()}", hud.right - widthF * 0.03f, hud.top + heightF * 0.050f, heightF * 0.040f, sector.primary, false, Paint.Align.RIGHT)
        label(canvas, "ENERGY $runEnergy", hud.right - widthF * 0.03f, hud.bottom - heightF * 0.021f, heightF * 0.014f, Color.rgb(188, 210, 240), false, Paint.Align.RIGHT)

        if (shieldCharges > 0) {
            label(canvas, "PHASE ${"◆".repeat(shieldCharges)}", widthF / 2f, heightF * 0.165f, heightF * 0.016f, Color.rgb(141, 229, 255), true)
        }
        label(canvas, "TAP OR SWIPE TO CHANGE LANE", widthF / 2f, heightF * 0.945f, heightF * 0.012f, Color.argb(175, 196, 210, 239), true)
    }

    private fun drawRoad(canvas: Canvas, sector: Sector) {
        val top = heightF * 0.18f
        val bottom = heightF * 0.93f
        val leftTop = widthF * 0.39f
        val rightTop = widthF * 0.61f
        val leftBottom = widthF * 0.10f
        val rightBottom = widthF * 0.90f
        path.reset()
        path.moveTo(leftTop, top)
        path.lineTo(rightTop, top)
        path.lineTo(rightBottom, bottom)
        path.lineTo(leftBottom, bottom)
        path.close()
        paint.color = Color.argb(190, 8, 13, 36)
        canvas.drawPath(path, paint)

        paint.color = Color.argb(115, Color.red(sector.secondary), Color.green(sector.secondary), Color.blue(sector.secondary))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.setShadowLayer(9f, 0f, 0f, sector.secondary)
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL

        for (line in 1..2) {
            val topX = leftTop + (rightTop - leftTop) * line / 3f
            val bottomX = leftBottom + (rightBottom - leftBottom) * line / 3f
            paint.color = Color.argb(90, Color.red(sector.primary), Color.green(sector.primary), Color.blue(sector.primary))
            canvas.drawLine(topX, top, bottomX, bottom, paint)
        }
    }

    private fun drawEntity(canvas: Canvas, entity: Entity, sector: Sector) {
        val x = laneX(entity.lane.toFloat())
        val unit = heightF * 0.044f * (0.74f + entity.y / heightF * 0.44f)
        when (entity.kind) {
            EntityKind.ENERGY -> {
                paint.color = Color.rgb(112, 241, 255)
                paint.setShadowLayer(unit * 0.8f, 0f, 0f, Color.rgb(112, 241, 255))
                canvas.drawCircle(x, entity.y, unit * 0.45f, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(x, entity.y, unit * 0.14f, paint)
                paint.clearShadowLayer()
            }
            EntityKind.RIFT -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = unit * 0.18f
                paint.color = sector.primary
                paint.setShadowLayer(unit * 0.9f, 0f, 0f, sector.primary)
                canvas.drawCircle(x, entity.y, unit * 0.58f + sin(nowSeconds * 6f) * unit * 0.08f, paint)
                paint.color = Color.WHITE
                paint.strokeWidth = unit * 0.06f
                canvas.drawCircle(x, entity.y, unit * 0.28f, paint)
                paint.clearShadowLayer()
                paint.style = Paint.Style.FILL
            }
            EntityKind.BLOCK -> {
                val r = RectF(x - unit * 0.62f, entity.y - unit * 0.62f, x + unit * 0.62f, entity.y + unit * 0.62f)
                paint.color = Color.rgb(255, 83, 121)
                paint.setShadowLayer(unit * 0.75f, 0f, 0f, Color.rgb(255, 50, 100))
                canvas.save()
                canvas.rotate(entity.spin * 15f, x, entity.y)
                canvas.drawRoundRect(r, unit * 0.15f, unit * 0.15f, paint)
                paint.color = Color.argb(165, 40, 12, 60)
                canvas.drawRect(x - unit * 0.55f, entity.y - unit * 0.08f, x + unit * 0.55f, entity.y + unit * 0.08f, paint)
                canvas.restore()
                paint.clearShadowLayer()
            }
            EntityKind.DRONE -> {
                paint.color = Color.rgb(255, 178, 67)
                paint.setShadowLayer(unit * 0.8f, 0f, 0f, Color.rgb(255, 139, 44))
                path.reset()
                path.moveTo(x, entity.y - unit * 0.68f)
                path.lineTo(x + unit * 0.72f, entity.y)
                path.lineTo(x, entity.y + unit * 0.68f)
                path.lineTo(x - unit * 0.72f, entity.y)
                path.close()
                canvas.drawPath(path, paint)
                paint.color = Color.rgb(33, 20, 46)
                canvas.drawCircle(x, entity.y, unit * 0.21f, paint)
                paint.clearShadowLayer()
            }
            EntityKind.MINE -> {
                paint.color = Color.rgb(202, 75, 255)
                paint.setShadowLayer(unit * 0.7f, 0f, 0f, Color.rgb(197, 63, 255))
                canvas.drawCircle(x, entity.y, unit * 0.45f, paint)
                paint.color = Color.rgb(28, 14, 48)
                canvas.drawCircle(x, entity.y, unit * 0.19f, paint)
                paint.color = Color.rgb(239, 176, 255)
                for (i in 0..5) {
                    val a = entity.spin + i * Math.PI.toFloat() / 3f
                    canvas.drawCircle(x + kotlin.math.cos(a) * unit * 0.61f, entity.y + kotlin.math.sin(a) * unit * 0.61f, unit * 0.10f, paint)
                }
                paint.clearShadowLayer()
            }
        }
    }

    private fun drawPlayer(canvas: Canvas, sector: Sector) {
        val x = laneX(playerVisualLane)
        val y = playerY()
        val unit = heightF * 0.058f
        paint.color = Color.argb(75, Color.red(sector.primary), Color.green(sector.primary), Color.blue(sector.primary))
        paint.setShadowLayer(unit * 1.25f, 0f, unit * 0.3f, sector.primary)
        canvas.drawCircle(x, y + unit * 0.32f, unit * 0.72f, paint)
        paint.clearShadowLayer()

        path.reset()
        path.moveTo(x, y - unit * 0.95f)
        path.lineTo(x + unit * 0.69f, y + unit * 0.68f)
        path.lineTo(x, y + unit * 0.43f)
        path.lineTo(x - unit * 0.69f, y + unit * 0.68f)
        path.close()
        paint.color = Color.rgb(224, 252, 255)
        paint.setShadowLayer(unit * 0.7f, 0f, 0f, sector.primary)
        canvas.drawPath(path, paint)
        paint.color = sector.primary
        path.reset()
        path.moveTo(x, y - unit * 0.54f)
        path.lineTo(x + unit * 0.30f, y + unit * 0.40f)
        path.lineTo(x, y + unit * 0.25f)
        path.lineTo(x - unit * 0.30f, y + unit * 0.40f)
        path.close()
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()

        if (shieldCharges > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = unit * 0.07f
            paint.color = Color.argb(170, 135, 232, 255)
            canvas.drawCircle(x, y, unit * 1.17f + sin(nowSeconds * 4f) * unit * 0.06f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (particle in particles) {
            val alpha = (255f * particle.life / particle.maxLife).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, Color.red(particle.color), Color.green(particle.color), Color.blue(particle.color))
            canvas.drawCircle(particle.x, particle.y, particle.radius * particle.life / particle.maxLife, paint)
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        drawRoad(canvas, sector())
        for (entity in entities) drawEntity(canvas, entity, sector())
        drawParticles(canvas)
        paint.color = Color.argb(190, 3, 5, 17)
        canvas.drawRect(0f, 0f, widthF, heightF, paint)
        val sector = sectors[maxSectorRun.coerceIn(0, sectors.lastIndex)]
        label(canvas, if (newBest) "NEW SIGNAL RECORD" else "RUN INTERRUPTED", widthF / 2f, heightF * 0.218f, heightF * 0.018f, sector.primary, true)
        label(canvas, "$lastRunScore", widthF / 2f, heightF * 0.300f, heightF * 0.073f, Color.WHITE, true)
        label(canvas, "RUN SCORE", widthF / 2f, heightF * 0.337f, heightF * 0.014f, Color.rgb(159, 183, 216), true)

        val stats = RectF(widthF * 0.105f, heightF * 0.403f, widthF * 0.895f, heightF * 0.520f)
        drawPanel(canvas, stats, sector.secondary, 0.65f)
        label(canvas, "+$lastBanked", stats.left + widthF * 0.13f, stats.top + heightF * 0.056f, heightF * 0.034f, sector.primary, true)
        label(canvas, "BITS BANKED", stats.left + widthF * 0.13f, stats.bottom - heightF * 0.020f, heightF * 0.013f, Color.rgb(176, 198, 224), true)
        label(canvas, "${maxSectorRun + 1} / ${sectors.size}", stats.right - widthF * 0.13f, stats.top + heightF * 0.056f, heightF * 0.034f, Color.WHITE, true)
        label(canvas, "SECTORS SEEN", stats.right - widthF * 0.13f, stats.bottom - heightF * 0.020f, heightF * 0.013f, Color.rgb(176, 198, 224), true)

        runAgainButton.set(widthF * 0.105f, heightF * 0.587f, widthF * 0.895f, heightF * 0.675f)
        drawButton(canvas, runAgainButton, "RUN AGAIN", "PUSH FURTHER INTO THE SIGNAL", sector.primary, true)
        titleButton.set(widthF * 0.105f, heightF * 0.699f, widthF * 0.895f, heightF * 0.774f)
        drawButton(canvas, titleButton, "RETURN TO BASE", "$bits BITS IN THE FABRICATION BAY", sector.secondary, false)
        label(canvas, "TIP: RIFTS HOLD EXTRA BITS. PHASE SHIELDS CAN SAVE A RUN.", widthF / 2f, heightF * 0.862f, heightF * 0.012f, Color.rgb(141, 165, 199), true)
    }

    private fun drawLab(canvas: Canvas) {
        val sector = sectors[charted.coerceIn(0, sectors.lastIndex)]
        label(canvas, "FABRICATION BAY", widthF / 2f, heightF * 0.105f, heightF * 0.040f, Color.WHITE, true)
        label(canvas, "TURN RECOVERED BITS INTO NEW RUN TECH", widthF / 2f, heightF * 0.140f, heightF * 0.0135f, sector.primary, true)
        val bank = RectF(widthF * 0.29f, heightF * 0.170f, widthF * 0.71f, heightF * 0.224f)
        drawPanel(canvas, bank, sector.secondary, 0.65f)
        label(canvas, "$bits BITS AVAILABLE", bank.centerX(), bank.centerY() + heightF * 0.006f, heightF * 0.021f, Color.WHITE, true)

        val names = arrayOf("REACTOR TUNE", "PHASE SHELL", "SALVAGE ARRAY")
        val details = arrayOf("+25% RUN SCORE / LEVEL", "START WITH 1 CRASH SAVE / LEVEL", "+35% BANKED BITS / LEVEL")
        val levels = intArrayOf(reactorLevel, shieldLevel, salvageLevel)
        for (i in 0..2) {
            val top = heightF * (0.267f + i * 0.174f)
            val card = RectF(widthF * 0.075f, top, widthF * 0.925f, top + heightF * 0.145f)
            drawPanel(canvas, card, if (i == 1) sector.secondary else sector.primary, 0.58f)
            label(canvas, names[i], card.left + widthF * 0.045f, card.top + heightF * 0.043f, heightF * 0.021f, Color.WHITE, false)
            label(canvas, details[i], card.left + widthF * 0.045f, card.bottom - heightF * 0.023f, heightF * 0.0125f, Color.rgb(165, 190, 220), false)
            val levelWord = if (levels[i] == 3) "MAX" else "LV ${levels[i]} / 3"
            label(canvas, levelWord, card.right - widthF * 0.045f, card.top + heightF * 0.039f, heightF * 0.015f, sector.primary, false, Paint.Align.RIGHT)
            upgradeButtons[i].set(card.right - widthF * 0.255f, card.bottom - heightF * 0.062f, card.right - widthF * 0.045f, card.bottom - heightF * 0.017f)
            val price = if (levels[i] >= 3) 0 else upgradePrice(i, levels[i])
            drawSmallButton(canvas, upgradeButtons[i], if (levels[i] >= 3) "BUILT" else "BUILD $price", if (levels[i] >= 3) Color.rgb(87, 112, 137) else sector.secondary)
        }
        backButton.set(widthF * 0.105f, heightF * 0.833f, widthF * 0.895f, heightF * 0.907f)
        drawButton(canvas, backButton, "RETURN TO RUNNER", "KEEP EXPLORING, KEEP BUILDING", sector.primary, false)
    }

    private fun drawOrbitMark(canvas: Canvas, x: Float, y: Float, r: Float, first: Int, second: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        paint.color = first
        paint.setShadowLayer(r * 0.35f, 0f, 0f, first)
        canvas.drawCircle(x, y, r, paint)
        paint.color = second
        canvas.drawOval(RectF(x - r * 1.42f, y - r * 0.47f, x + r * 1.42f, y + r * 0.47f), paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x + r * 1.27f, y, r * 0.11f, paint)
    }

    private fun drawPanel(canvas: Canvas, rect: RectF, accent: Int, opacity: Float) {
        paint.color = Color.argb((opacity * 225).toInt(), 11, 17, 44)
        canvas.drawRoundRect(rect, heightF * 0.018f, heightF * 0.018f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.4f, heightF * 0.0017f)
        paint.color = Color.argb(170, Color.red(accent), Color.green(accent), Color.blue(accent))
        paint.setShadowLayer(heightF * 0.01f, 0f, 0f, accent)
        canvas.drawRoundRect(rect, heightF * 0.018f, heightF * 0.018f, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun drawButton(canvas: Canvas, rect: RectF, main: String, sub: String, accent: Int, filled: Boolean) {
        if (filled) {
            paint.color = Color.argb(230, Color.red(accent), Color.green(accent), Color.blue(accent))
            paint.setShadowLayer(heightF * 0.022f, 0f, 0f, accent)
            canvas.drawRoundRect(rect, heightF * 0.020f, heightF * 0.020f, paint)
            paint.clearShadowLayer()
            label(canvas, main, rect.centerX(), rect.top + rect.height() * 0.46f, heightF * 0.025f, Color.rgb(6, 10, 26), true)
            label(canvas, sub, rect.centerX(), rect.top + rect.height() * 0.72f, heightF * 0.0115f, Color.rgb(15, 24, 42), true)
        } else {
            drawPanel(canvas, rect, accent, 0.68f)
            label(canvas, main, rect.centerX(), rect.top + rect.height() * 0.47f, heightF * 0.023f, Color.WHITE, true)
            label(canvas, sub, rect.centerX(), rect.top + rect.height() * 0.73f, heightF * 0.0115f, accent, true)
        }
    }

    private fun drawSmallButton(canvas: Canvas, rect: RectF, title: String, accent: Int) {
        paint.color = Color.argb(210, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(rect, heightF * 0.011f, heightF * 0.011f, paint)
        label(canvas, title, rect.centerX(), rect.centerY() + heightF * 0.006f, heightF * 0.013f, Color.rgb(7, 11, 27), true)
    }

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        centered: Boolean,
        alignment: Paint.Align = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
    ) {
        text.textSize = size
        text.color = color
        text.textAlign = alignment
        text.typeface = if (size > heightF * 0.022f) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(value, x, y, text)
    }

    private fun addParticle(x: Float, y: Float, vx: Float, vy: Float, life: Float, color: Int, radius: Float) {
        if (particles.size < 120) particles.add(Particle(x, y, vx, vy, life, life, color, radius))
    }

    private fun burst(x: Float, y: Float, color: Int, amount: Int) {
        repeat(amount) {
            val angle = random.nextFloat() * (Math.PI * 2.0).toFloat()
            val speed = heightF * (0.06f + random.nextFloat() * 0.22f)
            addParticle(x, y, kotlin.math.cos(angle) * speed, kotlin.math.sin(angle) * speed, 0.28f + random.nextFloat() * 0.36f, color, heightF * (0.004f + random.nextFloat() * 0.008f))
        }
    }

    private fun upgradePrice(index: Int, level: Int): Int = when (index) {
        0 -> intArrayOf(12, 28, 52)[level]
        1 -> intArrayOf(16, 34, 62)[level]
        else -> intArrayOf(14, 30, 56)[level]
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                when (screen) {
                    Screen.TITLE -> when {
                        startButton.contains(x, y) -> startRun()
                        labButton.contains(x, y) -> {
                            screen = Screen.LAB
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    Screen.LAB -> {
                        if (backButton.contains(x, y)) {
                            screen = Screen.TITLE
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } else {
                            for (i in upgradeButtons.indices) if (upgradeButtons[i].contains(x, y)) buyUpgrade(i)
                        }
                    }
                    Screen.GAME_OVER -> when {
                        runAgainButton.contains(x, y) -> startRun()
                        titleButton.contains(x, y) -> {
                            screen = Screen.TITLE
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    Screen.PLAYING -> {
                        val dx = x - touchDownX
                        if (abs(dx) > widthF * 0.09f) movePlayer(if (dx > 0f) 1 else -1)
                        else movePlayer(if (x < widthF * 0.5f) -1 else 1)
                    }
                }
                return true
            }
        }
        return true
    }

    /** @return true when Back was handled by returning to the launch screen. */
    fun returnToTitle(): Boolean {
        return when (screen) {
            Screen.TITLE -> false
            else -> {
                screen = Screen.TITLE
                entities.clear()
                particles.clear()
                true
            }
        }
    }

    private companion object {
        const val KEY_BITS = "bits"
        const val KEY_BEST = "best_score"
        const val KEY_CHARTED = "charted_sector"
        const val KEY_REACTOR = "reactor_level"
        const val KEY_SHIELD = "shield_level"
        const val KEY_SALVAGE = "salvage_level"
    }
}

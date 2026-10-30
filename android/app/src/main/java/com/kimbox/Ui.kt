package com.kimbox

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 原生壳的界面零件：底部面板、设置行、分段选择、按钮、悬浮入口。
 * 不引 androidx/Material，全部手搓；配色跟随网页实际明暗（见 MainActivity.onThemeReport）。
 */
object Ui {

    data class Palette(
        val dark: Boolean,
        val bg: Int,
        val surface: Int,
        val text: Int,
        val sub: Int,
        val divider: Int,
        val accent: Int,
        val onAccent: Int,
        val danger: Int,
    )

    val LIGHT = Palette(
        dark = false,
        bg = Color.WHITE,
        surface = Color.rgb(244, 244, 245),
        text = Color.rgb(24, 24, 27),
        sub = Color.rgb(113, 113, 122),
        divider = Color.rgb(228, 228, 231),
        accent = Color.rgb(24, 24, 27),
        onAccent = Color.WHITE,
        danger = Color.rgb(239, 68, 68),
    )

    val DARK = Palette(
        dark = true,
        bg = Color.rgb(30, 30, 33),
        surface = Color.rgb(44, 44, 48),
        text = Color.rgb(244, 244, 245),
        sub = Color.rgb(161, 161, 170),
        divider = Color.rgb(63, 63, 70),
        accent = Color.rgb(244, 244, 245),
        onAccent = Color.rgb(24, 24, 27),
        danger = Color.rgb(248, 113, 113),
    )

    fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokePx: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) setStroke(strokePx, strokeColor)
        }

    private fun ripple(p: Palette, content: GradientDrawable?, radius: Float): RippleDrawable {
        val pressed = if (p.dark) Color.argb(40, 255, 255, 255) else Color.argb(24, 0, 0, 0)
        val mask = rounded(Color.WHITE, radius)
        return RippleDrawable(ColorStateList.valueOf(pressed), content, mask)
    }

    /** 高度不超过屏幕高度一定比例的 ScrollView；maxFraction <= 0 表示不设上限 */
    private class CappedScrollView(a: Activity) : ScrollView(a) {
        var maxFraction = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var spec = heightMeasureSpec
            if (maxFraction > 0f) {
                val cap = (resources.displayMetrics.heightPixels * maxFraction).toInt()
                val size = MeasureSpec.getSize(heightMeasureSpec)
                spec = when (MeasureSpec.getMode(heightMeasureSpec)) {
                    MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
                    MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(minOf(cap, size), MeasureSpec.AT_MOST)
                    else -> heightMeasureSpec
                }
            }
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    /** 底部弹出面板：圆角顶、拖拽条、标题，内容区可滚动 */
    class Sheet(val activity: Activity, val p: Palette, title: String?, subtitle: String? = null) {
        val dialog = Dialog(activity)
        val body: LinearLayout
        private val root: LinearLayout
        private val scroll: CappedScrollView

        init {
            val a = activity
            root = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(p.bg)
                    val r = a.dp(20).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                setPadding(a.dp(20), a.dp(10), a.dp(20), a.dp(20))
            }
            root.addView(View(a).apply { background = rounded(p.divider, a.dp(2).toFloat()) },
                LinearLayout.LayoutParams(a.dp(36), a.dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = a.dp(14)
                })
            if (title != null) {
                root.addView(TextView(a).apply {
                    text = title
                    textSize = 19f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.text)
                })
            }
            if (subtitle != null) {
                root.addView(TextView(a).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(p.sub)
                    setPadding(0, a.dp(2), 0, 0)
                })
            }
            body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            scroll = CappedScrollView(a).apply {
                isVerticalScrollBarEnabled = false
                addView(body)
            }
            // weight=1：窗口放不下时（横屏、分屏）LinearLayout 会把超出的高度从滚动区里扣掉，
            // 保证标题和底部按钮始终可见；放得下时 AT_MOST 测量下 delta 为 0，不会被撑大
            root.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { topMargin = a.dp(if (title != null) 12 else 0) })

            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setWindowAnimations(android.R.style.Animation_InputMethod)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.35f)
                navigationBarColor = p.bg
            }
        }

        /** 限制内容区最大高度（占屏幕高度的比例，转屏后按新高度重算），长文本在面板里滚动 */
        fun maxBodyHeight(fraction: Float) {
            scroll.maxFraction = fraction
            scroll.requestLayout()
        }

        fun add(v: View, topMarginDp: Int = 0) {
            body.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(topMarginDp) })
        }

        fun addFooter(v: View) {
            root.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(16) })
        }

        fun sectionLabel(text: String, topMarginDp: Int = 14) {
            add(TextView(activity).apply {
                this.text = text
                textSize = 12f
                setTextColor(p.sub)
                setPadding(activity.dp(4), 0, 0, activity.dp(6))
            }, topMarginDp)
        }

        fun show() = dialog.show()
        fun dismiss() = dialog.dismiss()
    }

    /** 设置行：左图标块 + 标题/说明 + 右侧尾巴（徽标或箭头） */
    fun row(
        a: Activity, p: Palette, icon: String, title: String, subtitle: String?,
        trailing: String? = "›", trailingColor: Int? = null, dot: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val radius = a.dp(14).toFloat()
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(10), a.dp(12), a.dp(12), a.dp(12))
            background = ripple(p, null, radius)
            isClickable = true
            setOnClickListener { onClick() }

            val iconBox = FrameLayout(a)
            iconBox.addView(TextView(a).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(p.text)
                background = rounded(p.surface, a.dp(10).toFloat())
            }, FrameLayout.LayoutParams(a.dp(38), a.dp(38)))
            if (dot) {
                iconBox.addView(View(a).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.danger) }
                }, FrameLayout.LayoutParams(a.dp(9), a.dp(9), Gravity.TOP or Gravity.END))
            }
            addView(iconBox, LinearLayout.LayoutParams(a.dp(40), a.dp(40)))

            val texts = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(12), 0, a.dp(8), 0)
                addView(TextView(a).apply {
                    text = title
                    textSize = 16f
                    setTextColor(p.text)
                })
                if (subtitle != null) addView(TextView(a).apply {
                    text = subtitle
                    textSize = 12.5f
                    setTextColor(p.sub)
                    setPadding(0, a.dp(1), 0, 0)
                })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (trailing != null) addView(TextView(a).apply {
                text = trailing
                textSize = if (trailing == "›") 22f else 13f
                setTextColor(trailingColor ?: p.sub)
            })
        }
    }

    /** 分段选择（如 跟随系统 / 亮色 / 暗色），点中立即回调 */
    fun segmented(a: Activity, p: Palette, labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.surface, a.dp(12).toFloat())
            setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3))
        }
        val cells = labels.mapIndexed { i, label ->
            TextView(a).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, a.dp(9), 0, a.dp(9))
                isClickable = true
            }.also { box.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        fun paint(sel: Int) {
            cells.forEachIndexed { i, c ->
                val on = i == sel
                c.isSelected = on // 读屏软件据此报「已选择」
                c.setTextColor(if (on) p.text else p.sub)
                c.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
                c.background = if (on) rounded(if (p.dark) p.divider else p.bg, a.dp(9).toFloat()) else null
                c.elevation = if (on && !p.dark) a.dp(1).toFloat() else 0f
            }
        }
        paint(selected)
        cells.forEachIndexed { i, c -> c.setOnClickListener { paint(i); onPick(i) } }
        return box
    }

    fun button(a: Activity, p: Palette, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val radius = a.dp(12).toFloat()
        return TextView(a).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) p.onAccent else p.text)
            background = ripple(p, rounded(if (primary) p.accent else p.surface, radius), radius)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun buttonRow(a: Activity, vararg buttons: TextView): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = a.dp(10)
            })
        }
    }

    fun chip(a: Activity, p: Palette, label: String): TextView = TextView(a).apply {
        text = label
        textSize = 12f
        setTextColor(p.text)
        setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
        background = rounded(p.surface, a.dp(8).toFloat())
    }

    /**
     * 悬浮入口：贴边的小圆钮，可上下左右拖动（松手吸附到最近的左/右边），位置记在 prefs。
     * 单击 = 打开设置面板；长按 = 亮/暗快速切换。
     */
    class FloatingEntry(
        private val a: Activity,
        private val parent: FrameLayout,
        private val onTap: () -> Unit,
        private val onLongPress: () -> Unit,
    ) {
        private val size = a.dp(44)
        private val margin = a.dp(6)
        private val prefs = a.getSharedPreferences("floating_entry", Activity.MODE_PRIVATE)
        private val icon = TextView(a).apply {
            text = "⚙"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        private val dotView = View(a)
        val view = FrameLayout(a).apply {
            addView(icon, FrameLayout.LayoutParams(size, size))
            addView(dotView, FrameLayout.LayoutParams(a.dp(10), a.dp(10), Gravity.TOP or Gravity.END).apply {
                topMargin = a.dp(3); rightMargin = a.dp(3)
            })
            elevation = a.dp(6).toFloat()
            alpha = 0.82f
        }

        init {
            parent.addView(view, FrameLayout.LayoutParams(size, size))
            dotView.visibility = View.GONE
            // 读屏/键盘用户走标准点击、长按动作（触摸由下面的拖动处理接管，两者互不影响）
            view.contentDescription = "设置"
            view.isFocusable = true
            view.setOnClickListener { onTap() }
            view.setOnLongClickListener { onLongPress(); true }
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK, "打开设置"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, "切换亮色/暗色"))
                }
            }
            attachDrag()
            parent.post { restorePosition() }
            parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) restorePosition()
            }
        }

        fun applyPalette(p: Palette) {
            icon.setTextColor(p.text)
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.bg)
                setStroke(a.dp(1), p.divider)
            }
            dotView.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.danger) }
        }

        fun setDot(on: Boolean) {
            dotView.visibility = if (on) View.VISIBLE else View.GONE
            view.contentDescription = if (on) "设置，有新版本" else "设置"
        }

        private fun restorePosition() {
            val w = parent.width; val h = parent.height
            if (w == 0 || h == 0) return
            val right = prefs.getBoolean("right", true)
            val yFrac = prefs.getFloat("y", 0.42f)
            view.x = if (right) (w - size - margin).toFloat() else margin.toFloat()
            view.y = (yFrac * h).coerceIn(margin.toFloat(), (h - size - margin).toFloat())
        }

                private fun attachDrag() {
            val slop = ViewConfiguration.get(a).scaledTouchSlop
            val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
            var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
            var dragging = false; var longFired = false
            val longPress = Runnable {
                if (!dragging) {
                    longFired = true
                    view.performLongClick() // 走 OnLongClickListener，系统顺带给震动反馈
                }
            }
            view.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = v.x; startY = v.y
                        dragging = false; longFired = false
                        v.alpha = 1f
                        v.postDelayed(longPress, longPressMs)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            v.removeCallbacks(longPress)
                        }
                        if (dragging) {
                            v.x = (startX + dx).coerceIn(0f, (parent.width - size).toFloat())
                            v.y = (startY + dy).coerceIn(margin.toFloat(), (parent.height - size - margin).toFloat())
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPress)
                        v.alpha = 0.82f
                        if (dragging) {
                            val right = v.x + size / 2 > parent.width / 2
                            val targetX = if (right) (parent.width - size - margin).toFloat() else margin.toFloat()
                            v.animate().x(targetX).setDuration(160).start()
                            prefs.edit()
                                .putBoolean("right", right)
                                .putFloat("y", v.y / parent.height)
                                .apply()
                        } else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                    }
                }
                true
            }
        }
    }
}

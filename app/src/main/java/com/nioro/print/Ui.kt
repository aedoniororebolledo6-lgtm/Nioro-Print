package com.nioro.print

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Utilidades de interfaz: estilo "liquid glass" oscuro. */
object Ui {
    val TEXT: Int = Color.parseColor("#F2F6FF")
    val MUTED: Int = Color.parseColor("#9FB0D0")
    val ACCENT: Int = Color.parseColor("#7CC8FF")
    val OK: Int = Color.parseColor("#6EE7A8")
    val BAD: Int = Color.parseColor("#FF7A8A")
    val WARN: Int = Color.parseColor("#FFD27A")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun bgGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#12224A"), Color.parseColor("#070C1C"), Color.parseColor("#050812"))
    )

    fun glass(ctx: Context, radius: Int = 20, fill: Int = 0x1AFFFFFF, stroke: Int = 0x38FFFFFF): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(ctx, radius).toFloat()
        g.setColor(fill)
        g.setStroke(dp(ctx, 1), stroke)
        return g
    }

    fun primaryBg(ctx: Context): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#C8469BFF"), Color.parseColor("#C88B6BFF"))
        )
        g.cornerRadius = dp(ctx, 16).toFloat()
        g.setStroke(dp(ctx, 1), 0x77FFFFFF)
        return g
    }

    fun lp(ctx: Context, w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
        return p
    }

    fun label(ctx: Context, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(TEXT)
        b.textSize = 14f
        b.stateListAnimator = null
        b.background = if (primary) primaryBg(ctx) else glass(ctx, 16, 0x24FFFFFF, 0x4DFFFFFF)
        b.setPadding(dp(14, ctx), dp(8, ctx), dp(14, ctx), dp(8, ctx))
        b.minHeight = 0
        b.minimumHeight = dp(ctx, 46)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun dp(v: Int, ctx: Context): Int = dp(ctx, v)

    fun edit(ctx: Context, hint: String, multiline: Boolean = false, text: String = ""): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(text)
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.textSize = 15f
        e.background = glass(ctx, 14, 0x14FFFFFF, 0x33FFFFFF)
        e.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        if (multiline) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            e.minLines = 4
            e.gravity = Gravity.TOP or Gravity.START
        }
        return e
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = glass(ctx, 24)
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        return c
    }

    fun row(ctx: Context): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    /** Página desplazable; devuelve el ScrollView y la columna interior. */
    fun page(ctx: Context): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 18))
        sv.addView(col, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        return Pair(sv, col)
    }

    fun space(ctx: Context, h: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
        return v
    }
}

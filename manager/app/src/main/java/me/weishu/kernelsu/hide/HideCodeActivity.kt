package me.weishu.kernelsu.hide

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.R

/**
 * 「隐藏面具」设置页。
 *
 * 故意用最朴素的原生控件：这个页面必须**在图标被隐藏之后还能打开**，
 * 不依赖 Compose 主题 / 导航栈，少一层出错的机会。
 */
class HideCodeActivity : Activity() {

    private lateinit var codeInput: EditText
    private lateinit var hideSwitch: Switch
    private lateinit var hint: TextView

    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (dp * 22).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_title)
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF15151C.toInt())
            }
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_intro)
                textSize = 13f
                setTextColor(0xFF6B6C7C.toInt())
                setPadding(0, (dp * 8).toInt(), 0, (dp * 18).toInt())
                setLineSpacing(0f, 1.45f)
            }
        )

        root.addView(label(getString(R.string.hide_code_label)))

        codeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(HidePrefs.MAX_LEN))
            setText(HidePrefs.code(this@HideCodeActivity))
            setSelection(text.length)
            textSize = 17f
            setSingleLine()
            setPadding(0, (dp * 4).toInt(), 0, (dp * 4).toInt())
        }
        root.addView(codeInput)

        hint = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF5B4BD6.toInt())
            setPadding(0, (dp * 6).toInt(), 0, (dp * 18).toInt())
        }
        root.addView(hint)

        hideSwitch = Switch(this).apply {
            text = getString(R.string.hide_icon_label)
            textSize = 15f
            isChecked = HidePrefs.isIconHidden(this@HideCodeActivity)
            setPadding(0, (dp * 10).toInt(), 0, (dp * 10).toInt())
        }
        root.addView(hideSwitch)

        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_icon_summary)
                textSize = 12.5f
                setTextColor(0xFF8A8B99.toInt())
                setLineSpacing(0f, 1.4f)
                setPadding(0, 0, 0, (dp * 24).toInt())
            }
        )

        root.addView(
            primaryButton(getString(R.string.hide_save)) { save() }
        )
        root.addView(
            ghostButton(getString(R.string.hide_test)) { openDialer() }
        )

        setContentView(ScrollView(this).apply { addView(root) })

        refreshHint()
        codeInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = refreshHint()
        })
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(0xFF8A8B99.toInt())
        setPadding(0, (dp * 6).toInt(), 0, 0)
    }

    private fun refreshHint() {
        val code = codeInput.text?.toString()?.trim().orEmpty()
        hint.text = if (HidePrefs.isCodeValid(code)) {
            "拨号盘输入  *#*#$code#*#*  即可打开"
        } else {
            getString(R.string.hide_bad_code)
        }
    }

    private fun save() {
        val code = codeInput.text?.toString()?.trim().orEmpty()
        if (!HidePrefs.isCodeValid(code)) {
            toast(getString(R.string.hide_bad_code))
            return
        }
        HidePrefs.setCode(this, code)
        val hidden = hideSwitch.isChecked
        val ok = HidePrefs.setIconHidden(this, hidden)
        if (!ok) {
            toast(getString(R.string.hide_icon_failed))
            return
        }
        toast(if (hidden) getString(R.string.hide_saved_hidden) else getString(R.string.hide_saved_shown))
        refreshHint()
    }

    private fun openDialer() {
        val code = codeInput.text?.toString()?.trim().orEmpty()
        if (!HidePrefs.isCodeValid(code)) {
            toast(getString(R.string.hide_bad_code))
            return
        }
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#*#$code#*#*")))
        runCatching { startActivity(dial) }
            .onFailure { toast(getString(R.string.hide_no_dialer)) }
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp * 12
            setColor(0xFF5B4BD6.toInt())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * 46).toInt()
        )
    }

    private fun ghostButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF5B4BD6.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp * 12
            setColor(0xFFF1EFFC.toInt())
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * 44).toInt()
        ).also { it.topMargin = (dp * 10).toInt() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

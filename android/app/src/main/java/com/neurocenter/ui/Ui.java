package com.neurocenter.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.neurocenter.core.ThemeManager;
import com.neurocenter.core.ThemeManager.Palette;

/**
 * Utilidades de interfaz que aplican el tema activo a vistas creadas por código.
 * Las usan el host y las apps integradas; las apps externas pueden crear las suyas
 * con NeuroMobileContext.themeColor().
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable rounded(int fill, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    /** Fondo redondeado con efecto de pulsación acotado a su forma. */
    public static Drawable ripple(int fill, int rippleColor, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable content = rounded(fill, radiusPx, strokeColor, strokePx);
        GradientDrawable mask = rounded(Color.WHITE, radiusPx, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static TextView text(Context context, CharSequence value, float sp, boolean bold, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    /** Botón normal del tema (fondo «button», texto «buttonText»). */
    public static Button button(Context context, CharSequence label, Palette p) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{p.muted, p.buttonText}));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9));
        b.setBackground(ripple(p.button, withAlpha(p.text, 0x2A), dp(context, 10), p.border, 1));
        return b;
    }

    /** Botón de acento (fondo «primary», texto blanco). */
    public static Button primaryButton(Context context, CharSequence label, Palette p) {
        Button b = button(context, label, p);
        b.setTextColor(Color.WHITE);
        b.setBackground(ripple(p.primary, withAlpha(Color.WHITE, 0x40), dp(context, 10), p.primary, 0));
        return b;
    }

    /** Botón de icono/texto cuadrado de la barra superior (≡, ⋮…). */
    public static TextView iconButton(Context context, String glyph, int color, CharSequence description) {
        TextView v = text(context, glyph, 24, false, color);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        v.setContentDescription(description);
        int size = dp(context, 48);
        v.setMinWidth(size);
        v.setMinHeight(size);
        v.setBackground(ripple(Color.TRANSPARENT, withAlpha(color, 0x30), dp(context, 24), 0, 0));
        return v;
    }

    public static void styleField(EditText field, Palette p) {
        Context c = field.getContext();
        field.setTextColor(p.inputText);
        field.setHintTextColor(p.muted);
        field.setBackground(rounded(p.input, dp(c, 8), p.border, 1));
        field.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
    }

    public static void styleCheckBox(CheckBox box, Palette p) {
        box.setTextColor(p.text);
        box.setButtonTintList(ColorStateList.valueOf(p.primary));
    }

    // ------------------------------------------------------------------ diálogos

    /** Constructor de diálogos con la variante clara u oscura que corresponde al tema. */
    public static AlertDialog.Builder dialog(Context context) {
        Palette p = ThemeManager.palette();
        int style = p.dialogDark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        return new AlertDialog.Builder(context, style);
    }

    /** Crea, muestra y aplica los colores del tema. */
    public static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        style(dialog);
        return dialog;
    }

    /** Aplica los colores del tema a un diálogo ya mostrado. */
    public static void style(AlertDialog dialog) {
        Palette p = ThemeManager.palette();
        Context c = dialog.getContext();

        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable shape = rounded(p.dialog, dp(c, 16), p.border, 1);
            window.setBackgroundDrawable(new InsetDrawable(shape, dp(c, 12)));
        }

        View message = dialog.findViewById(android.R.id.message);
        if (message instanceof TextView) ((TextView) message).setTextColor(p.text);

        int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
        View title = titleId != 0 ? dialog.findViewById(titleId) : null;
        if (title instanceof TextView) ((TextView) title).setTextColor(p.text);

        int[] buttons = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int which : buttons) {
            Button b = dialog.getButton(which);
            if (b != null) b.setTextColor(p.primary);
        }
    }
}

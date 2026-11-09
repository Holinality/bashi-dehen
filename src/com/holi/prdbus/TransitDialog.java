package com.holi.prdbus;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.*;

final class TransitDialog {
    static final class Builder {
        private final Context context;
        private CharSequence title, message, positive, negative;
        private CharSequence[] items;
        private DialogInterface.OnClickListener onPositive, onNegative, onItem;
        private boolean singleChoice;
        private boolean cancelable = true;
        private DialogInterface.OnDismissListener onDismiss;
        private int checked;

        Builder(Context context) { this.context = context; }
        Builder setTitle(CharSequence value) { title = value; return this; }
        Builder setMessage(CharSequence value) { message = value; return this; }
        Builder setCancelable(boolean value) { cancelable = value; return this; }
        Builder setOnDismissListener(DialogInterface.OnDismissListener action) { onDismiss = action; return this; }
        Builder setPositiveButton(CharSequence label, DialogInterface.OnClickListener action) {
            positive = label; onPositive = action; return this;
        }
        Builder setNegativeButton(CharSequence label, DialogInterface.OnClickListener action) {
            negative = label; onNegative = action; return this;
        }
        Builder setItems(CharSequence[] values, DialogInterface.OnClickListener action) {
            items = values; onItem = action; return this;
        }
        Builder setSingleChoiceItems(CharSequence[] values, int initial, DialogInterface.OnClickListener action) {
            singleChoice = true; checked = initial; return setItems(values, action);
        }

        Dialog show() {
            Dialog dialog = new Dialog(context);
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);
            if (onDismiss != null) dialog.setOnDismissListener(onDismiss);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(Ui.rounded(context, Ui.SURFACE, 24));
            int inset = Ui.dp(context, 20);
            panel.setPadding(inset, inset, inset, inset);
            TextView heading = label(title, 20, Ui.TEXT);
            heading.setTypeface(null, android.graphics.Typeface.BOLD);
            panel.addView(heading);

            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10));
            if (message != null) body.addView(label(message, 15, Ui.MUTED));
            if (items != null) {
                Button[] choices = new Button[items.length];
                for (int i = 0; i < items.length; i++) {
                    final int index = i;
                    Button button = Ui.button(context, items[i].toString(), Ui.LIGHT_BLUE, Ui.PRIMARY_DARK);
                    choices[i] = button;
                    button.setSelected(singleChoice && checked == i);
                    if (button.isSelected()) button.setText("✓  " + items[i]);
                    LinearLayout.LayoutParams choice = new LinearLayout.LayoutParams(-1, -2);
                    if (i > 0) choice.topMargin = Ui.dp(context, 8);
                    body.addView(button, choice);
                    button.setOnClickListener(v -> {
                        if (singleChoice) {
                            for (int j = 0; j < choices.length; j++) {
                                choices[j].setSelected(j == index);
                                choices[j].setText(j == index ? "✓  " + items[j] : items[j]);
                            }
                        } else dialog.dismiss();
                        onItem.onClick(dialog, index);
                    });
                }
            }
            ScrollView scroll = new ScrollView(context);
            scroll.addView(body);
            int bodyHeight = message != null && message.length() > 350
                    ? Math.min(Ui.dp(context,420),Math.round(context.getResources().getDisplayMetrics().heightPixels*.48f)) : -2;
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, bodyHeight, 1));
            LinearLayout actions = new LinearLayout(context);
            actions.setGravity(android.view.Gravity.END);
            actions.setPadding(0, Ui.dp(context, 8), 0, 0);
            addAction(actions, dialog, negative, onNegative, false);
            addAction(actions, dialog, positive, onPositive, true);
            panel.addView(actions);
            dialog.setContentView(panel);
            dialog.show();
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = context.getResources().getDisplayMetrics().widthPixels - 2 * inset;
                window.setLayout(Math.min(width, Ui.dp(context, 400)), -2);
            }
            return dialog;
        }

        private TextView label(CharSequence text, int size, int color) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(size);
            view.setTextColor(color);
            return view;
        }

        private void addAction(LinearLayout row, Dialog dialog, CharSequence label,
                               DialogInterface.OnClickListener action, boolean primary) {
            if (label == null) return;
            Button button = Ui.button(context, label.toString(), primary ? Ui.PRIMARY : Ui.RAISED,
                    primary ? Ui.ON_PRIMARY : Ui.TEXT);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginStart(Ui.dp(context, 8));
            row.addView(button, params);
            button.setOnClickListener(v -> {
                dialog.dismiss();
                if (action != null) action.onClick(dialog, primary
                        ? DialogInterface.BUTTON_POSITIVE : DialogInterface.BUTTON_NEGATIVE);
            });
        }
    }
}

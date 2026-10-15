package com.pinfileshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(247, 249, 252));
        getWindow().setNavigationBarColor(Color.rgb(247, 249, 252));
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
        showHome();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(22), dp(24), dp(24));
        layout.setBackgroundColor(Color.rgb(247, 249, 252));
        return layout;
    }

    private void addGap(LinearLayout layout, int height) {
        View gap = new View(this);
        layout.addView(gap, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(foreground);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        button.setMinHeight(dp(56));
        return button;
    }

    private void showHome() {
        LinearLayout root = column();
        TextView brand = text("PIN FILE SHARE", 12, Color.rgb(74, 105, 145));
        brand.setLetterSpacing(0.12f);
        root.addView(brand);
        addGap(root, 28);

        TextView title = text("Send files.\nSimply.", 34, Color.rgb(22, 34, 53));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        addGap(root, 10);
        root.addView(text("Share files between phones with a temporary PIN.", 16,
            Color.rgb(92, 105, 123)));
        addGap(root, 30);

        LinearLayout sendCard = card();
        sendCard.addView(text("SEND A FILE", 12, Color.rgb(74, 105, 145)));
        addGap(sendCard, 8);
        sendCard.addView(text("Choose a file to share", 20, Color.rgb(22, 34, 53)));
        addGap(sendCard, 6);
        sendCard.addView(text("Pick a document, photo, or other file.", 14,
            Color.rgb(92, 105, 123)));
        addGap(sendCard, 18);
        Button send = button("Choose file", Color.rgb(36, 99, 235), Color.WHITE);
        send.setOnClickListener(v -> chooseFile());
        sendCard.addView(send);
        root.addView(sendCard);

        addGap(root, 16);
        LinearLayout receiveCard = card();
        receiveCard.addView(text("RECEIVE A FILE", 12, Color.rgb(74, 105, 145)));
        addGap(receiveCard, 8);
        receiveCard.addView(text("Have a PIN?", 20, Color.rgb(22, 34, 53)));
        addGap(receiveCard, 6);
        receiveCard.addView(text("Enter the sender's temporary PIN to connect.", 14,
            Color.rgb(92, 105, 123)));
        addGap(receiveCard, 18);
        Button receive = button("Enter PIN", Color.rgb(226, 234, 246), Color.rgb(22, 52, 92));
        receive.setOnClickListener(v -> showPinNotice());
        receiveCard.addView(receive);
        root.addView(receiveCard);

        addGap(root, 22);
        TextView note = text("EARLY PROTOTYPE  •  TRANSFER NOT ENABLED YET", 11,
            Color.rgb(130, 140, 153));
        note.setGravity(Gravity.CENTER);
        root.addView(note);
        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(Color.WHITE);
        return card;
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Toast.makeText(this, "File selected. PIN transfer will be added next.",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showPinNotice() {
        new AlertDialog.Builder(this)
            .setTitle("Receive with PIN")
            .setMessage("PIN-based transfer is the next development step. This prototype only includes the initial interface.")
            .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
            .show();
    }
}

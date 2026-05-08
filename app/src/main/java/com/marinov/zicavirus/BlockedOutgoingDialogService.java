package com.marinov.zicavirus;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

public class BlockedOutgoingDialogService extends Service {

    private WindowManager windowManager;
    private View overlayView;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int penaltyMinutes = intent.getIntExtra("penalty_minutes", 0);
        showOverlayDialog(penaltyMinutes);
        return START_NOT_STICKY;
    }

    private void showOverlayDialog(int penaltyMinutes) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Inflar com o tema do app (Material 3) para resolver os atributos
        Context themedContext = new ContextThemeWrapper(this, R.style.Theme_ZikaVírus);
        LayoutInflater inflater = LayoutInflater.from(themedContext);
        overlayView = inflater.inflate(R.layout.dialog_blocked_outgoing, null);

        // Texto da mensagem
        MaterialTextView messageView = overlayView.findViewById(R.id.tvMessage);
        String timeStr;
        if (penaltyMinutes >= 60 && penaltyMinutes % 60 == 0) {
            int hours = penaltyMinutes / 60;
            timeStr = hours + " " + (hours == 1 ? "hora" : "horas");
        } else {
            timeStr = penaltyMinutes + " " + (penaltyMinutes == 1 ? "minuto" : "minutos");
        }
        String message = getString(R.string.blocked_outgoing_message, timeStr);
        messageView.setText(message);

        // Botão "Entendi"
        MaterialButton btnEntendi = overlayView.findViewById(R.id.btnEntendi);
        btnEntendi.setOnClickListener(v -> {
            removeOverlay();
            stopSelf();
        });

        // Parâmetros da janela flutuante
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.x = 0;
        params.y = 0;

        windowManager.addView(overlayView, params);
    }

    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }
}
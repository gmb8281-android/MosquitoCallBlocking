package com.marinov.zicavirus;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Receptor de administrador de dispositivo.
 * Impede a desinstalação do app enquanto estiver ativo como admin.
 */
public class DeviceAdminReceiverImpl extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Zika Vírus: proteção de admin ativada.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Zika Vírus: proteção de admin desativada.", Toast.LENGTH_SHORT).show();
    }
}
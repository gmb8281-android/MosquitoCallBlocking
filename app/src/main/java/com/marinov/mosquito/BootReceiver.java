package com.marinov.mosquito;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Recebe o broadcast de boot concluído para garantir que o app
 * esteja pronto para processar chamadas logo após reinicialização.
 * O CallScreeningService é gerenciado pelo sistema Android e não
 * precisa ser iniciado explicitamente; este receiver garante que
 * o processo do app seja acordado e o banco de dados inicializado.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            DatabaseHelper db = new DatabaseHelper(context);
            db.cleanOldLogs();
            db.close();
        }
    }
}
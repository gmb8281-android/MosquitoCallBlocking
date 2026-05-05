package com.marinov.zicavirus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;

/**
 * Receiver de backup para log de chamadas recebidas.
 * O CallScreeningServiceImpl é o caminho principal (loga E bloqueia).
 * Este receiver serve como fallback para garantir o log quando o app
 * ainda não foi definido como serviço de triagem padrão.
 * Detecta somente CALL_STATE_RINGING para evitar entradas duplicadas,
 * pois o CallScreeningService também insere o log ao receber a chamada.
 */
public class PhoneStateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (!TelephonyManager.EXTRA_STATE_RINGING.equals(state)) return;

        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (incomingNumber == null || incomingNumber.isEmpty()) return;

        // Só registra se NÃO houver regra para esse número
        // (se houver, o CallScreeningService já registrou)
        ContactRule rule = RulesManager.findRuleForNumber(context, incomingNumber);
        if (rule != null) return; // evita duplicata

        // Registra a chamada mesmo sem regra (pode ser adicionada depois)
        String contactId = getContactId(context, incomingNumber);
        DatabaseHelper db = new DatabaseHelper(context);
        db.insertCallLog(contactId, incomingNumber, System.currentTimeMillis());
        db.cleanOldLogs();
        db.close();
    }

    private String getContactId(Context ctx, String number) {
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number));
            Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{ ContactsContract.PhoneLookup.CONTACT_ID },
                    null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    String id = c.getString(0);
                    c.close();
                    return id;
                }
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
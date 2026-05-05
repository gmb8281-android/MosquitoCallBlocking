package com.marinov.zicavirus;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.telecom.TelecomManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Calendar;

@RequiresApi(api = Build.VERSION_CODES.N)
public class CallScreeningServiceImpl extends CallScreeningService {

    private static final long TWO_DAYS_MS = 2L * 24 * 60 * 60 * 1000;

    @Override
    public void onScreenCall(@NonNull Call.Details callDetails) {
        // Chamadas efetuadas pelo usuário nunca devem ser bloqueadas.
        // No API 29+ o serviço de triagem também é invocado para outgoing calls;
        // abaixo do 29 o sistema só chama onScreenCall para incoming, sem risco.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                callDetails.getCallDirection() == Call.Details.DIRECTION_OUTGOING) {
            respondToCall(callDetails, new CallResponse.Builder().build());
            return;
        }

        String phoneNumber = extractNumber(callDetails);
        boolean block = false;

        if (phoneNumber != null) {
            block = processCall(getApplicationContext(), phoneNumber);
        }

        CallResponse response = new CallResponse.Builder().build();
        respondToCall(callDetails, response);

        if (block) {
            final Context appCtx = getApplicationContext();
            final String numToDelete = phoneNumber;

            // Thread 1 — derruba a ligação.
            // Pequeno delay para o telecom processar o respondToCall antes do endCall.
            new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                endCallNow(appCtx);
            }).start();

            // Thread 2 — apaga do histórico do discador (somente a entrada bloqueada).
            // Roda em paralelo com a Thread 1; usa loop de tentativas porque o
            // sistema grava a entrada no call log em tempo variável após encerrar
            // a chamada (especialmente na primeira ocorrência, pode demorar mais).
            // Tentativas: aguarda 800ms, depois tenta até 6x em intervalos de 600ms
            // (janela total ~4.4s), parando assim que a entrada for encontrada e deletada.
            new Thread(() -> {
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                for (int attempt = 0; attempt < 6; attempt++) {
                    if (deleteLastCallLogEntry(appCtx, numToDelete)) break;
                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                }
            }).start();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lógica principal
    //
    // Fluxo por chamada recebida:
    //
    //  1. Sem regra cadastrada → loga e libera.
    //
    //  2. Penalidade ATIVA (blockedUntil > agora)
    //     → bloqueia SEM logar.
    //       Não logar é essencial: logar durante a pena inflaria o contador e
    //       faria a pena se renovar automaticamente ao expirar.
    //
    //  3. Penalidade EXPIRADA (blockedUntil estava > 0, mas agora <= agora)
    //     → zera blockedUntil, apaga logs do contato.
    //       O contador recomeça do zero após o cumprimento da pena.
    //
    //  4. Fora da janela de horário (se configurada)
    //     → bloqueia SEM logar. Não conta para o limite.
    //
    //  5. Loga esta chamada e conta quantas houve nos últimos 2 dias.
    //
    //  6. count > maxCalls → aplica penalidade.
    //     Semântica: o spinner "N" significa "permite N ligações, bloqueia
    //     a (N+1)ª". O uso de > (e não >=) garante que a Nª chamada ainda
    //     passa — >= bloqueava a própria Nª, tornando o valor 1 inacessível.
    // ─────────────────────────────────────────────────────────────────────────

    private boolean processCall(Context ctx, String phoneNumber) {
        long now = System.currentTimeMillis();

        // Resolve o contactId pelo número recebido (pode ser null p/ desconhecidos)
        String contactId = getContactId(ctx, phoneNumber);

        // Busca a regra pelo contactId; fallback por número para desconhecidos
        ContactRule rule = (contactId != null)
                ? RulesManager.findRuleForContact(ctx, contactId)
                : RulesManager.findRuleForNumber(ctx, phoneNumber);

        // ── 1. Sem regra: loga e libera
        if (rule == null) {
            logCall(ctx, contactId, phoneNumber, now);
            return false;
        }

        // ── 2. Penalidade ATIVA: bloqueia sem logar
        if (rule.blockedUntil > now) {
            return true;
        }

        // ── 3. Penalidade EXPIRADA: reseta estado e limpa contador
        if (rule.blockedUntil > 0) {
            rule.blockedUntil = 0;
            RulesManager.updateRule(ctx, rule);
            DatabaseHelper dbReset = new DatabaseHelper(ctx);
            dbReset.deleteCallsForContact(rule.contactId);
            dbReset.close();
        }

        // ── 4. Fora da janela de horário: bloqueia sem logar
        if (rule.timeWindowEnabled && !isInTimeWindow(rule)) {
            return true;
        }

        // ── 5. Loga esta chamada
        //    Se o contactId do lookup falhou mas temos a regra, usa o ID da regra
        String cid = (contactId != null) ? contactId : rule.contactId;
        logCall(ctx, cid, phoneNumber, now);

        DatabaseHelper db = new DatabaseHelper(ctx);
        int callCount = db.countCallsForContact(rule.contactId, now - TWO_DAYS_MS);
        db.close();

        // ── 6. count > maxCalls → (N+1)ª ligação aciona a penalidade
        if (callCount > rule.maxCalls) {
            rule.blockedUntil = now + (long) rule.penaltyMinutes * 60 * 1000;
            RulesManager.updateRule(ctx, rule);
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers internos
    // ─────────────────────────────────────────────────────────────────────────

    /** Insere entrada no banco interno e apaga logs com mais de 2 dias. */
    private void logCall(Context ctx, String contactId, String phoneNumber, long timestamp) {
        DatabaseHelper db = new DatabaseHelper(ctx);
        db.insertCallLog(contactId, phoneNumber, timestamp);
        db.cleanOldLogs();
        db.close();
    }

    private String extractNumber(Call.Details details) {
        if (details == null || details.getHandle() == null) return null;
        String raw = details.getHandle().getSchemeSpecificPart();
        return (raw != null && !raw.isEmpty()) ? raw : null;
    }

    private String getContactId(Context ctx, String number) {
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{ ContactsContract.PhoneLookup.CONTACT_ID },
                    null, null, null);
            if (c != null) {
                if (c.moveToFirst()) { String id = c.getString(0); c.close(); return id; }
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Retorna true se o horário atual está dentro da janela permitida.
     * Suporta janelas que cruzam a meia-noite (ex: 22:00–06:00).
     */
    private boolean isInTimeWindow(ContactRule rule) {
        try {
            Calendar cal = Calendar.getInstance();
            int nowMin   = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            String[] s   = rule.timeWindowStart.split(":");
            String[] e   = rule.timeWindowEnd.split(":");
            int startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
            int endMin   = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);
            if (startMin <= endMin) return nowMin >= startMin && nowMin <= endMin;
            return nowMin >= startMin || nowMin <= endMin; // cruza meia-noite
        } catch (Exception ex) {
            return true; // em caso de erro, não bloqueia
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exclusão do histórico do discador
    // ─────────────────────────────────────────────────────────────────────────

    /** Retorna true se a entrada foi encontrada e deletada, false se ainda não estava no log. */
    private boolean deleteLastCallLogEntry(Context ctx, String phoneNumber) {
        if (phoneNumber == null) return false;
        try {
            String normalized = phoneNumber.replaceAll("[^0-9+]", "");
            if (tryDeleteLatest(ctx, normalized)) return true;
            if (tryDeleteLatest(ctx, phoneNumber)) return true;
            if (normalized.length() >= 8)
                return tryDeleteLatestWithLike(ctx, normalized.substring(normalized.length() - 8));
        } catch (Exception e) { e.printStackTrace(); }
        return false;
    }

    /**
     * Deleta somente a entrada mais recente com aquele número que:
     *  - tenha DURATION = 0  → não foi atendida (bloqueada/perdida)
     *  - tenha DATE nos últimos 60s → evita apagar chamadas perdidas antigas
     * Chamadas atendidas (DURATION > 0) nunca são tocadas.
     */
    private boolean tryDeleteLatest(Context ctx, String number) {
        Cursor cursor = null;
        try {
            long since = System.currentTimeMillis() - 60_000;
            cursor = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{ CallLog.Calls._ID },
                    CallLog.Calls.NUMBER   + " = ? AND " +
                            CallLog.Calls.DURATION + " = 0 AND " +
                            CallLog.Calls.DATE     + " > ?",
                    new String[]{ number, String.valueOf(since) },
                    CallLog.Calls.DATE + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ctx.getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                        CallLog.Calls._ID + " = ?", new String[]{ String.valueOf(id) }) > 0;
            }
        } catch (Exception e) { e.printStackTrace(); }
        finally { if (cursor != null) cursor.close(); }
        return false;
    }

    private boolean tryDeleteLatestWithLike(Context ctx, String lastDigits) {
        Cursor cursor = null;
        try {
            long since = System.currentTimeMillis() - 60_000;
            cursor = ctx.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{ CallLog.Calls._ID },
                    CallLog.Calls.NUMBER   + " LIKE ? AND " +
                            CallLog.Calls.DURATION + " = 0 AND " +
                            CallLog.Calls.DATE     + " > ?",
                    new String[]{ "%" + lastDigits, String.valueOf(since) },
                    CallLog.Calls.DATE + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ctx.getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                        CallLog.Calls._ID + " = ?", new String[]{ String.valueOf(id) }) > 0;
            }
        } catch (Exception e) { e.printStackTrace(); }
        finally { if (cursor != null) cursor.close(); }
        return false;
    }

    private void endCallNow(Context ctx) {
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { tm.endCall(); } catch (SecurityException e) { e.printStackTrace(); }
        }
    }
}
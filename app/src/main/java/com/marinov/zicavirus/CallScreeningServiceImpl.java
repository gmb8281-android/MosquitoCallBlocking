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

import java.util.Calendar;

public class CallScreeningServiceImpl extends CallScreeningService {

    @Override
    public void onScreenCall(@NonNull Call.Details callDetails) {
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
            new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                endCallNow(appCtx);
            }).start();
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
    // Lógica principal (mesma documentação, agora com janela de reset dinâmica)
    // ─────────────────────────────────────────────────────────────────────────

    private boolean processCall(Context ctx, String phoneNumber) {
        long now = System.currentTimeMillis();

        String contactId = getContactId(ctx, phoneNumber);
        ContactRule rule = (contactId != null)
                ? RulesManager.findRuleForContact(ctx, contactId)
                : RulesManager.findRuleForNumber(ctx, phoneNumber);

        // 1. Sem regra: loga e libera
        if (rule == null) {
            logCall(ctx, contactId, phoneNumber, now);
            return false;
        }

        // 2. Penalidade ATIVA: bloqueia sem logar
        if (rule.blockedUntil > now) {
            return true;
        }

        // 3. Penalidade EXPIRADA: reseta estado e limpa contador
        if (rule.blockedUntil > 0) {
            rule.blockedUntil = 0;
            RulesManager.updateRule(ctx, rule);
            DatabaseHelper dbReset = new DatabaseHelper(ctx);
            dbReset.deleteCallsForContact(rule.contactId);
            dbReset.close();
        }

        // 4. Fora da janela de horário: bloqueia sem logar
        if (rule.timeWindowEnabled && !isInTimeWindow(rule)) {
            return true;
        }

        // 5. Loga esta chamada
        String cid = (contactId != null) ? contactId : rule.contactId;
        logCall(ctx, cid, phoneNumber, now);

        // Janela de reset dinâmica (em minutos)
        long resetWindowMs = (long) rule.resetWindowMinutes * 60 * 1000;

        DatabaseHelper db = new DatabaseHelper(ctx);
        int callCount = db.countCallsForContact(rule.contactId, now - resetWindowMs);
        db.close();

        // 6. count > maxCalls → aplica penalidade
        if (callCount > rule.maxCalls) {
            rule.blockedUntil = now + (long) rule.penaltyMinutes * 60 * 1000;
            RulesManager.updateRule(ctx, rule);
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers internos (idênticos ao original)
    // ─────────────────────────────────────────────────────────────────────────

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

    private boolean isInTimeWindow(ContactRule rule) {
        try {
            Calendar cal = Calendar.getInstance();
            int nowMin   = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            String[] s   = rule.timeWindowStart.split(":");
            String[] e   = rule.timeWindowEnd.split(":");
            int startMin = Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
            int endMin   = Integer.parseInt(e[0]) * 60 + Integer.parseInt(e[1]);
            if (startMin <= endMin) return nowMin >= startMin && nowMin <= endMin;
            return nowMin >= startMin || nowMin <= endMin;
        } catch (Exception ex) {
            return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exclusão do histórico do discador
    // ─────────────────────────────────────────────────────────────────────────

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
        if (tm != null) {
            try { tm.endCall(); } catch (SecurityException e) { e.printStackTrace(); }
        }
    }
}
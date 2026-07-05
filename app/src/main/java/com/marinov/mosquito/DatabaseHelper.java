package com.marinov.mosquito;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "zicavirus.db";
    private static final int    DB_VERSION = 2;

    private static final String TABLE      = "call_logs";
    private static final String COL_ID     = "id";
    private static final String COL_CID    = "contact_id";
    private static final String COL_NUM    = "phone_number";
    private static final String COL_TIME   = "timestamp";
    private static final String COL_DIR    = "direction";

    // Limpeza geral: remove entradas com mais de 7 dias (não afeta a janela de reset por regra)
    private static final long MAX_LOG_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    public DatabaseHelper(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID   + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_CID  + " TEXT, " +
                COL_NUM  + " TEXT NOT NULL, " +
                COL_TIME + " INTEGER NOT NULL, " +
                COL_DIR  + " TEXT NOT NULL DEFAULT 'incoming'" +
                ")");
        db.execSQL("CREATE INDEX idx_contact_time ON " + TABLE + "(" + COL_CID + "," + COL_TIME + ")");
        db.execSQL("CREATE INDEX idx_contact_dir ON " + TABLE + "(" + COL_CID + "," + COL_DIR + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_DIR + " TEXT NOT NULL DEFAULT 'incoming'");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_contact_dir ON " + TABLE + "(" + COL_CID + "," + COL_DIR + ")");
        }
    }

    public void insertCallLog(String contactId, String phoneNumber, long timestamp, String direction) {
        ContentValues cv = new ContentValues();
        cv.put(COL_CID,  contactId);
        cv.put(COL_NUM,  phoneNumber);
        cv.put(COL_TIME, timestamp);
        cv.put(COL_DIR,  direction);
        getWritableDatabase().insert(TABLE, null, cv);
    }

    public int countCallsForContact(String contactId, long since, String direction) {
        if (contactId == null) return 0;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE +
                        " WHERE " + COL_CID + " = ? AND " + COL_TIME + " >= ? AND " + COL_DIR + " = ?",
                new String[]{ contactId, String.valueOf(since), direction });
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public void cleanOldLogs() {
        long cutoff = System.currentTimeMillis() - MAX_LOG_AGE_MS;
        getWritableDatabase().delete(TABLE, COL_TIME + " < ?",
                new String[]{ String.valueOf(cutoff) });
    }

    /**
     * Remove TODAS as chamadas de um determinado contato em uma direção específica.
     * Usado ao aplicar penalidade para zerar a contagem.
     */
    public void deleteCallsForContact(String contactId, String direction) {
        if (contactId == null) return;
        getWritableDatabase().delete(TABLE,
                COL_CID + " = ? AND " + COL_DIR + " = ?",
                new String[]{ contactId, direction });
    }
}
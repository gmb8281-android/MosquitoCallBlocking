package com.marinov.zicavirus;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME    = "zicavirus.db";
    private static final int    DB_VERSION = 1;

    private static final String TABLE      = "call_logs";
    private static final String COL_ID     = "id";
    private static final String COL_CID    = "contact_id";
    private static final String COL_NUM    = "phone_number";
    private static final String COL_TIME   = "timestamp";

    private static final long TWO_DAYS_MS  = 2L * 24 * 60 * 60 * 1000;

    public DatabaseHelper(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID   + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_CID  + " TEXT, " +
                COL_NUM  + " TEXT NOT NULL, " +
                COL_TIME + " INTEGER NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX idx_contact_time ON " + TABLE + "(" + COL_CID + "," + COL_TIME + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void insertCallLog(String contactId, String phoneNumber, long timestamp) {
        ContentValues cv = new ContentValues();
        cv.put(COL_CID,  contactId);
        cv.put(COL_NUM,  phoneNumber);
        cv.put(COL_TIME, timestamp);
        getWritableDatabase().insert(TABLE, null, cv);
    }

    public int countCallsForContact(String contactId, long since) {
        if (contactId == null) return 0;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE +
                        " WHERE " + COL_CID + " = ? AND " + COL_TIME + " >= ?",
                new String[]{ contactId, String.valueOf(since) });
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public void cleanOldLogs() {
        long cutoff = System.currentTimeMillis() - TWO_DAYS_MS;
        getWritableDatabase().delete(TABLE, COL_TIME + " < ?",
                new String[]{ String.valueOf(cutoff) });
    }

    /**
     * Remove TODAS as chamadas de um determinado contato do banco interno.
     * Usado ao aplicar penalidade para zerar a contagem.
     */
    public void deleteCallsForContact(String contactId) {
        if (contactId == null) return;
        getWritableDatabase().delete(TABLE, COL_CID + " = ?",
                new String[]{ contactId });
    }
}
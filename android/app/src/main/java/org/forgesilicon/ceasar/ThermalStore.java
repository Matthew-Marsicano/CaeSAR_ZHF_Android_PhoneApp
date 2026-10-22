package org.forgesilicon.ceasar;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.LocalDate;
import java.util.*;

/** Local minute aggregates and logged period starts. Raw 1 Hz samples are not persisted. */
public final class ThermalStore extends SQLiteOpenHelper {
    public ThermalStore(Context context) { super(context, "ceasar-thermal.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE minute (minute INTEGER PRIMARY KEY, core REAL NOT NULL, top REAL NOT NULL, top_n INTEGER NOT NULL, heater REAL NOT NULL, heater_n INTEGER NOT NULL, n INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE period (day TEXT PRIMARY KEY)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unexpected database upgrade"); }

    /** Sums for one minute; merged with anything already stored for that minute. */
    public static final class Sums {
        public final long minute; public double core, top, heater; public int n, topN, heaterN;
        public Sums(long minute) { this.minute = minute; }
        public void add(ThermalSample s) {
            core += s.core; n++;
            if (!Double.isNaN(s.top)) { top += s.top; topN++; }
            if (!Double.isNaN(s.heaterMw)) { heater += s.heaterMw; heaterN++; }
        }
    }
    public void record(Collection<Sums> minutes) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (Sums m : minutes) {
                db.execSQL("INSERT OR IGNORE INTO minute VALUES (?,0,0,0,0,0,0)", new Object[]{m.minute});
                db.execSQL("UPDATE minute SET core=core+?, top=top+?, top_n=top_n+?, heater=heater+?, heater_n=heater_n+?, n=n+? WHERE minute=?",
                        new Object[]{m.core, m.top, m.topN, m.heater, m.heaterN, m.n, m.minute});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public MinuteSeries load(long from, long to) {
        MinuteSeries s = new MinuteSeries(from, (int) ((to - from) / MinuteSeries.MINUTE) + 1);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT minute, core, n, top, top_n, heater, heater_n FROM minute WHERE minute>=? AND minute<? ORDER BY minute",
                new String[]{String.valueOf(s.start), String.valueOf(s.end())})) {
            while (c.moveToNext()) {
                int i = s.index(c.getLong(0));
                if (i < 0 || i >= s.size() || c.getInt(2) == 0) continue;
                s.core[i] = (float) (c.getDouble(1) / c.getInt(2));
                if (c.getInt(4) > 0) s.top[i] = (float) (c.getDouble(3) / c.getInt(4));
                if (c.getInt(6) > 0) s.heater[i] = (float) (c.getDouble(5) / c.getInt(6));
            }
        }
        return s;
    }
    public long minutes() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM minute", null)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }
    public List<LocalDate> periodStarts() {
        List<LocalDate> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day FROM period ORDER BY day", null)) {
            while (c.moveToNext()) out.add(LocalDate.parse(c.getString(0)));
        }
        return out;
    }
    public void logPeriod(LocalDate day) { getWritableDatabase().execSQL("INSERT OR IGNORE INTO period VALUES (?)", new Object[]{day.toString()}); }
    public void removePeriod(LocalDate day) { getWritableDatabase().execSQL("DELETE FROM period WHERE day=?", new Object[]{day.toString()}); }
    public void clear() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("minute", null, null); db.delete("period", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
}

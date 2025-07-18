package me.telegraphy.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import me.telegraphy.android.messenger.FileLog;

public class DatabaseManager {

    private static final String TAG = "DatabaseManager";
    private static volatile DatabaseManager instance;
    private SQLiteDatabase database;
    private DatabaseHelper dbHelper;

    private static final String DATABASE_NAME = "telegraphy.db";
    private static final int DATABASE_VERSION = 1;

    private DatabaseManager(Context context) {
        dbHelper = new DatabaseHelper(context);
        database = dbHelper.getWritableDatabase();
    }

    public static DatabaseManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager(context);
                }
            }
        }
        return instance;
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Create tables
            db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY, data TEXT)");
            db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, data TEXT)");
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, chatId INTEGER, data TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Upgrade database
            db.execSQL("DROP TABLE IF EXISTS users");
            db.execSQL("DROP TABLE IF EXISTS chats");
            db.execSQL("DROP TABLE IF EXISTS messages");
            onCreate(db);
        }
    }

    public void close() {
        dbHelper.close();
    }

    // User operations
    public void addUser(long id, String data) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("data", data);
        database.replace("users", null, values);
    }

    public String getUser(long id) {
        Cursor cursor = database.query("users", new String[]{"data"}, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            String data = cursor.getString(0);
            cursor.close();
            return data;
        }
        return null;
    }

    public void deleteUser(long id) {
        database.delete("users", "id = ?", new String[]{String.valueOf(id)});
    }

    // Chat operations
    public void addChat(long id, String data) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("data", data);
        database.replace("chats", null, values);
    }

    public String getChat(long id) {
        Cursor cursor = database.query("chats", new String[]{"data"}, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            String data = cursor.getString(0);
            cursor.close();
            return data;
        }
        return null;
    }

    public void deleteChat(long id) {
        database.delete("chats", "id = ?", new String[]{String.valueOf(id)});
    }

    // Message operations
    public void addMessage(long id, long chatId, String data) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("chatId", chatId);
        values.put("data", data);
        database.replace("messages", null, values);
    }

    public String getMessage(long id) {
        Cursor cursor = database.query("messages", new String[]{"data"}, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            String data = cursor.getString(0);
            cursor.close();
            return data;
        }
        return null;
    }

    public void deleteMessage(long id) {
        database.delete("messages", "id = ?", new String[]{String.valueOf(id)});
    }
}

package com.fxcalc.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class CalcDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "fxcalc.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "calculations"
        private const val COL_ID = "id"
        private const val COL_NAME = "name"
        private const val COL_DATA = "data"
        private const val COL_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_DATA TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun saveCalculation(name: String, data: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NAME, name)
            put(COL_DATA, data)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        db.insert(TABLE_NAME, null, values)
    }

    fun getAllCalculations(): String {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null, null, null, null, null,
            "$COL_TIMESTAMP DESC"
        )
        val arr = JSONArray()
        while (cursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id", cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)))
            obj.put("name", cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)))
            obj.put("data", cursor.getString(cursor.getColumnIndexOrThrow(COL_DATA)))
            obj.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)))
            arr.put(obj)
        }
        cursor.close()
        return arr.toString()
    }

    fun deleteCalculation(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun importCalculations(json: String): Int {
        val arr = JSONArray(json)
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val values = ContentValues().apply {
                    put(COL_NAME, obj.getString("name"))
                    put(COL_DATA, obj.getString("data"))
                    put(COL_TIMESTAMP, obj.getLong("timestamp"))
                }
                db.insert(TABLE_NAME, null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }
}

package com.runcode.app.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.runcode.app.domain.models.ColumnInfo
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.QueryResult
import com.runcode.app.domain.models.TableInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ProjectDatabaseManager {

    suspend fun discoverDatabases(project: Project): List<File> = withContext(Dispatchers.IO) {
        val list = mutableListOf<File>()
        val root = File(project.projectRoot)
        listOf("data", "source").forEach { sub ->
            val dir = File(root, sub)
            if (dir.exists()) {
                dir.walkTopDown().maxDepth(3).forEach { f ->
                    if (f.isFile && (f.extension in listOf("sqlite", "db", "sqlite3") || f.name.endsWith(".sqlite-wal"))) {
                        if (!f.name.endsWith("-wal") && !f.name.endsWith("-shm")) {
                            list.add(f)
                        }
                    }
                }
            }
        }
        list
    }

    suspend fun getTables(dbFile: File): List<TableInfo> = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext emptyList()
        val tables = mutableListOf<TableInfo>()

        try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val tableName = cursor.getString(0)
                        val columns = getColumnsForTable(db, tableName)
                        val rowCount = getRowCount(db, tableName)
                        tables.add(TableInfo(name = tableName, columns = columns, rowCount = rowCount))
                    }
                }
            }
        } catch (_: Exception) {}
        tables
    }

    private fun getColumnsForTable(db: SQLiteDatabase, tableName: String): List<ColumnInfo> {
        val list = mutableListOf<ColumnInfo>()
        try {
            db.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                    val pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) == 1
                    list.add(ColumnInfo(name = name, type = type, notNull = notNull, primaryKey = pk))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun getRowCount(db: SQLiteDatabase, tableName: String): Long {
        return try {
            db.rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun executeQuery(dbFile: File, sql: String, maxRows: Int = 100): QueryResult = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) {
            return@withContext QueryResult(error = "Database file does not exist")
        }

        val trimmed = sql.trim()
        val isSelect = trimmed.startsWith("SELECT", ignoreCase = true) ||
                trimmed.startsWith("PRAGMA", ignoreCase = true) ||
                trimmed.startsWith("EXPLAIN", ignoreCase = true)

        val startTime = System.currentTimeMillis()
        try {
            val flags = if (isSelect) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags).use { db ->
                if (isSelect) {
                    db.rawQuery(sql, null).use { cursor ->
                        val duration = System.currentTimeMillis() - startTime
                        val colCount = cursor.columnCount
                        val colNames = (0 until colCount).map { cursor.getColumnName(it) }
                        val rows = mutableListOf<List<String>>()

                        var count = 0
                        while (cursor.moveToNext() && count < maxRows) {
                            val row = (0 until colCount).map { idx ->
                                getCursorValueAsString(cursor, idx)
                            }
                            rows.add(row)
                            count++
                        }
                        QueryResult(
                            columns = colNames,
                            rows = rows,
                            affectedRows = rows.size,
                            durationMs = duration,
                            isQuery = true
                        )
                    }
                } else {
                    // DML / DDL statement
                    val stmt = db.compileStatement(sql)
                    val affected = stmt.executeUpdateDelete()
                    val duration = System.currentTimeMillis() - startTime
                    QueryResult(
                        columns = emptyList(),
                        rows = emptyList(),
                        affectedRows = affected,
                        durationMs = duration,
                        isQuery = false
                    )
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            QueryResult(error = e.message ?: "SQL execution error", durationMs = duration)
        }
    }

    private fun getCursorValueAsString(cursor: Cursor, idx: Int): String {
        return when (cursor.getType(idx)) {
            Cursor.FIELD_TYPE_NULL -> "NULL"
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx).toString()
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx).toString()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(idx)
            Cursor.FIELD_TYPE_BLOB -> "[BLOB ${cursor.getBlob(idx).size} bytes]"
            else -> cursor.getString(idx) ?: ""
        }
    }

    suspend fun exportTableToCsv(dbFile: File, tableName: String): String = withContext(Dispatchers.IO) {
        val res = executeQuery(dbFile, "SELECT * FROM `$tableName`", maxRows = 2000)
        buildString {
            append(res.columns.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
            append("\n")
            res.rows.forEach { row ->
                append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                append("\n")
            }
        }
    }

    suspend fun exportTableToJson(dbFile: File, tableName: String): String = withContext(Dispatchers.IO) {
        val res = executeQuery(dbFile, "SELECT * FROM `$tableName`", maxRows = 2000)
        buildString {
            append("[\n")
            res.rows.forEachIndexed { rIdx, row ->
                append("  {")
                res.columns.forEachIndexed { cIdx, col ->
                    val value = row.getOrNull(cIdx) ?: ""
                    append("\"$col\": \"${value.replace("\"", "\\\"")}\"")
                    if (cIdx < res.columns.size - 1) append(", ")
                }
                append("}")
                if (rIdx < res.rows.size - 1) append(",")
                append("\n")
            }
            append("]")
        }
    }

    /**
     * Checkpoints WAL mode and performs a consistent file copy.
     */
    suspend fun createConsistentBackup(sourceDb: File, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            SQLiteDatabase.openDatabase(sourceDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
            sourceDb.copyTo(targetFile, overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }
}

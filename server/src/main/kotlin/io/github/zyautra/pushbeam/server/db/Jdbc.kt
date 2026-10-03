package io.github.zyautra.pushbeam.server.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

/** 짧은 JDBC 헬퍼. 시각은 epoch milliseconds, 열거형은 이름으로 저장한다. */
fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { it.bind(params); it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        ps.bind(params)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

fun Connection.count(sql: String, vararg params: Any?): Int =
    queryOne(sql, *params) { it.getInt(1) } ?: 0

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        val idx = i + 1
        when (p) {
            null -> setObject(idx, null)
            is Boolean -> setInt(idx, if (p) 1 else 0)
            is Instant -> setLong(idx, p.toEpochMilli())
            is Enum<*> -> setString(idx, p.name)
            is Int -> setInt(idx, p)
            is Long -> setLong(idx, p)
            is String -> setString(idx, p)
            else -> error("unsupported parameter type: ${p::class}")
        }
    }
}

fun ResultSet.str(col: String): String = getString(col)
fun ResultSet.strOrNull(col: String): String? = getString(col)
fun ResultSet.bool(col: String): Boolean = getInt(col) != 0
fun ResultSet.instant(col: String): Instant = Instant.ofEpochMilli(getLong(col))
fun ResultSet.instantOrNull(col: String): Instant? = getLong(col).let { if (wasNull()) null else Instant.ofEpochMilli(it) }
fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

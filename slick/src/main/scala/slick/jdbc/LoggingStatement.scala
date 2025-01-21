package slick.jdbc

import java.sql as js
import java.io.{InputStream, Reader}
import java.net.URL
import java.sql.{Array as _, *}
import java.util.Calendar

import scala.collection.mutable.ArrayBuffer

import slick.jdbc.JdbcBackend.{benchmarkLogger, parameterLogger, statementAndParameterLogger, statementLogger}
import slick.compat.collection.*
import slick.util.TableDump

/** A wrapper for `java.sql.Statement` that logs statements and benchmark results
  * to the appropriate [[JdbcBackend]] loggers. */
class LoggingStatement(st: Statement) extends Statement {
  private[this] val doStatement = statementLogger.isDebugEnabled
  private[this] val doBenchmark = benchmarkLogger.isDebugEnabled
  private[this] val doParameter = parameterLogger.isDebugEnabled
  private[this] val doStatementAndParameter = statementAndParameterLogger.isDebugEnabled

  private[this] var params: ArrayBuffer[(Any, Any)] = _
  private[this] var paramss: ArrayBuffer[ArrayBuffer[(Any, Any)]] = _

  /** log a parameter */
  protected[this] def p(idx: Int, tpe: Any, value: Any): Unit = if(doParameter) {
    if(params eq null) params = new ArrayBuffer
    if(params.size == idx) params += ((tpe, value))
    else {
      while(params.size < idx) params += null
      params(idx-1) = (tpe, value)
    }
  }

  protected[this] def pushParams(): Unit = if (doParameter) {
    if(params ne null) {
      if(paramss eq null) paramss = new ArrayBuffer
      paramss += params
    }
    params = null
  }

  protected[this] def clearParamss(): Unit = if (doParameter) {
    paramss = null
    params = null
  }

  protected[this] def logged[T](sql: String, what: String = "statement")(f: =>T) = {
    if (doStatement && (sql ne null)) statementLogger.debug("Executing " + what + ": " + sql)
    if (doStatementAndParameter && (sql ne null))
      statementAndParameterLogger.debug("Executing " + what + ": " + sql)
    if(doParameter && (paramss ne null) && paramss.nonEmpty) {
      // like s.groupBy but only group adjacent elements and keep the ordering
      def groupBy[U](s: IterableOnce[U])(f: U => AnyRef): IndexedSeq[IndexedSeq[U]] = {
        var current: AnyRef = null
        val b = new ArrayBuffer[ArrayBuffer[U]]
        s.iterator.foreach { v =>
          val id = f(v)
          if(b.isEmpty || current != id) b += ArrayBuffer(v)
          else b.last += v
          current = id
        }
        b.toIndexedSeq.map(_.toIndexedSeq)
      }
      def mkTpStr(tp: Int) = JdbcTypesComponent.typeNames.getOrElse(tp, tp.toString)
      val paramSets = paramss.map { params =>
        (params.map {
          case (tp: Int, _) => mkTpStr(tp)
          case ((tp: Int, scale: Int), _) => mkTpStr(tp)+"("+scale+")"
          case ((tp: Int, tpStr: String), _) => mkTpStr(tp)+": "+tpStr
          case (tpe, _) => tpe.toString
        }, params.map {
          case (_, null) => "NULL"
          case (_, ()) => ""
          case (_, v) =>
            val s = v.toString
            if(s eq null) "NULL" else s
        })
      }
      val dump = new TableDump(25)
      groupBy(paramSets)(_._1).foreach { matchingSets =>
        val types = matchingSets.head._1
        val indexes = 1.to(types.length).map(_.toString)
        dump(Vector(indexes, types.toIndexedSeq), matchingSets.map(_._2).map(_.toIndexedSeq))
          .foreach(s => parameterLogger.debug(s))
      }
    }
    val t0 = if(doBenchmark) System.nanoTime() else 0L
    val res = f
    if (doBenchmark) benchmarkLogger.debug("Execution of " + what + " took " + formatNS(System.nanoTime() - t0))
    clearParamss()
    res
  }

  private[this] def formatNS(ns: Long): String = {
    if(ns < 1000L) s"${ns}ns"
    else if (ns < 1000000L) s"${ns / 1000L}µs"
    else if (ns < 1000000000L) s"${ns / 1000000L}ms"
    else s"${ns / 1000000000L}s"
  }

  override def addBatch(sql: String) = {
    if (doStatement) statementLogger.debug("Adding to batch: " + sql)
    if (doStatementAndParameter) statementAndParameterLogger.debug("Adding to batch: " + sql)
    st.addBatch(sql)
  }
  override def execute(sql: String, columnNames: Array[String]): Boolean = logged(sql) {
    st.execute(sql, columnNames)
  }
  override def execute(sql: String, columnIndexes: Array[Int]): Boolean = logged(sql) {
    st.execute(sql, columnIndexes)
  }
  override def execute(sql: String, autoGeneratedKeys: Int): Boolean =
    logged(sql) {
      st.execute(sql, autoGeneratedKeys)
    }
  override def execute(sql: String): Boolean = logged(sql) {
    st.execute(sql)
  }
  override def executeQuery(sql: String): ResultSet = logged(sql, "query") {
    st.executeQuery(sql)
  }
  override def executeUpdate(sql: String, columnNames: Array[String]): Int =
    logged(sql, "update") {
      st.executeUpdate(sql, columnNames)
    }
  override def executeUpdate(sql: String, columnIndexes: Array[Int]): Int =
    logged(sql, "update") {
      st.executeUpdate(sql, columnIndexes)
    }
  override def executeUpdate(sql: String, autoGeneratedKeys: Int): Int =
    logged(sql, "update") {
      st.executeUpdate(sql, autoGeneratedKeys)
    }
  override def executeUpdate(sql: String): Int = logged(sql, "update") {
    st.executeUpdate(sql)
  }
  override def executeBatch(): Array[Int] = logged(null, "batch") {
    st.executeBatch()
  }

  override def setMaxFieldSize(max: Int) = st.setMaxFieldSize(max)
  override def clearWarnings() = st.clearWarnings()
  override def getMoreResults(current: Int) = st.getMoreResults(current)
  override def getMoreResults: Boolean = st.getMoreResults
  override def getGeneratedKeys: ResultSet = st.getGeneratedKeys
  override def cancel() = st.cancel()
  override def getResultSet: ResultSet = st.getResultSet
  override def setPoolable(poolable: Boolean) = st.setPoolable(poolable)
  override def isPoolable: Boolean = st.isPoolable
  override def setCursorName(name: String) = st.setCursorName(name)
  override def getUpdateCount: Int = st.getUpdateCount
  override def getMaxRows: Int = st.getMaxRows
  override def getResultSetType: Int = st.getResultSetType
  override def unwrap[T](iface: Class[T]): T = st.unwrap(iface)
  override def setMaxRows(max: Int) = st.setMaxRows(max)
  override def getFetchSize: Int = st.getFetchSize
  override def getResultSetHoldability: Int = st.getResultSetHoldability
  override def setFetchDirection(direction: Int) = st.setFetchDirection(direction)
  override def getFetchDirection: Int = st.getFetchDirection
  override def getResultSetConcurrency: Int = st.getResultSetConcurrency
  override def isWrapperFor(iface: Class[?]): Boolean = st.isWrapperFor(iface)
  override def clearBatch() = st.clearBatch()
  override def close() = st.close()
  override def isClosed: Boolean = st.isClosed
  override def getWarnings: SQLWarning = st.getWarnings
  override def getQueryTimeout: Int = st.getQueryTimeout
  override def setQueryTimeout(seconds: Int) = st.setQueryTimeout(seconds)
  override def setFetchSize(rows: Int) = st.setFetchSize(rows)
  override def setEscapeProcessing(enable: Boolean) = st.setEscapeProcessing(enable)
  override def getConnection: Connection = st.getConnection
  override def getMaxFieldSize: Int = st.getMaxFieldSize
  override def closeOnCompletion(): Unit = st.closeOnCompletion()
  override def isCloseOnCompletion: Boolean = st.isCloseOnCompletion
}

/** A wrapper for `java.sql.PreparedStatement` that logs statements, parameters and benchmark results
  * to the appropriate [[JdbcBackend]] loggers. */
class LoggingPreparedStatement(st: PreparedStatement) extends LoggingStatement(st) with PreparedStatement {

  override def execute(): Boolean = {
    pushParams()
    if (statementAndParameterLogger.isDebugEnabled) {
      logged(st.toString, "prepared statement") { st.execute() }
    } else {
      logged(null, "prepared statement") { st.execute() }
    }
  }
  override def executeQuery(): js.ResultSet = {
    pushParams()
    logged(null, "prepared query") {
      st.executeQuery()
    }
  }

  override def executeUpdate(): Int = {
    pushParams()
    if (statementAndParameterLogger.isDebugEnabled) {
      logged(st.toString, "prepared update") { st.executeUpdate() }
    } else {
      logged(null, "prepared update") { st.executeUpdate() }
    }
  }

  override def addBatch(): Unit = {
    pushParams()
    if (statementAndParameterLogger.isDebugEnabled) {
      logged(st.toString, "batch insert") { st.addBatch() }
    } else {
      st.addBatch()
    }
  }
  override def clearParameters(): Unit = {
    clearParamss()
    st.clearParameters()
  }

  override def getMetaData: js.ResultSetMetaData = st.getMetaData
  override def getParameterMetaData: js.ParameterMetaData = st.getParameterMetaData

  // printable parameters
  override def setArray(i: Int, v: js.Array): Unit = {
    p(i, "Array", v)
    st.setArray(i, v)
  }
  override def setBigDecimal(i: Int, v: java.math.BigDecimal): Unit = {
    p(i, "BigDecimal", v)
    st.setBigDecimal(i, v)
  }
  override def setBoolean(i: Int, v: Boolean): Unit = {
    p(i, "Boolean", v)
    st.setBoolean(i, v)
  }
  override def setByte(i: Int, v: Byte): Unit = {
    p(i, "Byte", v)
    st.setByte(i, v)
  }
  override def setBytes(i: Int, v: Array[Byte]): Unit = {
    p(i, "Bytes", v)
    st.setBytes(i, v)
  }
  override def setDate(i: Int, v: js.Date, c: Calendar): Unit = {
    p(i, "Date", (v, c))
    st.setDate(i, v, c)
  }
  override def setDate(i: Int, v: js.Date): Unit = {
    p(i, "Date", v)
    st.setDate(i, v)
  }
  override def setDouble(i: Int, v: Double): Unit = {
    p(i, "Double", v)
    st.setDouble(i, v)
  }
  override def setFloat(i: Int, v: Float): Unit = {
    p(i, "Float", v)
    st.setFloat(i, v)
  }
  override def setInt(i: Int, v: Int): Unit = {
    p(i, "Int", v)
    st.setInt(i, v)
  }
  override def setLong(i: Int, v: Long): Unit = {
    p(i, "Long", v)
    st.setLong(i, v)
  }
  override def setNString(i: Int, v: String): Unit = {
    p(i, "NString", v)
    st.setNString(i, v)
  }
  override def setNull(i: Int, tp: Int, tpStr: String): Unit = {
    p(i, (tp, tpStr), null)
    st.setNull(i, tp, tpStr)
  }
  override def setNull(i: Int, tp: Int): Unit = {
    p(i, tp, null)
    st.setNull(i, tp)
  }
  override def setObject(i: Int, v: Any, tp: Int, scl: Int): Unit = {
    p(i, (tp, scl), v)
    st.setObject(i, v, tp, scl)
  }
  override def setObject(i: Int, v: Any, tp: Int): Unit = {
    p(i, tp, v)
    st.setObject(i, v, tp)
  }
  override def setObject(i: Int, v: Any): Unit = {
    p(i, "Object", v)
    st.setObject(i, v)
  }
  override def setRef(i: Int, v: js.Ref): Unit = {
    p(i, "Ref", v)
    st.setRef(i, v)
  }
  override def setRowId(i: Int, v: js.RowId): Unit = {
    p(i, "RowId", v)
    st.setRowId(i, v)
  }
  override def setSQLXML(i: Int, v: js.SQLXML): Unit = {
    p(i, "SQLXML", v)
    st.setSQLXML(i, v)
  }
  override def setShort(i: Int, v: Short): Unit = {
    p(i, "Short", v)
    st.setShort(i, v)
  }
  override def setString(i: Int, v: String): Unit = {
    p(i, "String", v)
    st.setString(i, v)
  }
  override def setTime(i: Int, v: js.Time, c: Calendar): Unit = {
    p(i, "Time", (v, c))
    st.setTime(i, v, c)
  }
  override def setTime(i: Int, v: js.Time): Unit = {
    p(i, "Time", v)
    st.setTime(i, v)
  }
  override def setTimestamp(i: Int, v: Timestamp, c: Calendar): Unit = {
    p(i, "Timestamp", (v, c))
    st.setTimestamp(i, v, c)
  }
  override def setTimestamp(i: Int, v: Timestamp): Unit = {
    p(i, "Timestamp", v)
    st.setTimestamp(i, v)
  }
  override def setURL(i: Int, v: URL): Unit = {
    p(i, "URL", v)
    st.setURL(i, v)
  }

  // hidden parameters
  override def setAsciiStream(i: Int, v: InputStream): Unit = {
    p(i, "AsciiStream", ())
    st.setAsciiStream(i, v)
  }
  override def setAsciiStream(i: Int, v: InputStream, len: Long): Unit = {
    p(i, "AsciiStream", ())
    st.setAsciiStream(i, v, len)
  }
  override def setAsciiStream(i: Int, v: InputStream, len: Int): Unit = {
    p(i, "AsciiStream", ())
    st.setAsciiStream(i, v, len)
  }
  override def setBinaryStream(i: Int, v: InputStream): Unit = {
    p(i, "BinaryStream", ())
    st.setBinaryStream(i, v)
  }
  override def setBinaryStream(i: Int, v: InputStream, len: Long): Unit = {
    p(i, "BinaryStream", ())
    st.setBinaryStream(i, v, len)
  }
  override def setBinaryStream(i: Int, v: InputStream, len: Int): Unit = {
    p(i, "BinaryStream", ())
    st.setBinaryStream(i, v, len)
  }
  override def setBlob(i: Int, v: InputStream): Unit = {
    p(i, "Blob", ())
    st.setBlob(i, v)
  }
  override def setBlob(i: Int, v: InputStream, len: Long): Unit = {
    p(i, "Blob", ())
    st.setBlob(i, v, len)
  }
  override def setBlob(i: Int, v: js.Blob): Unit = {
    p(i, "Blob", ())
    st.setBlob(i, v)
  }
  override def setCharacterStream(i: Int, v: Reader): Unit = {
    p(i, "CharacterStream", ())
    st.setCharacterStream(i, v)
  }
  override def setCharacterStream(i: Int, v: Reader, len: Long): Unit = {
    p(i, "CharacterStream", ())
    st.setCharacterStream(i, v, len)
  }
  override def setCharacterStream(i: Int, v: Reader, len: Int): Unit = {
    p(i, "CharacterStream", ())
    st.setCharacterStream(i, v, len)
  }
  override def setClob(i: Int, v: Reader): Unit = {
    p(i, "Clob", ())
    st.setClob(i, v)
  }
  override def setClob(i: Int, v: Reader, len: Long): Unit = {
    p(i, "Clob", ())
    st.setClob(i, v, len)
  }
  override def setClob(i: Int, v: js.Clob): Unit = {
    p(i, "Clob", ())
    st.setClob(i, v)
  }
  override def setNCharacterStream(i: Int, v: Reader): Unit = {
    p(i, "NCharacterStream", ())
    st.setNCharacterStream(i, v)
  }
  override def setNCharacterStream(i: Int, v: Reader, len: Long): Unit = {
    p(i, "NCharacterStream", ())
    st.setNCharacterStream(i, v, len)
  }
  override def setNClob(i: Int, v: Reader): Unit = {
    p(i, "NClob", ())
    st.setNClob(i, v)
  }
  override def setNClob(i: Int, v: Reader, len: Long): Unit = {
    p(i, "NClob", ())
    st.setNClob(i, v, len)
  }
  override def setNClob(i: Int, v: js.NClob): Unit = {
    p(i, "NClob", ())
    st.setNClob(i, v)
  }
  @deprecated("setUnicodeStream is deprecated", "")
  override def setUnicodeStream(i: Int, v: InputStream, len: Int): Unit = {
    p(i, "UnicodeStream", ())
    st.setUnicodeStream(i, v, len)
  }
}

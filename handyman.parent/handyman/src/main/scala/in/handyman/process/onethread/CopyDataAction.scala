package in.handyman.process.onethread

import com.typesafe.scalalogging.LazyLogging
import in.handyman.command.Context
import in.handyman.command.Action
import in.handyman.command.CommandProxy
import in.handyman.util.ResourceAccess
import org.eclipse.emf.common.util.EList
import in.handyman.util.ParameterisationEngine
import java.util.ArrayList
import java.sql.SQLException
import scala.util.control.Exception.Finally

class CopydataAction extends in.handyman.command.Action with LazyLogging {
  val detailMap = new java.util.HashMap[String, String]

  def execute(context: in.handyman.command.Context, action: in.handyman.dsl.Action): in.handyman.command.Context = {
    val copydataAsIs: in.handyman.dsl.Copydata = action.asInstanceOf[in.handyman.dsl.Copydata]
    val copydata: in.handyman.dsl.Copydata = CommandProxy.createProxy(copydataAsIs, classOf[in.handyman.dsl.Copydata], context)

    val source = copydata.getSource
    val destination = copydata.getTo
    val name = copydata.getName
    val ddlSql: String = copydata.getValue.replaceAll("\"", "")
    val id = context.getValue("process-id")

    val copydataDbConnfrom = ResourceAccess.rdbmsConn(source)
    val copydataStmtfrom = copydataDbConnfrom.createStatement
    val copydataDbConnto = ResourceAccess.rdbmsConn(destination)
    val copydataStmtto = copydataDbConnto.createStatement

    copydataDbConnto.setAutoCommit(false)

    val output: Array[String] = ddlSql.split(";")
    val select: String = output(0)
    val slimit: String = copydata.getLimit
    val limit: Int = slimit.toInt

    val rs = copydataStmtfrom.executeQuery(select)

    val cols: Int = rs.getMetaData().getColumnCount()
    var query: String = ""
    var j: Int = 0
    try {

      while (rs.next()) {
        val i: Int = 0
        query = query + "("
        for (i <- 1 to cols) {

          var str: String = rs.getString(i)
          if (str != null)
            str = str.replaceAll("[^a-zA-Z0-9-:]", " ")
          query = query + "\"" + str + "\"" + ","

        }

        query = query.substring(0, query.length() - 1) + "),"

        if (j % limit == 0) {

          query = query.replace("\"null\"", "null")
          logger.info("WriteCsv id#{}, name#{}, from#{}, sqlList#{}", id, name, source, query)

          var insert: String = output(1) + query.substring(0, query.length() - 1) + ";"
          logger.info("WriteCsv id#{}, name#{}, from#{}, sqlList#{}", id, name, source, insert)

          copydataStmtto.execute(insert)
          insert = ""
          query = ""

        }
        j = j + 1

        copydataDbConnto.commit()

      }

      query = query.replace("\"null\"", "null")
      var insert: String = output(1) + query.substring(0, query.length() - 1) + ";"
      logger.info("WriteCsv id#{}, name#{}, from#{}, sqlList#{}", id, name, source, insert)
      copydataStmtto.execute(insert)
      copydataDbConnto.commit()

    } catch {
      case ex: SQLException => {
        ex.printStackTrace()
      }
    } finally {
      copydataStmtfrom.close()
      copydataStmtto.close()
      copydataDbConnfrom.close()
      copydataDbConnto.close()
      detailMap.put("name", name)
      detailMap.put("source", source)
      detailMap.put("destination", destination)
      detailMap.put("ddlSql", ddlSql)
    }
    context

  }

  def executeIf(context: in.handyman.command.Context, action: in.handyman.dsl.Action): Boolean = {
    val copydataAsIs = action.asInstanceOf[in.handyman.dsl.Copydata]
    val copydata: in.handyman.dsl.Copydata = CommandProxy.createProxy(copydataAsIs, classOf[in.handyman.dsl.Copydata], context)

    val expression = copydata.getCondition
    try {
      val output = ParameterisationEngine.doYieldtoTrue(expression)
      detailMap.putIfAbsent("condition-output", output.toString())
      output
    } finally {
      if (expression != null)
        detailMap.putIfAbsent("condition", "LHS=" + expression.getLhs + ", Operator=" + expression.getOperator + ", RHS=" + expression.getRhs)

    }
  }

  def generateAudit(): java.util.Map[String, String] = {
    detailMap
  }
}
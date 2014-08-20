package scala.lms.tutorial

import org.scalatest.FunSuite

trait StagedCSV extends Dsl with ScannerBase {

  type Schema = Vector[String]
  def Schema(schema: String*): Schema = schema.toVector
  type Fields = Rep[Array[String]]

  case class Record(fields: Fields, schema: Schema) {
    def apply(key: String): Rep[String] = fields(schema indexOf key)
  }

  sealed abstract class Operator
  case class Scan(filename: Rep[String], schema: Schema) extends Operator
  case class PrintRecord(parent: Operator) extends Operator

  def execOp(o: Operator)(yld: Record => Rep[Unit]): Rep[Unit] = o match {
    case Scan(filename, schema) =>
      val s = newScanner(filename)
      s.next // ignore csv header
      while (s.hasNext) yld(Record(s.next, schema))
    case PrintRecord(parent) => execOp(parent) { rec =>
      println(if (rec.schema.length < 2) rec.fields else rec.fields.mkString(","))
    }
  }
  def execQuery(q: Operator): Rep[Unit] = execOp(q){ _ => }
}

abstract class StagedQuery extends DslDriver[String,Unit] with StagedCSV with ScannerExp { q =>
  override val codegen = new DslGen with ScalaGenScanner {
    val IR: q.type = q
  }
  override def snippet(fn: Rep[String]): Rep[Unit] = execQuery(query(fn))
  def query(fn: Rep[String]): Operator
}

class StagedCSVTest extends TutorialFunSuite {
  val under = "scsv"

  def testquery(name: String, csv: String, query: StagedQuery) {
    test(name) {
      // TODO: check
      //checkOut(name+"-eval", "csv", query.eval("src/data/" + csv))
      //check(name+"-code", query.code)
      query.eval("src/data/" + csv)
      exec(name+"-code", query.code)
    }
  }

  testquery("t1", "t.csv", new StagedQuery {
    def query(fn: Rep[String]) =
      PrintRecord(
        Scan(fn, Schema("Name", "Value", "Flag"))
      )
  })
}

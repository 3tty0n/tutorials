/**
A SQL Query Compiler
====================

Abstraction without regret for efficient data processing.

Outline:
<div id="tableofcontents"></div>

*/

package scala.lms.tutorial

import scala.virtualization.lms.common._
import org.scalatest.FunSuite

trait SQLParser extends QueryAST {
  import scala.util.parsing.combinator._
  object Grammar extends JavaTokenParsers with PackratParsers {
    def fieldIdent: Parser[String] = """[\w\#]+""".r
    def tableIdent: Parser[String] = """[\w_\-/\.]+""".r
    def fieldList: Parser[Schema]  = repsep(fieldIdent,",") ^^ { fs => Schema(fs:_*) }

    def predicate: Parser[Predicate] = ref ~ "=" ~ ref ^^ { case a ~ _ ~ b => Eq(a,b) }
    def ref: Parser[Ref] = fieldIdent ^^ Field | """'\w*'""".r ^^ (s => Value(s.drop(1).dropRight(1))) |       
          """[0-9]+""".r ^^ (s => Value(s.toInt))
    def fromClause: Parser[Operator] = "from" ~> tableIdent ^^ { table => Scan(table) }
    def selectClause: Parser[Operator=>Operator] = "select" ~> ("*" ^^ { _ => (op:Operator) => op } | fieldList ^^ { fs => Project(fs,fs,_:Operator) })
    def whereClause: Parser[Operator=>Operator] = opt("where" ~> predicate ^^ { p => Filter(p, _:Operator) }) ^^ { _.getOrElse(op => op)}

    def stm: Parser[Operator] = selectClause ~ fromClause ~ whereClause ^^ { case p ~ s ~ f => p(f(s)) }
  }
  def parseSql(input: String) = Grammar.parseAll(Grammar.stm, input).get // cleaner error reporting?
}

trait QueryAST {
  val defaultFieldDelimiter = ','
  type Schema
  def Schema(schema: String*): Schema
  def loadSchemaFor(tableName: String): Schema
  def externalSchemaFor(tableName: String): Option[Schema] = None
  def fieldDelimiterFor(tableName: String): Option[Char] = None

  def Scan(tableName: String): Scan = {
    val (schema, externalSchema) = externalSchemaFor(tableName) match {
      case Some(schema) => (schema, true)
      case None => (loadSchemaFor(tableName), false)
    }
    val fieldDelimiter = fieldDelimiterFor(tableName) match {
      case Some(d) => d
      case None => defaultFieldDelimiter
    }
    Scan(tableName, schema, fieldDelimiter, externalSchema)
  }

  sealed abstract class Operator
  case class Scan(tableName: String, schema: Schema, fieldDelimiter: Char, externalSchema: Boolean) extends Operator
  case class PrintCSV(parent: Operator) extends Operator
  case class Project(schema: Schema, schema2: Schema, parent: Operator) extends Operator
  case class Filter(pred: Predicate, parent: Operator) extends Operator
  case class Join(parent1: Operator, parent2: Operator) extends Operator
  case class Group(keys: Schema, agg: Schema, parent: Operator) extends Operator
  case class HashJoin(parent1: Operator, parent2: Operator) extends Operator

  // for filtering
  sealed abstract class Predicate
  case class Eq(a: Ref, b: Ref) extends Predicate

  sealed abstract class Ref
  case class Field(name: String) extends Ref
  case class Value(x: Any) extends Ref
}

trait QueryProcessor extends QueryAST {
  def version: String

  def defaultFilenameFor(tableName: String): String

  type Schema = Vector[String]
  def Schema(schema: String*): Schema = schema.toVector

  type Table
  def tableFor(tableName: String): Table


  override def loadSchemaFor(tableName: String) =
    loadSchema(defaultFilenameFor(tableName))
  def loadSchema(filename: String): Schema = {
    val s = new Scanner(filename)
    val schema = Schema(s.next('\n').split(defaultFieldDelimiter): _*)
    s.close
    schema
  }
}

trait PlainQueryProcessor extends QueryProcessor {
  type Table = String
  def tableFor(tableName: String): Table = defaultFilenameFor(tableName)

  def execQuery(q: Operator): Unit
}

trait StagedQueryProcessor extends QueryProcessor with Dsl {

  type Table = Rep[String] // dynamic filename
  def tableFor(tableName: String): Table = unit(defaultFilenameFor(tableName))

  def execQuery(q: Operator): Rep[Unit]
}

class QueryTest extends TutorialFunSuite {
  val under = "query_"

  trait TestDriver extends SQLParser with QueryProcessor with ExpectedASTs {
    def runtest: Unit

    override def defaultFilenameFor(tableName: String) = dataFilePath(tableName+".csv")
    override def externalSchemaFor(tableName: String) =
      if (tableName.contains("gram")) Some(Schema("Phrase", "Year", "MatchCount", "VolumeCount"))
      else super.externalSchemaFor(tableName)
    override def fieldDelimiterFor(tableName: String) =
        if (tableName.contains("gram")) Some('\t')
        else super.fieldDelimiterFor(tableName)

    type Table
    def name: String
    def query: String
    def parsedQuery: Operator = if (query.isEmpty) expectedAstForTest(name) else parseSql(query)

  }

  trait PlainTestDriver extends TestDriver with PlainQueryProcessor {
    def eval(fn: Table): Unit = execQuery(PrintCSV(parsedQuery))
  }

  trait StagedTestDriver extends TestDriver with StagedQueryProcessor {
    // this is special-cased to run legacy queries as is
    // TODO: generalize once it works
    override def tableFor(tableName: String) = tableName match {
      case "t1gram" => defaultTable // dynamic
      case _ => super.tableFor(tableName) // constant
    }
    var defaultTable: Table = _
    def snippet(fn: Table): Rep[Unit] = {
      defaultTable = fn
      execQuery(PrintCSV(parsedQuery))
    }
  }

  abstract class ScalaPlainQueryDriver(val name: String, val query: String) extends PlainTestDriver with QueryProcessor { q =>
    override def runtest: Unit = {
      test(version+" "+name) {
        assert(expectedAstForTest(name)==parsedQuery)
        checkOut(name, "csv", eval(defaultFilenameFor(if (query.contains("gram")) "t1gram" else "t")))
      }
    }
  }

  abstract class ScalaStagedQueryDriver(val name: String, val query: String) extends DslDriver[String,Unit] with StagedTestDriver with StagedQueryProcessor with ScannerExp { q =>
    override val codegen = new DslGen with ScalaGenScanner {
      val IR: q.type = q
    }
    override def runtest: Unit = {
      if (version == "query_staged0" && query.isEmpty) return ()
      test(version+" "+name) {
        assert(expectedAstForTest(name)==parsedQuery)
        /* FIXME: group by currently not supported in staged Scala version*/
        if (!parsedQuery.toString.contains("Group(")) {
          check(name, code)
          precompile
          checkOut(name, "csv", eval(defaultFilenameFor(if (query.contains("gram")) "t1gram" else "t")))
        }
      }
    }
  }

  abstract class CStagedQueryDriver(val name: String, val query: String) extends DslDriverC[String,Unit] with StagedTestDriver with StagedQueryProcessor with ScannerLowerExp { q =>
    override val codegen = new DslGenC with CGenScannerLower {
      val IR: q.type = q
    }
    override def runtest: Unit = {
      test(version+" "+name) {
        assert(expectedAstForTest(name)==parsedQuery)
        check(name, code, "c")
        //precompile
        checkOut(name, "csv", eval(defaultFilenameFor(if (query.contains("gram")) "t1gram" else "t")))
      }
    }
  }

  def testquery(name: String, query: String = "") {
    val drivers: List[TestDriver] =
      List(
        new ScalaPlainQueryDriver(name, query) with query_unstaged.QueryInterpreter,
        new ScalaStagedQueryDriver(name, query) with query_staged0.QueryCompiler,
        new ScalaStagedQueryDriver(name, query) with query_staged.QueryCompiler,
        new CStagedQueryDriver(name, query) with query_optc.QueryCompiler {
            // FIXME: hack so i don't need to replace Value -> #Value in all the files right now
            override def isNumericCol(s: String) = s == "Value" || super.isNumericCol(s)
        }
      )
    drivers.foreach(_.runtest)
  }

  trait ExpectedASTs extends QueryAST {
    val expectedAstForTest = Map(
      "t1" -> Scan("t"),
      "t2" -> Project(Schema("Name"), Schema("Name"), Scan("t")),
      "t3" -> Project(Schema("Name"), Schema("Name"),
                      Filter(Eq(Field("Flag"), Value("yes")),
                             Scan("t"))),
      "t4" -> Join(Scan("t"),
                   Project(Schema("Name1"), Schema("Name"), Scan("t"))),
      "t5" -> Join(Scan("t"),
                   Project(Schema("Name"), Schema("Name"), Scan("t"))),
      "t4h" -> HashJoin(Scan("t"),
                   Project(Schema("Name1"), Schema("Name"), Scan("t"))),
      "t5h" -> HashJoin(Scan("t"),
                   Project(Schema("Name"), Schema("Name"), Scan("t"))),
      "t6"  -> Group(Schema("Name"),Schema("Value"), Scan("t")),

      "t1gram1" -> Scan("t1gram"),
      "t1gram2" -> Filter(Eq(Field("Phrase"), Value("Auswanderung")), Scan("t1gram"))
    )
  }

  testquery("t1", "select * from t")
  testquery("t2", "select Name from t")
  testquery("t3", "select Name from t where Flag='yes'")
  testquery("t4")
  testquery("t5")
  testquery("t4h")
  testquery("t5h")
  testquery("t6")

  testquery("t1gram1", "select * from t1gram")
  testquery("t1gram2", "select * from t1gram where Phrase='Auswanderung'")
}

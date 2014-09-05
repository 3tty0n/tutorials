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

/**
Relational Algebra AST
----------------------

The core of any query processing engine is an AST representation of
relational algebra operators.
*/
trait QueryAST {
  def tableFor(s: Table) = s // remove

  type Schema = Vector[String]
  def Schema(schema: String*): Schema = schema.toVector
  type Table
  def Scan(tableName: String): Scan = Scan(tableName, None, None)
  def Scan(tableName: String, schema: Option[Schema], delim: Option[Char]): Scan // defined in QueryProcessor

  sealed abstract class Operator
  case class Scan(tableName: Table, schema: Schema, fieldDelimiter: Char, externalSchema: Boolean) extends Operator
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


/**
Parser
------

We add a parser that takes a SQL(-like) string and converts it to tree of operators.
*/

trait SQLParser extends QueryAST {
  import scala.util.parsing.combinator._
  object Grammar extends JavaTokenParsers with PackratParsers {
    def fieldIdent: Parser[String] = """[\w\#]+""".r
    def tableIdent: Parser[String] = """[\w_\-/\.]+""".r | "?"
    def fieldList:  Parser[(Schema,Schema)] =
      repsep(fieldIdent ~ opt("as" ~> fieldIdent), ",") ^^ { fs2s =>
        val (fs,fs1) = fs2s.map { case a~b => (b.getOrElse(a),a) }.unzip
        (Schema(fs:_*),Schema(fs1:_*))
      }
    def fieldList1:  Parser[Schema] = repsep(fieldIdent,",") ^^ (fs => Schema(fs:_*))

    def predicate: Parser[Predicate] = ref ~ "=" ~ ref ^^ { case a ~ _ ~ b => Eq(a,b) }
    def ref: Parser[Ref] = fieldIdent ^^ Field | """'\w*'""".r ^^ (s => Value(s.drop(1).dropRight(1))) |
          """[0-9]+""".r ^^ (s => Value(s.toInt))
    def tableClause: Parser[Operator] =
      tableIdent ~ opt("schema" ~> fieldList1) ~ opt("delim" ~> ("""\t""" ^^ (_ => '\t') | """.""".r ^^ (_.head))) ^^ {
        case table ~ schema ~ delim => Scan(table, schema, delim)
      } |
      ("(" ~> stm <~ ")")
    def fromClause: Parser[Operator] =
      "from" ~> joinClause
    def joinClause: Parser[Operator] =
      ("nestedloops" ~> repsep(tableClause, "join") ^^ { _.reduceLeft((a,b) => Join(a,b)) }) |
      (repsep(tableClause, "join") ^^ { _.reduceLeft((a,b) => HashJoin(a,b)) })

    def selectClause: Parser[Operator=>Operator] =
      "select" ~> ("*" ^^ { _ => (op:Operator) => op } | fieldList ^^ { case (fs,fs1) => Project(fs,fs1,_:Operator) })
    def whereClause: Parser[Operator=>Operator] =
      opt("where" ~> predicate ^^ { p => Filter(p, _:Operator) }) ^^ { _.getOrElse(op => op)}

    def groupClause: Parser[Operator=>Operator] =
      opt("group" ~> "by" ~> fieldList1 ~ ("sum" ~> fieldList1) ^^ { case p1 ~ p2 => Group(p1,p2, _:Operator) }) ^^ { _.getOrElse(op => op)}

    def stm: Parser[Operator] = selectClause ~ fromClause ~ whereClause ~ groupClause ^^ {
      case p ~ s ~ f ~ g => g(p(f(s)))
    }
  }
  def parseSql(input: String) = Grammar.parseAll(Grammar.stm, input).get // cleaner error reporting?
}



/**
Iterative Development of a Query Processor
------------------------------------------

We develop our SQL engine in multiple steps. Each steps leads to a
working processor, and each successive step either adds a feature or
optimization. We define a common interface for each processor.

*/
trait QueryProcessor extends QueryAST {
  def version: String
  val defaultFieldDelimiter = ','

  def filePath(table: String): String = table
  def dynamicFilePath(table: String): Table

  def Scan(tableName: String, schema: Option[Schema], delim: Option[Char]): Scan = {
    val dfile = dynamicFilePath(tableName)
    val (schema1, externalSchema) = schema.map(s=>(s,true)).getOrElse((loadSchema(filePath(tableName)),false))
    Scan(dfile, schema1, delim.getOrElse(defaultFieldDelimiter), externalSchema)
  }

  def loadSchema(filename: String): Schema = {
    val s = new Scanner(filename)
    val schema = Schema(s.next('\n').split(defaultFieldDelimiter): _*)
    s.close
    schema
  }

  def execQuery(q: Operator): Unit
}

/**
### A (Plain) Query Interpreter

We start with a plain query processor: an interpreter.

- [query_unstaged.scala](query_unstaged.html)

*/
trait PlainQueryProcessor extends QueryProcessor {
  type Table = String
}

/**

### A Staged Query Interpreter (= Compiler)

Staging our query interpreter yields a query compiler.
In the first iteration we generate Scala code:

- [query_staged0](query_staged0.html)

- [query_staged](query_staged.html)

For additional low-level optimizations we switch to
generating C code:

- [query_optc](query_optc.html)

*/
trait StagedQueryProcessor extends QueryProcessor with Dsl {
  type Table = Rep[String] // dynamic filename
  override def filePath(table: String) = if (table == "?") throw new Exception("file path for table ? not available") else super.filePath(table)
}

/**

Unit Tests
----------

*/
class QueryTest extends TutorialFunSuite {
  val under = "query_"

  trait TestDriver extends SQLParser with QueryProcessor with ExpectedASTs {
    def runtest: Unit
    override def filePath(table: String) = dataFilePath(table)

    def name: String
    def query: String
    def parsedQuery: Operator = if (query.isEmpty) expectedAstForTest(name) else parseSql(query)
  }

  trait PlainTestDriver extends TestDriver with PlainQueryProcessor {
    override def dynamicFilePath(table: String): Table = if (table == "?") defaultEvalTable else filePath(table)
    def eval(fn: Table): Unit = {
      execQuery(PrintCSV(parsedQuery))
    }
  }

  trait StagedTestDriver extends TestDriver with StagedQueryProcessor {
    var dynamicFileName: Table = _
    override def dynamicFilePath(table: String): Table = if (table == "?") dynamicFileName else unit(filePath(table))
    def snippet(fn: Table): Rep[Unit] = {
      dynamicFileName = fn
      execQuery(PrintCSV(parsedQuery))
    }
  }

  abstract class ScalaPlainQueryDriver(val name: String, val query: String) extends PlainTestDriver with QueryProcessor { q =>
    override def runtest: Unit = {
      test(version+" "+name) {
        for (expectedParsedQuery <- expectedAstForTest.get(name)) {
          assert(expectedParsedQuery==parsedQuery)
        }
        checkOut(name, "csv", eval(defaultEvalTable))
      }
    }
  }

  abstract class ScalaStagedQueryDriver(val name: String, val query: String) extends DslDriver[String,Unit] with StagedTestDriver with StagedQueryProcessor with ScannerExp { q =>
    override val codegen = new DslGen with ScalaGenScanner {
      val IR: q.type = q
    }

    override def runtest: Unit = {
      if (version == "query_staged0" && List("Group","HashJoin").exists(parsedQuery.toString contains _)) return ()
      test(version+" "+name) {
        for (expectedParsedQuery <- expectedAstForTest.get(name)) {
          assert(expectedParsedQuery==parsedQuery)
        }
        check(name, code)
        precompile
        checkOut(name, "csv", eval(defaultEvalTable))
      }
    }
  }

  abstract class CStagedQueryDriver(val name: String, val query: String) extends DslDriverC[String,Unit] with StagedTestDriver with StagedQueryProcessor with ScannerLowerExp { q =>
    override val codegen = new DslGenC with CGenScannerLower {
      val IR: q.type = q
    }
    override def runtest: Unit = {
      test(version+" "+name) {
        for (expectedParsedQuery <- expectedAstForTest.get(name)) {
          assert(expectedParsedQuery==parsedQuery)
        }
        check(name, code, "c")
        //precompile
        checkOut(name, "csv", eval(defaultEvalTable))
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

  // NOTE: we can use "select * from ?" to use dynamic file names (not used here right now)

  trait ExpectedASTs extends QueryAST {
    val scan_t = Scan("t.csv")
    val scan_t1gram = Scan("?",Some(Schema("Phrase", "Year", "MatchCount", "VolumeCount")),Some('\t'))

    val expectedAstForTest = Map(
      "t1" -> scan_t,
      "t2" -> Project(Schema("Name"), Schema("Name"), scan_t),
      "t3" -> Project(Schema("Name"), Schema("Name"),
                      Filter(Eq(Field("Flag"), Value("yes")),
                             scan_t)),
      "t4" -> Join(scan_t,
                   Project(Schema("Name1"), Schema("Name"), scan_t)),
      "t5" -> Join(scan_t,
                   Project(Schema("Name"), Schema("Name"), scan_t)),
      "t4h" -> HashJoin(scan_t,
                   Project(Schema("Name1"), Schema("Name"), scan_t)),
      "t5h" -> HashJoin(scan_t,
                   Project(Schema("Name"), Schema("Name"), scan_t)),
      "t6"  -> Group(Schema("Name"),Schema("Value"), scan_t),

      "t1gram1" -> scan_t1gram,
      "t1gram2" -> Filter(Eq(Field("Phrase"), Value("Auswanderung")), scan_t1gram)
    )
  }

  testquery("t1", "select * from t.csv")
  testquery("t2", "select Name from t.csv")
  testquery("t3", "select Name from t.csv where Flag='yes'")
  testquery("t4", "select * from nestedloops t.csv join (select Name as Name1 from t.csv)")
  testquery("t5", "select * from nestedloops t.csv join (select Name from t.csv)")
  testquery("t4h","select * from t.csv join (select Name as Name1 from t.csv)")
  testquery("t5h","select * from t.csv join (select Name from t.csv)")
  testquery("t6", "select * from t.csv group by Name sum Value") // not 100% right syntax, but hey ...

  val defaultEvalTable = dataFilePath("t1gram.csv")
  val t1gram = "? schema Phrase, Year, MatchCount, VolumeCount delim \\t"
  testquery("t1gram1", s"select * from $t1gram")
  testquery("t1gram2", s"select * from $t1gram where Phrase='Auswanderung'")
  testquery("t1gram2n", s"select * from nestedloops words.csv join (select Phrase as Word, Year, MatchCount, VolumeCount from $t1gram)")
  testquery("t1gram2h", s"select * from words.csv join (select Phrase as Word, Year, MatchCount, VolumeCount from $t1gram)")
  testquery("t1gram3", s"select * from nestedloops words.csv join (select * from $t1gram)")
  testquery("t1gram3h", s"select * from words.csv join (select * from $t1gram)")
  testquery("t1gram4", s"select * from nestedloops words.csv join (select Phrase as Word, Year, MatchCount, VolumeCount from $t1gram)")
  testquery("t1gram4h", s"select * from words.csv join (select Phrase as Word, Year, MatchCount, VolumeCount from $t1gram)")
}

// example runs from sbt console
/*
test:run unstaged "select * from ? schema Phrase, Year, MatchCount, VolumeCount delim \\t where Phrase='Auswanderung'" src/data/t1gram.csv
test:run c "select * from ? schema Phrase, Year, MatchCount, VolumeCount delim \\t where Phrase='Auswanderung'" src/data/1gram_a.csv
*/
object Run {
  def time[A](a: => A) = {
    val now = System.nanoTime
    val result = a
    val micros = (System.nanoTime - now) / 1000
    println("%d microseconds".format(micros))
    result
  }

    var query: String = _
    var fn: String = _


    trait Engine extends QueryProcessor with SQLParser {
      def liftTable(n: String): Table
      def eval: Unit
      def prepare: Unit = {}
      def run: Unit = execQuery(PrintCSV(parseSql(query)))
      override def dynamicFilePath(table: String): Table =
        liftTable(if (table == "?") fn else filePath(table))
    }
    trait StagedEngine extends Engine with StagedQueryProcessor {
      override def liftTable(n: String) = unit(n)
    }
    def unstaged_engine: Engine =
      new Engine with query_unstaged.QueryInterpreter {
        override def liftTable(n: Table) = n
        override def eval = run
      }
    def scala_engine =
      new DslDriver[String,Unit] with ScannerExp
      with StagedEngine with query_staged.QueryCompiler { q =>
        override val codegen = new DslGen with ScalaGenScanner {
          val IR: q.type = q
        }
        override def snippet(fn: Table): Rep[Unit] = run
        override def prepare: Unit = precompile
        override def eval: Unit = eval(fn)
      }
    def c_engine =
      new DslDriverC[String,Unit] with ScannerLowerExp
      with StagedEngine with query_optc.QueryCompiler { q =>
        override val codegen = new DslGenC with CGenScannerLower {
          val IR: q.type = q
        }
        override def snippet(fn: Table): Rep[Unit] = run
        override def prepare: Unit = {}
        override def eval: Unit = eval(fn)
      }

  def main(args: Array[String]) {
    val version = args(0)
    val engine = version match {
      case "c" => c_engine
      case "scala" => scala_engine
      case "unstaged" => unstaged_engine
      case _ => println("warning: unexecpted engine, using 'unstaged' by default")
        unstaged_engine
    }
    query = args(1)
    if (args.length > 2)
      fn = args(2)

    engine.prepare
    time(engine.eval)
  }
}

/**
Suggestions for Exercises
-------------------------

- Implement a scanner that reads on demand from a URL.

  (Cool with: a new operator that only prints the first N results.)

- (easy) Implement a typed schema in the Scala version, so that the
  types of columns are statically known, while the values are not.

  (Hint: the C version already does this, but is also more involved
  because of the custom type representations.)

- Implement more predicates (e.g. `LessThan`) and predicate
  combinators (e.g. `And`, `Or`) in order to run more interesting
  queries.

- (medium) Implement a real column-oriented database, where each column has its
  own file so that it can be read independently.

- Implement an optimizer on the relational algebra before generating code.

  (Hint: smart constructors might help.)

*/

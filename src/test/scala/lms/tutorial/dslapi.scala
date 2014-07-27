package scala.lms.tutorial

import scala.virtualization.lms.common._
import scala.reflect.SourceContext

trait Dsl extends NumericOps with PrimitiveOps with BooleanOps with LiftString with LiftNumeric with LiftBoolean with IfThenElse with Equal with RangeOps with OrderingOps with MiscOps with ArrayOps with StringOps with SeqOps with Functions with While with StaticData with Variables with LiftVariables {
  implicit def repStrToSeqOps(a: Rep[String]) = new SeqOpsCls(a.asInstanceOf[Rep[Seq[Char]]])
  def infix_&&&(lhs: Rep[Boolean], rhs: => Rep[Boolean]): Rep[Boolean] =
    __ifThenElse(lhs, rhs, unit(false))
  def comment[A:Manifest](l: String, verbose: Boolean = true)(b: => Rep[A])
}
trait DslExp extends Dsl with NumericOpsExpOpt with PrimitiveOpsExpOpt with BooleanOpsExp with CompileScala with IfThenElseExpOpt with EqualExpBridgeOpt with RangeOpsExp with OrderingOpsExp with MiscOpsExp with EffectExp with ArrayOpsExpOpt with StringOpsExp with SeqOpsExp with FunctionsRecursiveExp with WhileExp with StaticDataExp with VariablesExp {
  override def boolean_or(lhs: Exp[Boolean], rhs: Exp[Boolean])(implicit pos: SourceContext) : Exp[Boolean] = lhs match {
    case Const(false) => rhs
    case _ => super.boolean_or(lhs, rhs)
  }

  case class Comment[A:Manifest](l: String, verbose: Boolean, b: Block[A]) extends Def[A]
  def comment[A:Manifest](l: String, verbose: Boolean)(b: => Rep[A]) = {
    val br = reifyEffects(b)
    val be = summarizeEffects(br)
    reflectEffect(Comment(l, verbose, br), be)
  }
  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case Comment(_, _, b) => effectSyms(b)
    case _ => super.boundSyms(e)
  }

  override def array_apply[T:Manifest](x: Exp[Array[T]], n: Exp[Int])(implicit pos: SourceContext): Exp[T] = (x,n) match {
    case (Def(StaticData(x:Array[T])), Const(n)) => 
      val y = x(n)
      if (y.isInstanceOf[Int]) unit(y) else staticData(y)
    case _ => super.array_apply(x,n)
  }
}
trait DslGen extends ScalaGenNumericOps with ScalaGenPrimitiveOps with ScalaGenBooleanOps with ScalaGenIfThenElse with ScalaGenEqual with ScalaGenRangeOps with ScalaGenOrderingOps with ScalaGenMiscOps with ScalaGenArrayOps with ScalaGenStringOps with ScalaGenSeqOps with ScalaGenFunctions with ScalaGenWhile with ScalaGenStaticData with ScalaGenVariables {
  val IR: DslExp

  import IR._
  
  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case IfThenElse(c,Block(Const(true)),Block(Const(false))) =>
      emitValDef(sym, quote(c))
    case Comment(s, verbose, b) =>
      stream.println("val " + quote(sym) + " = {")
      stream.println("//#" + s)
      if (verbose) {
        stream.println("// generated code for " + s.replace('_', ' '))
      } else {
        stream.println("// generated code")
      }
      emitBlock(b)
      stream.println(quote(getBlockResult(b)))
      stream.println("//#" + s)
      stream.println("}")
    case _ => super.emitNode(sym, rhs)
  }
}
trait DslImpl extends DslExp { q =>
  object codegen extends DslGen {
    val IR: q.type = q
  }
}

abstract class DslSnippet[A:Manifest,B:Manifest] extends Dsl {
  def snippet(x: Rep[A]): Rep[B]
}

abstract class DslDriver[A:Manifest,B:Manifest] extends DslSnippet[A,B] with DslImpl {
  lazy val f = compile(snippet)
  def eval(x: A): B = f(x)
  lazy val code: String = {
    val source = new java.io.StringWriter()
    codegen.emitSource(snippet, "Snippet", new java.io.PrintWriter(source))
    source.toString
  }
}

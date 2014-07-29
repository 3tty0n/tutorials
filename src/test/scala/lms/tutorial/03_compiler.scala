/**

Overview
========

1. Intro: Not your Grandfather's Compiler
1. Intermediate Representation: Trees
    1. Trees Instead of Strings
        1. Modularity: Adding IR Node Types
    1. Enabling Analysis and Transformation
        1. Modularity: Adding Traversal Passes
        1. Solving the ''Expression Problem''
        1. Generating Code
        1. Modularity: Adding Transformations
        1. Transformation by Iterated Staging
    1. Problem: Phase Ordering
1. Intermediate Representation: Graphs
    1. Purely Functional Subset
    1. Modularity: Adding IR Node Types
    1. Simpler Analysis and More Flexible Transformations
        1. Common Subexpression Elimination/Global Value Numbering
        1. Pattern Rewrites
        1. Modularity: Adding new Optimizations
        1. Context- and Flow-Sensitive Transformations
        1. Graph Transformations
        1. Dead Code Elimination
    1. From Graphs Back to Trees
        1. Code Motion
            1. Pathological Cases
            1. Scheduling
        1. Tree-Like Traversals and Transformers
    1. Effects
        1. Simple Effect Domain
        1. Fine Grained Effects: Tracking Mutations per Allocation Site
            1. Restricting Possible Effects
1. Advanced Optimizations
    1. Rewriting
        1. Context-Sensitive Rewriting
        1. Speculative Rewriting: Combining Analyses and Transformations
        1. Delayed Rewriting and Multi-Level IR
    1. Splitting and Combining Statements
        1. Effectful Statements
        1. Data Structures
        1. Representation Conversion
    1. Loop Fusion and Deforestation


# (Chapter 0) Intro: Not your Grandfather's Compiler
<a name="chap:300"></a>

This part discusses compiler internals. 
How do embedded compilers compile their programs?

The purely string based representation of staged programs from Part~\ref{part:P1} does 
not allow analysis or transformation of embedded programs. Since LMS is not
inherently tied to a particular program representation it is very easy to
pick one that is better suited for optimization. As a first cut, we switch to
an intermediate representation (IR) based on expression trees, adding a level of indirection 
between construction of object programs and code generation (Chapter~\ref{chap:310trees}).
On this tree IR we can define traversal and transformation passes and
build a straightforward embedded compiler. We can add new IR node types 
and new transformation passes that implement domain specific
optimizations. In particular we can use multiple passes of staging:
While traversing (effectively, interpreting) one IR we can execute staging
commands to build another staged program, possibly in a different, 
lower-level object language. 

However the extremely high degree of extensibility poses serious challenges.
In particular, the interplay of optimizations implemented as many separate 
phases does not yield good results due to the phase ordering problem: It 
is unclear in which order
and how often to execute these phases, and since each optimization pass 
has to make pessimistic assumptions about the outcome of all other passes 
the global result is often suboptimal compared to a dedicated, combined 
optimization phase \cite{veldhuizen:combiningoptimizations,click95combineanalysis}. 
There are also implementation challenges as each optimization needs to be 
designed to treat unknown IR nodes in a sensible way.

Other challenges are due to the fact that embedded compilers are supposed
to be used like libraries. Extending an embedded compiler should be easy,
and as much of the work as possible should be delegated to a library
of compiler components. Newly defined high-level IR nodes should 
profit from generic optimizations automatically.


To remedy this situation, we switch to a graph-based ''sea of nodes'' 
representation (Chapter~\ref{chap:320graphs}). This representation links definitions and uses, and it also
reflects the program block structure via nesting edges.
We consider purely functional programs first. A number of nontrivial optimizations
become considerably simpler. Common subexpression elimination (CSE) and dead 
code elimination (DCE) are particularly easy. Both are completely generic and support
an open domain of IR node types. 
Optimizations that can be expressed as context-free rewrites are also easy to add
in a modular fashion.
A scheduling and code motion algorithm transforms graphs back into trees, moving 
computations to places where they are less often executed, e.g.\ out of loops or functions. 
Both graph-based and tree-based transformations are useful: graph-based
transformations are usually simpler and more efficient whereas tree-based 
transformations, implemented as multiple staging passes, can be more
powerful and employ arbitrary context-sensitive information.

To support effectful programs, we make effects explicit in the dependency graph 
(similar to SSA form). We can support simple effect domains (pure vs effectful) 
and more fine grained ones, such as tracking modifications per allocation site. 
The latter one relies on alias and points-to analysis.


We turn to advanced optimizations in Chapter~\ref{chap:330opt}.
For combining analyses and optimizations, it is crucial to maintain optimistic assumptions for
all analyses. The key challenge is that one analysis has to anticipate the effects of the other
transformations. The solution is
speculative rewriting \cite{lerner02composingdataflow}: transform a program fragment 
in the presence of partial and possibly
unsound analysis results and re-run the analyses on the transformed code until a fixpoint
is reached. This way, different analyses can communicate through the transformed code and 
need not anticipate their results in other ways. Using speculative rewriting, we compose 
many optimizations into more powerful combined passes. Often, a single forward
simplification pass that can be used to clean up after non-optimizing 
transformations is sufficient.

However not all rewrites can fruitfully be combined into a single phase. For example,
high-level representations of linear algebra operations may give rise to rewrite
rules like $I M \rightarrow M$ where $I$ is the identity matrix. At the same time,
there may be rules that define how a matrix multiplication can be implemented in terms
of arrays and while loops, or a call to an external library (BLAS). 
To be effective, all the high-level simplifications need to be applied
exhaustively before any of the lowering transformations are applied. But
lowering transformations may create new opportunities for high-level rules, too.
Our solution here is delayed rewriting: programmers can specify that a 
certain rewrite should not be applied right now, but have it registered to
be executed at the next iteration of a particular phase. Delayed rewriting
thus provides a way of grouping and prioritizing modularly defined
transformations.


On top of this infrastructure, we build a number of advanced optimizations.
A general pattern is split and merge: We split operations and
data structures in order to expose their components to rewrites and dead-code 
elimination and then merge the remaining parts back together. This struct transformation 
also allows for more general data structure conversions, including array-of-struct to
struct-of-array representation conversion. Furthermore we present a novel loop fusion
algorithm, a powerful transformation that removes intermediate data structures. 

Evaluation and examples follow in Part~\ref{part:P3}.


# (Chapter 1) Intermediate Representation: Trees
\label{chap:310trees}

With the aim of generating code, we could represent
staged expressions directly as strings, as done in Part~\ref{part:P1}. But for optimization
purposes we would rather have a structured intermediate
representation that we can analyze in various ways. Fortunately, LMS
makes it very easy to use a different internal program
representation.

# Trees Instead of Strings
\label{sec:301}

Our starting point is an object language \emph{interface} derived from Part~\ref{part:P1}:
\begin{listing}
trait Base {
  type Rep[T]
}
trait Arith extends Base {
  def infix_+(x: Rep[Double], y: Rep[Double]): Rep[Double]
  def infix_*(x: Rep[Double], y: Rep[Double]): Rep[Double]
  ...
}
trait IfThenElse extends Base  {
  def __ifThenElse[T](c: Rep[Boolean], a: =>Rep[T], b: =>Rep[T]): Rep[T]
}
\end{listing}
The goal will be to build a corresponding \emph{implementation} hierarchy that supports
optimizing compilation.

Splitting interface and implementation has many advantages, most importantly a clear
separation between the user program world and the compiler implementation world. 
For the sake of completeness, let us briefly recast the string representation 
from Part~\ref{part:P1} in this model:
\begin{listing}
trait BaseStr extends Base {
  type Rep[T] = String
}
trait ArithStr extends BaseStr with Arith {
  def infix_+(x: Rep[Double], y: Rep[Double]) = perform(x + " + " + y)
  def infix_*(x: Rep[Double], y: Rep[Double]) = perform(x + " * " + y)
  ...
}
trait IfThenElseStr extends BaseStr with IfThenElse  {
  def __ifThenElse[T](c: Rep[Boolean], a: =>Rep[T], b: =>Rep[T]) =
    perform("if (" + c + ") " + accumulate(a) + " else " + accumulate(b))
}
\end{listing}


In this chapter, we will use an IR that is based on expression trees, closely resembling
the abstract syntax tree (AST) of a staged program. This representation enables separate analysis, 
optimization and code generation passes. We will use the following types:
\begin{listing}
type Exp[T]     // atomic:     Sym, Const
type Def[T]     // composite:  Exp + Exp, Exp * Exp, ...
type Stm[T]     // statement:  val x = Def
type Block[T]   // blocks:     { Stm; ...; Stm; Exp }
\end{listing}
They are defined as follows in a separate trait:
\begin{listing}
trait Expressions {
  // expressions (atomic)
  abstract class Exp[T]
  case class Const[T](x: T) extends Exp[T]
  case class Sym[T](n: Int) extends Exp[T]
  def fresh[T]: Sym[T]

  // definitions (composite, subclasses provided by other traits)
  abstract class Def[T]
  
  // statements
  case class Stm[T](sym: Sym[T], rhs: Def[T])

  // blocks
  case class Block[T](stms: Stm[_], res: Exp[T])
  
  // perform and accumulate
  def reflectStm[T](d: Stm[T]): Exp[T]
  def reifyBlock[T](b: =>Exp[T]): Block[T]

  // bind definitions to symbols automatically
  // by creating a statement
  implicit def toAtom[T](d: Def[T]): Exp[T] = 
    reflectStm(Stm(fresh[T], d))
}
\end{listing}
This trait \code{Expressions} will be mixed in at the root of the object language 
implementation hierarchy. The guiding principle 
is that each definition has an
associated symbol and refers to other definitions
only via their symbols. This means that every 
composite value will be named, similar to administrative
normal form (ANF).
Methods \code{reflectStm} and \code{reifyBlock}
take over the responsibility of \code{perform} 
and \code{accumulate}.

## Modularity: Adding IR Node Types

We observe that there are no concrete definition classes provided by trait \code{Expressions}.
Providing meaningful data types is the responsibility of other traits that implement the
interfaces defined previously (\code{Base} and its descendents).

Trait \code{BaseExp} forms the root of the implementation hierarchy and installs 
atomic expressions as the representation of staged
values by defining \code{Rep[T] = Exp[T]}: 
\begin{listing}
trait BaseExp extends Base with Expressions {
  type Rep[T] = Exp[T]
}
\end{listing}
For each interface trait, there is one corresponding core implementation trait.
Shown below, we have traits \code{ArithExp}
and \code{IfThenElseExp} as the running example. 
Both traits define one definition class for each
operation defined by \code{Arith} and \code{IfThenElse}, respectively, and
implement the corresponding interface methods to create instances of
those classes.
\begin{listing}
trait ArithExp extends BaseExp with Arith {
  case class Plus(x: Exp[Double], y: Exp[Double]) extends Def[Double]
  case class Times(x: Exp[Double], y: Exp[Double]) extends Def[Double]
  def infix_+(x: Rep[Double], y: Rep[Double]) = Plus(x, y)
  def infix_*(x: Rep[Double], y: Rep[Double]) = Times(x, y)
  ...
}
trait IfThenElseExp extends BaseExp with IfThenElse {
  case class IfThenElse(c: Exp[Boolean], a: Block[T], b: Block[T]) extends Def[T]
  def __ifThenElse[T](c: Rep[Boolean], a: =>Rep[T], b: =>Rep[T]): Rep[T] =
    IfThenElse(c, reifyBlock(a), reifyBlock(b))
}
\end{listing}

The framework ensures that code that contains staging operations will
always be executed within the dynamic scope of at least one invocation 
of \code{reifyBlock}, which returns a block object
and takes as call-by-name argument the present-stage expression 
that will compute the staged block result.
Block objects can be part of definitions, e.g.\ for
loops or conditionals.

Since all operations in interface traits such as
\code{Arith} return \code{Rep} types, 
equating \code{Rep[T]} and \code{Exp[T]} in trait
\code{BaseExp} means
that conversion to symbols will take place already 
within those methods. This fact is important
because it establishes our correspondence between
the evaluation order of the program generator
and the evaluation order of the generated program:
at the point where the generator calls \code{toAtom},
the composite definition is turned into an atomic
value via \code{reflectStm}, i.e.\ its evaluation will be recorded
now and played back later in the same
relative order with respect to others
within the closest \code{reifyBlock} 
invocation.





# Enabling Analysis and Transformation

Given our IR representation it is easy to add traversals
and transformations.

## Modularity: Adding Traversal Passes

All that is needed to define a generic in-order traversal
is a way to access all blocks immediately contained in a
definition:
\begin{listing}
def blocks(x: Any): List[Block[Any]]
\end{listing}
For example, applying \code{blocks} to an \code{IfThenElse}
node will return the then and else blocks.
Since definitions are case classes, this method is easy 
to implement by using the \code{Product} interface that
all case classes implement.

The basic structural in-order traversal is then defined like this:
\begin{listing}
trait ForwardTraversal {
  val IR: Expressions
  import IR._
  def traverseBlock[T](b: Block[T]): Unit = b.stms.foreach(traverseStm)
  def traverseStm[T](s: Stm[T]): Unit = blocks(s).foreach(traverseBlock)
}
\end{listing}

Custom traversals can be implemented in a modular way by extending
the \code{ForwardTraversal} trait:
\begin{listing}
trait MyTraversalBase extends ForwardTraversal {
  val IR: BaseExp
  import IR._
  override def traverseStm[T](s: Stm[T]) = s match {
    // custom base case or delegate to super
    case _ => super.traverseStm(s)
  }
}
trait MyTraversalArith extends MyTraversalBase {
  val IR: ArithExp
  import IR._
  override def traverseStm[T](s: Stm[T]) = s match {
    case Plus(x,y) => ... // handle specific nodes
    case _ => super.traverseStm(s)
  }
}
\end{listing}
For each unit of functionality such as \code{Arith} or \code{IfThenElse}
the traversal actions can be defined separately as \code{MyTraversalArith}
and \code{MyTraversalIfThenElse}.

Finally, we can use our traversal as follows:
\begin{listing}
trait Prog extends Arith {
  def main = ... // program code here
}
val impl = new Prog with ArithExp
val res = impl.reifyBlock(impl.main)  
val inspect = MyTraversalArith { val IR: impl.type = impl }
inspect.traverseBlock(res)
\end{listing}





## Solving the ''Expression Problem''

In essence, traversals confront us with the classic ''expression problem''
of independently extending a data model with new data variants and 
new operations \citep{wadlerExprProblem}.
There are many solutions to this problem but most of them
are rather heavyweight.
More lightweight implementations are possible in languages that support 
multi-methods, i.e.\
dispatch method calls dynamically based on the actual types of
all the arguments. 
We can achieve essentially the same using pattern
matching and mixin composition, making use of the fact 
that composing traits is subject to linearization \citep{DBLP:conf/oopsla/OderskyZ05}.
We package each set of specific traversal rules into its own
trait, e.g.\ \code{MyTraversalArith} that inherits from \code{MyTraversalBase}
and overrides \code{traverseStm}.
When the arguments do not match the rewriting pattern,
the overridden method will invoke the ''parent'' implementation using \code{super}.
When several such traits are combined, the super calls
will traverse the overridden method implementations according to
the linearization order of their containing traits. 
The use of pattern matching and super calls is similar to earlier
work on extensible algebraic data types with defaults~\cite{DBLP:conf/icfp/ZengerO01},
which supported linear extensions but not composition of independent extensions.

Implementing multi-methods in a statically typed setting usually poses three problems:
separate type checking/compilation, ensuring non-ambiguity and ensuring exhaustiveness.
The described encoding supports separate type-checking and compilation in as far as
traits do. Ambiguity is ruled out by always following the linearization order and
the first-match semantics of pattern matching. Exhaustiveness is ensured at the type
level by requiring a default implementation, although no guarantees can be
made that the default will not choose to throw an exception at runtime.
In the particular case of traversals, the default is always
safe and will just continue the structural traversal.

## Generating Code

Code generation is just a traversal pass that prints code. Compiling
and executing code can use the same mechanism as described in Section~\ref{sec:230codegen}.


## Modularity: Adding Transformations

Transformations work very similar to traversals. One option
is to traverse and transform an existing program more or less in place, not actually modifying data 
but attaching new Defs to existing Syms:
\begin{listing}
trait SimpleTransformer {
  val IR: Expressions
  import IR._
  def transformBlock[T](b: Block[T]): Block[T] = 
    Block(b.stms.flatMap(transformStm), transformExp(b.res))
  def transformStm[T](s: Stm[T]): List[Stm] = 
    List(Stm(s.lhs, transformDef(s.rhs)))   // preserve existing symbol s
  def transformDef[T](d: Def[T]): Def[T]    // default: use reflection 
                                            // to map over case classes
}
\end{listing}

An implementation is straightforward:
\begin{listing}
trait MySimpleTransformer extends SimpleTransformer {
  val IR: IfThenElseExp
  import IR._
  // override transformDef for each Def subclass
  def transformDef[T](d: Def[T]): Def[T] = d match {
    case IfThenElse(c,a,b) => 
      IfThenElse(transformExp(c), transformBlock(a), transformBlock(b))
    case _ => super.transformDef(d)
  }
}
\end{listing}


## Transformation by Iterated Staging
\label{sec:310treeTrans}

Another option that is more principled and in line with the idea of making
compiler transforms programmable through the use of staging
is to traverse the old program and create a new program. Effectively we
are implementing an IR interpreter that executes staging commands,
which greatly simplifies the implementation of the transform and
removes the need for low-level IR manipulation.

In the implementation, we will create new symbols instead of reusing existing
ones so we need to maintain a substitution that maps old to new Syms.
The core implementation is given below:
\begin{listing}
trait ForwardTransformer extends ForwardTraversal {
  val IR: Expressions
  import IR._
  var subst: Map[Exp[_],Exp[_]]
  def transformExp[T](s: Exp[T]): Exp[T] = ... // lookup s in subst
  def transformDef[T](d: Def[T]): Exp[T]       // default
  def transformStm[T](s: Stm[T]): Exp[T] = { 
    val e = transformDef(s.rhs); subst += (s.sym -> e); e
  }
  override def traverseStm[T](s: Stm[T]): Unit = { 
    transformStm(s)
  }
  def reflectBlock[T](b: Block[T]): Exp[T] = withSubstScope { 
    traverseBlock(b); transformExp(b.res)
  }
  def transformBlock[T](b: Block[T]): Block[T] = {
    reifyBlock(reflectBlock(b))  
  }  
}
\end{listing}

Here is a simple identity transformer implementation for conditionals
and array construction:
\begin{listing}
trait MyTransformer extends ForwardTransformer {
  val IR: IfThenElseExp with ArraysExp
  import IR._
  def transformDef[T](d: Def[T]): Exp[T] = d match {
    case IfThenElse(c,a,b) => 
      __ifThenElse(transformExp(c), reflectBlock(a), reflectBlock(b))
    case ArrayFill(n,i,y) => 
      arrayFill(transformExp(n), { j => withSubstScope(i -> j) { reflectBlock(y) }})
    case _ => ...
  }
}
\end{listing}

The staged transformer facility can be extended slightly to translate not only within a single 
language but also between two languages:
\begin{listing}
trait FlexTransformer {
  val SRC: Expressions
  val DST: Base
  trait TypeTransform[A,B]
  var subst: Map[SRC.Exp[_],DST.Rep[_]]
  def transformExp[A,B](s: SRC.Exp[A])(implicit t: TypeTransform[A,B]): DST.Rep[B]
}
\end{listing}

It is also possible to add more abstraction on top of the base transforms to build 
combinators for rewriting strategies in the style of Stratego \cite{spoofax} or 
Kiama \cite{DBLP:conf/gttse/Sloane09}.


# Problem: Phase Ordering

This all works but is not completely satisfactory. 
With fine grained separate transformations
we immediately run into phase ordering problems \cite{veldhuizen:combiningoptimizations,click95combineanalysis}. 
We could execute optimization passes in a loop until we reach a fixpoint but even then
we may miss opportunities if the program contains loops. For best
results, optimizations need to be tightly integrated. Optimizations need a different
mechanisms than lowering transformations that have a clearly defined 
before and after model.
In the next chapter, we will thus consider a slightly different IR 
representation.




# (Chapter 2) Intermediate Representation: Graphs
\label{chap:320graphs}

To remedy phase ordering problems and overall allow for more flexibility in rearranging program pieces, 
we switch to a program representation based on structured graphs. This representation is not to be
confused with control-flow graphs (CFGs): Since one of our main goals is parallelization, a sequential CFG
would not be a good fit.




# Purely Functional Subset
\label{sec:303purefun}


Let us first consider a purely functional language subset. There are much more possibilities for aggressive optimizations. 
We can rely on referential transparency: The value of an expression is always the same, no matter when and where it is computed. 
Thus, optimizations do not need to check availability or lifetimes of expressions.
Global common subexpression elimination (CSE), pattern rewrites, dead code elimination (DCE) and code motion
are considerably simpler than the usual implementations for imperative programs.

We switch to a ''sea of nodes''-like \cite{DBLP:conf/irep/ClickP95} representation that is a directed 
(and for the moment, acyclic) graph:
\begin{listing}
trait Expressions {
  // expressions (atomic)
  abstract class Exp[T]
  case class Const[T](x: T) extends Exp[T]
  case class Sym[T](n: Int) extends Exp[T]
  def fresh[T]: Sym[T]

  // definitions (composite, subclasses provided by other traits)
  abstract class Def[T]
  
  // blocks -- no direct links to statements
  case class Block[T](res: Exp[T])
  
  // bind definitions to symbols automatically
  // by creating a statement
  implicit def toAtom[T](d: Def[T]): Exp[T] = 
    reflectPure(d)

  def reifyBlock[T](b: =>Exp[T]): Block[T]
  def reflectPure[T](d: Def[T]): Sym[T] =
    findOrCreateDefinition(d)
  
  def findDefinition[T](s: Sym[T]): Option[Def[T]]
  def findDefinition[T](d: Def[T]): Option[Sym[T]]
  def findOrCreateDefinition[T](d: Def[T]): Sym[T]
}
\end{listing}

It is instructive to compare the definition of trait \code{Expressions}
with the one from the previous Chapter~\ref{chap:310trees}. 
Again there are three categories of objects involved: expressions, 
which are atomic (subclasses of \code{Exp}: constants and symbols; with
a ''gensym'' operator \code{fresh} to create fresh symbols),
definitions, which represent composite operations (subclasses of 
\code{Def}, to be provided by other components), and blocks, which
model nested scopes.

Trait \code{Expressions} now provides methods to 
find a definition given a symbol or vice versa. 
Direct links between blocks and statements are removed.
The actual graph nodes are \code{(Sym[T], Def[T])} pairs. 
They need not be accessible to clients at this level.
Thus method \code{reflectStm} from the previous chapter is
replaced by \code{reflectPure}.

Graphs also carry nesting information (boundSyms, see below). 
This enables code motion for different kinds of nested expressions such as lambdas, 
not only for loops or conditionals.
The structured graph representation is also more appropriate for parallel 
execution than the traditional sequential control-flow graph. Pure 
computation can float freely in
the graph and can be scheduled for 
execution anywhere.


# Modularity: Adding IR Node Types

The object language implementation code is the same compared to the tree representation:
\begin{listing}
trait BaseExp extends Base with Expressions {
  type Rep[T] = Exp[T]
}
\end{listing}
Again, we have separate traits, one for each unit of functionality:
\begin{listing}
trait ArithExp extends BaseExp with Arith {
  case class Plus(x: Exp[Double], y: Exp[Double]) extends Def[Double]
  case class Times(x: Exp[Double], y: Exp[Double]) extends Def[Double]
  def infix_+(x: Rep[Double], y: Rep[Double]) = Plus(x, y)
  def infix_*(x: Rep[Double], y: Rep[Double]) = Times(x, y)
  ...
}
trait IfThenElseExp extends BaseExp with IfThenElse {
  case class IfThenElse(c: Exp[Boolean], a: Block[T], b: Block[T]) extends Def[T]
  def __ifThenElse[T](c: Rep[Boolean], a: =>Rep[T], b: =>Rep[T]): Rep[T] =
    IfThenElse(c, reifyBlock(a), reifyBlock(b))
}
\end{listing}


# Simpler Analysis and More Flexible Transformations

Several optimizations are very simple to implement on 
this purely functional graph IR. The implementation draws inspiration
from previous work on compiling embedded DSLs \cite{DBLP:conf/saig/ElliottFM00,DBLP:conf/dsl/LeijenM99}
as well as staged FFT kernels \cite{DBLP:conf/emsoft/KiselyovST04}.

## Common Subexpression Elimination/Global Value Numbering
\label{sec:320cse}

Common subexpressions are eliminated during IR construction using
hash consing:
\begin{listing}
def findOrCreateDefinition[T](d: Def[T]): Sym[T]
\end{listing}

Invoked by \code{reflectPure} through the implicit conversion 
method \code{toAtom}, this method converts
a definition to an atomic expression and links it to
the scope being built up by the innermost enclosing
\code{reifyBlock} call. When the definition is known to be
side-effect free, it will search the already 
encountered definitions for a structurally equivalent one. 
If a matching previous definition is found, its symbol
will be returned, possibly moving the definition to
a parent scope to make it accessible. 
If the definition may have side effects or it is seen 
for the first time,
it will be associated with a fresh symbol and saved
for future reference. 
This simple scheme provides a powerful
global value numbering optimization \cite{DBLP:conf/pldi/Click95} 
that effectively prevents generating duplicate code.


## Pattern Rewrites

Using \code{findDefinition}, we can implement an extractor object \citep{DBLP:conf/ecoop/EmirOW07}
that enables pattern matching on a symbol to lookup
the underlying definition associated to the symbol:
\begin{listing}
object Def {
  def unapply[T](s: Exp[T]): Option[Def[T]] = s match {
    case s: Sym[T] => findDefinition(s)
    case _ => None
  }
}
\end{listing}

This extractor object can be used to implement smart 
constructors for IR nodes that deeply inspect their arguments:
\begin{listing}
def infix_*(x: Exp[Double], y: Exp[Double]) = (x,y) match {
  case (Const(x), Const(y)) => Const(x * y)
  case (Const(k), Def(Times(Const(l), y))) => Const(k * l) * y
  case _ => Times(x,y)
}
\end{listing}

Smart constructors are a simple yet powerful rewriting facility.
If the smart constructor is the only way to construct \code{Times}
nodes we obtain a strong guarantee: No \code{Times} node is ever
created without applying all possible rewrites first.


## Modularity: Adding new Optimizations
\label{sec:308addOpts}

Some profitable optimizations, such as the global value numbering
described above, are very generic.
Other optimizations apply only to specific aspects of functionality,
for example particular implementations of constant folding (or more generally
symbolic rewritings) such as replacing computations like
\code{x * 1.0} with \code{x}.
Yet other optimizations are specific to the actual program being staged.
Kiselyov et al.\ \cite{DBLP:conf/emsoft/KiselyovST04} describe 
a number of rewritings that are particularly
effective for the patterns of code generated by a staged FFT algorithm
but not as much for other programs. The FFT example is discussed 
in more detail in Section~\ref{sec:Afft}.

What we want to achieve again is modularity, so that
optimizations can be combined in a way that is most useful for a given task. 
To implement a particular rewriting rule (whether specific or generic),
say, \code{x * 1.0} $\rightarrow$ \code{x}, we can provide
a specialized implementation of \code{infix_*} (overriding the one in trait \code{ArithExp})
that will test its arguments for a particular pattern.
How this can be done in a modular way is shown by the
traits \code{ArithExpOpt} and \code{ArithExpOptFFT},
which implement some generic and program specific optimizations.
Note that the use of \code{x*y} within
the body of \code{infix_*} will apply the optimization recursively.

The appropriate pattern is to override the smart constructor in a separate 
trait and call the super implementation if no rewrite matches. This decouples 
optimizations from node type definitions.
\begin{listing}
trait ArithExpOpt extends ArithExp {
  override def infix_*(x:Exp[Double],y:Exp[Double]) = (x,y) match {
    case (Const(x), Const(y)) => Const(x * y)
    case (x, Const(1)) => x
    case (Const(1), y) => x
    case _ => super.infix_*(x, y)
  }
}
trait ArithExpOptFFT extends ArithExp {
  override def infix_*(x:Exp[Double],y:Exp[Double]) = (x,y) match {
    case (Const(k), Def(Times(Const(l), y))) => Const(k * l) * y
    case (x, Def(Times(Const(k), y))) => Const(k) * (x * y))
    case (Def(Times(Const(k), x)), y) => Const(k) * (x * y))
    ...
    case (x, Const(y)) => Const(y) * x
    case _ => super.infix_*(x, y)
  }
}
\end{listing}
Note that the trait linearization order defines the rewriting strategy.
We still maintain our guarantee that no \code{Times} node could be rewritten further.

\begin{figure*}\centering
  \includegraphics[width=\textwidth]{papers/cacm2012/figoverview.pdf}
  \caption{\label{fig:overview}Component architecture. Arrows denote extends relationships, 
  dashed boxes represent units of functionality.}
  \vspace{1cm}
\end{figure*}

Figure~\ref{fig:overview} shows the component architecture formed by base traits and 
corresponding optimizations.


## Context- and Flow-Sensitive Transformations

Context and flow sensitive transformation become very important
once we introduce effects. But even pure functional programs can profit 
from context information:
\begin{listing}
if (c) { if (c) a else b } else ...
\end{listing}
The inner test on the same condition is redundant and will
always succeed. How do we detect this situation?
In other cases we can use the Def extractor to lookup the definition
of a symbol. This will not work here, because Def works on Exp input and
produces a Def object as output. 
We however need to work on the level of Exps, turning a Sym into \code{Const(true)}
based on context information.

We need to adapt the way we construct IR nodes. 
When we enter the then branch, we add \code{c$\rightarrow$Const(true)} to
a substitution. This substitution needs to be applied 
to arguments of IR nodes constructed within the then branch. 

One possible solution would be add yet another type constructor, \code{Ref}, 
with an implicit conversion from Exp to Ref that applies the substitution.
A signature like \code{IfThenElse(c: Exp, ...)} would become \code{IfThenElse(c: Ref, ...)}.
A simpler solution is to implement \code{toAtom} in such a way that it 
checks the resulting Def if any of its
inputs need substitution and if so invoke \code{mirror} (see below) on the result 
Def, which will apply the substitution, call the appropriate smart constructor
and finally call \code{toAtom} again with the transformed result.


## Graph Transformations

In addition to optimizations performed during graph constructions, we
can also implement transformation that work directly on the graph
structure. This is useful if 
we need to do analysis on a larger portion of the program first and only
then apply the transformation. An example would be to find all \code{FooBar} 
statements in a graph, and replace them uniformly with \code{BarBaz}.
All dependent statements should re-execute
their pattern rewrites, which might trigger on the new \code{BarBaz}
input. 

We introduce the concept of \emph{mirroring}: Given an IR node, we want to
apply a substitution (or generally, a \code{Transformer}) to the arguments and 
call the appropriate smart constructor again.
For every IR node type we require a default \code{mirror} implementation 
that calls back its smart constructor:
\begin{listing}
override def mirror[A](e: Def[A], f: Transformer): Exp[A] = e match {
  case Plus(a,b) => f(a) + f(b) // calls infix_+
  case Times(a,b) => f(a) * f(b)
  case _ => super.mirror(e,f)
}
\end{listing}

There are some restrictions if we are working directly on the graph level: In
general we have no (or only limited) context information because a single
IR node may occur multiple times in the final program. Thus, attempting to simplify
effectful or otherwise context-dependent expressions will produce wrong results 
without an appropriate context.
For pure expressions, a smart constructor called from \code{mirror} should not 
create new symbols apart from the result and it should not call reifyBlock. 
Otherwise, if we were creating new symbols when 
nothing changes, the returned symbol could not be used to check 
convergence of an iterative transformation easily. 

The \code{Transfomer} argument to \code{mirror} can be 
queried to find out whether \code{mirror} is allowed to call context
dependent methods:
\begin{listing}
override def mirror[A](e: Def[A], f: Transformer): Exp[A] = e match {
  case IfThenElse(c,a,b) => 
    if (f.hasContext)
      __ifThenElse(f(c),f.reflectBlock(a),f.reflectBlock(b))
    else
      ifThenElse(f(c),f(a),f(b)) // context-free version
  case _ => super.mirror(e,f)
}
\end{listing}
If the context is guaranteed to be set up correctly, we
call the regular smart constructor and use \code{f.reflectBlock} to
call mirror recursively on the contents of blocks \code{a} and \code{b}. 
Otherwise, we call a more restricted context free method.






## Dead Code Elimination

Dead code elimination can be performed purely on the graph level, simply by finding all statements 
reachable from the final result and discarding everything else.

We define a method to find all symbols a given object references directly:
\begin{listing}
def syms(x: Any): List[Sym[Any]]
\end{listing}
If \code{x} is a Sym itself, \code{syms(x)} will return \code{List(x)}. For a case class instance 
that implements the \code{Product} interface such as \code{Times(a,b)}, it will return \code{List(a,b)} if both
\code{a} and \code{b} are Syms. Since the argument type is \code{Any}, we can apply \code{syms} not 
only to Def objects directly but also to lists of Defs, for example.

Then, assuming \code{R} is the final program result, the set of remaining symbols in the 
graph \code{G} is the least fixpoint of: 
\begin{listing}
G = R $\cup$ syms(G map findDefinition)
\end{listing}
Dead code elimination will discard all other nodes.

# From Graphs Back to Trees

To turn program graphs back into trees for code generation we have to decide
which graph nodes should go where in the resulting program. This is the task
of code motion.

## Code Motion
\label{sec:320codemotion}

Other optimizations can apply transformations optimistically and need not worry 
about maintaining a correct schedule: Code motion will fix it up. 
The algorithm will try to push statements inside conditional branches and hoist statements
out of loops. Code motion depends on dependency and frequency information but not directly on
data-flow information. Thus it can treat functions or other user defined compound statements
in the same way as loops. This makes 
our algorithm different from code motion algorithms based on data flow 
analysis such as Lazy Code Motion (LCM, \cite{DBLP:conf/pldi/KnoopRS92}) or Partial Redundancy Elimination (PRE, \cite{DBLP:journals/toplas/KennedyCLLTC99}).

The graph IR reflects ''must before'' (ordering) and ''must inside'' (containment) relations,
as well as anti-dependence and frequency. These relations are implemented by the
following methods, which can be overridden for new definition classes:
\begin{listing}
def syms(e: Any): List[Sym[Any]]        // value dependence (must before)
def softSyms(e: Any): List[Sym[Any]]    // anti dependence (must not after)
def boundSyms(e: Any): List[Sym[Any]]   // nesting dependence (must not outside)
def symsFreq(e: Any): List[(Sym[Any],   // frequency information (classify
  Double)]                              // sym as 'hot', 'normal', 'cold')
\end{listing}
To give an example, \code{boundSyms} applied to a loop node \code{RangeForeach(range,idx,body)}
with index variable \code{idx} would return \code{List(idx)} to denote that
\code{idx} is fixed ''inside'' the loop expression. 

Given a subgraph and a list of result nodes, the goal is to identify the graph 
nodes that should form the ''current'' level, as opposed to those that should 
remain in some ''inner'' scope, to be scheduled later. We will reason about the
paths on which statements can be reached from the result.
The first idea is to retain all nodes on the current level that are reachable
on a path that does not cross any conditionals, i.e.\ that has no ''cold'' refs.
Nodes only used from conditionals will be pushed down. However, this
approach does not yet reflect the precedence of loops. If a loop is top-level, 
then conditionals inside the loop (even if deeply nested) should not prevent 
hoisting of statements. So we refine the characterization to retain all
nodes that are reachable on a path that does not cross top-level conditionals.


This leads to a simple iterative algorithm (see Figure~\ref{fig:codemotion2}): 
Starting with the known top level statements, nodes reachable via normal
links are added and for each hot ref, we follow nodes that are forced inside
until we reach one that can become top-level again.


\begin{figure}
\begin{center}
\includegraphics[width=0.7\textwidth]{fig_graph_nesting.pdf}
\end{center}
\caption{\label{fig:codemotion}Graph IR with regular and nesting edges (boundSyms, dotted line) as
used for code motion.}
\end{figure}

%% ALGO begins here:
\begin{figure}
\begin{center}\begin{minipage}{10cm}
Code Motion Algorithm: Compute the set $L$ of top level statements for the current block, from a set of available statements $E$, a set of forced-inside statements $G \subseteq E$ and a block result $R$.
\begin{enumerate}
\item Start with $L$ containing the known top level statements, initially the (available) block result $R \cap E$.

\item Add to $L$ all nodes reachable from $L$ via normal links (neither hot nor cold) through $E-G$ (not forced inside).

\item For each hot ref from $L$ to a statement in $E-L$, follow any links through $G$, i.e.\ the nodes that are forced inside, if there are any. The first non-forced-inside nodes (the ''hot fringe'') become top level as well (add to $L$). 

\item Continue with 2 until a fixpoint is reached.
\end{enumerate}
\end{minipage}
\end{center}
\caption{\label{fig:codemotion2}Code Motion algorithm.}
\end{figure}

To implement this algorithm, we need to determine the set \code{G} of nodes that are
forced inside and may not be part of the top level. 
We start with the block result \code{R} and a graph \code{E} that has all unnecessary 
nodes removed (DCE already performed):
\begin{listing}
E = R $\cup$ syms(E map findDefinition)
\end{listing}

We then need a way to find all uses of a given symbol \code{s}, 
up to but not including the node where the symbol is bound:
\begin{listing}
U(s) = {s} $\cup$ { g $\in$ E | syms(findDefinition(g)) $\cap$ U(s) $\ne\emptyset$ 
                      && s $\notin$ boundSyms(findDefinition(g))) }
\end{listing}

We collect all bound symbols and their dependencies. These cannot live on the current level, they
are forced inside:
\begin{listing}
B = boundSyms (E map findDefinition)
G = union (B map U)    // must inside
\end{listing}
Computing \code{U(s)} for many symbols \code{s} individually is costly but
implementations can exploit considerable sharing to optimize the computation of
\code{G}.

The iteration in Figure~\ref{fig:codemotion2} uses \code{G} to follow forced-inside 
nodes after a hot ref until a node is found that can be moved to the top level.


Let us consider a few examples to build some intuition about the code motion behavior.
In the code below, the starred conditional is on the fringe (first statement that
can be outside) and on a hot path (through the loop). Thus it will be hoisted.
Statement \code{foo} will be moved inside:
\begin{listing}
loop { i =>                z = *if (x) foo
  if (i > 0)               loop { i =>
    *if (x)                  if (i > 0)
      foo                      z
}                          }
\end{listing}

The situation changes if the inner conditional is forced inside by a value dependency. 
Now statement \code{foo} is on the hot fringe and becomes top level.
\begin{listing}
loop { i =>                z = *foo
  if (x)                   loop { i =>
    if (i > 0)               if (x)
      *foo                     if (i > 0)
}                                z
                           }
\end{listing}

For loops inside conditionals, the containing statements will be moved inside (relative
to the current level).
\begin{listing}
if (x)                     if (x)
  loop { i =>                z = foo
    foo                      loop { i =>
  }                            z
                             }
\end{listing}


### Pathological Cases

The described algorithm works well and is reasonably efficient in practice. 
Being a heuristic, it cannot be optimal in all cases. Future versions 
could employ more elaborate cost models instead of
the simple hot/cold distinction. One case worth mentioning is when a 
statement is used only in conditionals but in different conditionals:
\begin{listing}
z = foo                    if (x)
if (x)                       foo
  z                        if (y)
if (y)                       foo
  z
\end{listing}
In this situation \code{foo} will be duplicated. Often this duplication is
beneficial because \code{foo} can be optimized together with other 
statements inside 
the branches.
In general of course there is a danger of slowing down the program
if both conditions are likely to be true at the same time. In that case
it would be a good idea anyways to restructure the program to factor out 
the common criteria into a separate test.


### Scheduling

Once we have determined which statements should occur on which level, 
we have to come up with an ordering for the statements. Before starting
the code motion algorithm, we sort the input graph in topological order
and we will use the same order for the final result.
For the purpose of sorting, we include anti-dependencies in the topological
sort although they are disregarded during dead code elimination. 
A bit of care must be taken though: If we introduce loops or recursive 
functions the graph can be cyclic, in which case
no topological order exists. However, cycles are caused only by inner nodes
pointing back to outer nodes and for sorting purposes we can remove these
back-edges to obtain an acyclic graph.


## Tree-Like Traversals and Transformers

To generate code or to perform transformation by iterated staging (see Section~\ref{sec:310treeTrans})
we need to turn our graph back into a tree.
The interface to code motion allows us to build a generic tree-like traversal
over our graph structure: 
\begin{listing}
trait Traversal {
  val IR: Expressions; import IR._
  // perform code motion, maintaining current scope
  def focusExactScope(r: Exp[Any])(body: List[Stm[Any]] => A): A    
  // client interface
  def traverseBlock[T](b: Block[T]): Unit =
    focusExactScope(b.res) { levelScope =>
      levelScope.foreach(traverseStm)
    }
  def traverseStm[T](s: Stm[T]): Unit = blocks(s).foreach(traverseBlock)
}
\end{listing}
This is useful for other analyses as well, but in particular for
building transformers that traverse one graph
in a tree like fashion and create another graph analogous to
Section~\ref{sec:310treeTrans}. The implementation of
trait \code{ForwardTransformer} carries over almost
unmodified.








# Effects
\label{sec:321}

To ensure that operations can be safely moved around (and for other optimizations as well),
a compiler needs to reason about their possible side effects. The graph representation presented so far is pure
and does not mention effects at all.
However all the necessary ingredients are already there: We can keep track of side effects simply by making 
effect dependencies explicit in the graph.
In essence, we turn all programs into functional programs by adding an invisible state parameter (similar
in spirit but not identical to SSA conversion).

## Simple Effect Domain

We first consider global effects like console output via \code{println}. Distinguishing only between
''has no effect'' and ''may have effect'' means that all operations on mutable data structures, 
including reads, have to be serialized along with all other side effects.

By default, we assume operations to be pure (i.e.\ side-effect free).
Programmers can designate effectful operations by using \code{reflectEffect} instead of
the implicit conversion \code{toAtom} which internally delegates to \code{reflectPure}.
Console output, for example, is implemented like this:
\begin{listing}
def print(x: Exp[String]): Exp[Unit] = reflectEffect(Print(x))
\end{listing}

The call to \code{reflectEffect} adds the passed IR node to a list of effects for the 
current block. Effectful expressions will attract dependency edges between them to 
ensure serialization. 
A compound expression such as a loop or a conditional will internally use \code{reifyBlock},
which attaches nesting edges to the effectful nodes contained in the block.

Internally, \code{reflectEffect} creates \code{Reflect} nodes that keep track
of the context dependencies:
\begin{listing}
var context: List[Exp[Any]]
case class Reflect[T](d: Def[T], es: List[Sym[Any]]) extends Def[T]
def reflectEffect[T](d: Def[T]): Exp[T] = createDefinition(Reflect(d, context)).sym
\end{listing}
The context denotes the ''current state''. Since state can be seen as an abstraction of effect
history, we just define context as a list of the previous effects.

In this simple model, all effect dependencies are uniformly encoded in the IR graph. 
Rewriting, CSE, DCE, and Code Motion are disabled for effectful
statements (very pessimistic). Naturally we would like something more 
fine grained for mutable data.


## Fine Grained Effects: Tracking Mutations per Allocation Site

We can add other, more fine grained, variants of \code{reflectEffect} which
allow tracking mutations per allocation site or other, more general abstractions
of the heap that provide a partitioning into regions. Aliasing and sharing of
heap objects such as arrays can be tracked via optional annotations on IR
nodes. Reads and writes of mutable objects are automatically serialized and
appropriate dependencies inserted to guarantee a legal execution schedule.

Effectful statements are tagged with an effect summary that further describes the effect.
The summary can be extracted via \code{summarizeEffects}, and 
there are some operations on summaries (like \code{orElse}, \code{andThen}) to 
combine effects.
As an example consider the definition of conditionals, which computes the
compound effect from the effects of the two branches:
\begin{listing}
def __ifThenElse[T](cond: Exp[Boolean], thenp: => Rep[T], elsep: => Rep[T]) {
  val a = reifyBlock(thenp)
  val b = reifyBlock(elsep)
  val ae = summarizeEffects(a) // get summaries of the branches
  val be = summarizeEffects(b) 
  val summary = ae orElse be   // compute summary for whole expression
  reflectEffect(IfThenElse(cond, a, b), summary)  // reflect compound expression
                                                  // (effect might be none, i.e. pure)
}
\end{listing}

To specify effects more precisely for different kinds of IR nodes, we add 
further \code{reflect} methods:
\begin{listing}
reflectSimple     // a 'simple' effect: serialized with other simple effects
reflectMutable    // an allocation of a mutable object; result guaranteed unique
reflectWrite(v)   // a write to v: must refer to a mutable allocation 
                  // (reflectMutable IR node)
reflectRead(v)    // a read of allocation v (not used by programmer, 
                  // inserted implicitly)
reflectEffect(s)  // provide explicit summary s, specify may/must info for 
                  // multiple reads/writes
\end{listing}

The framework will serialize reads and writes so to respect data and anti-dependency with respect 
to the referenced allocations. To make this work we also need to keep track of sharing and 
aliasing. Programmers can provide for their IR nodes 
a list of input expressions which the result of the IR node may 
alias, contain, extract from or copy from. 
\begin{listing}
def aliasSyms(e: Any): List[Sym[Any]]
def containSyms(e: Any): List[Sym[Any]]
def extractSyms(e: Any): List[Sym[Any]]
def copySyms(e: Any): List[Sym[Any]]
\end{listing}

These four pieces of information correspond to the possible pointer 
operations \code{x = y}, \code{*x = y}, \code{x = *y} and \code{*x = *y}. 
Assuming an operation \code{y = Foo(x)}, \code{x} should be returned in the following cases:
\begin{listing}
x $\in$ aliasSyms(y)      if y = x      // if then else
x $\in$ containSyms(y)    if *y = x     // array update
x $\in$ extractSyms(y)    if y = *x     // array apply
x $\in$ copySyms(y)       if *y = *x    // array clone
\end{listing}
Here, \code{y = x} is understood as ''y may be equal to x'', 
\code{*y = x} as ''dereferencing y (at some index) may return x'' etc.





### Restricting Possible Effects

It is often useful to restrict the allowed effects somewhat to
make analysis more tractable and provide better optimizations.
One model, which works reasonably well for many applications, 
is to prohibit sharing and aliasing between
mutable objects. Furthermore, read and write operations must 
unambiguously identify the allocation site of the object being 
accessed.
The framework uses the aliasing and points-to information to
enforce these rules and to keep track of immutable objects that 
point to mutable data. This is to make sure the right serialization 
dependencies and \code{reflectRead} calls are inserted for
operations that may reference mutable state in an
indirect way.





# (Chapter 3) Advanced Optimizations
\label{chap:330opt}

We have seen above in Chaper~\ref{chap:320graphs} how many classic compiler 
optimizations can be applied to the IR generated from embedded programs 
in a straightforward way. 
Due to the structure of the IR, these optimizations all
operate in an essentially global way, at the level of domain operations.
In this chapter we discuss some other advanced optimizations that can be implemented on
the graph IR. We present more elaborate examples for how these optimizations benefit 
larger use cases later in Part~\ref{part:P3}.


# Rewriting

Many optimizations that are traditionally implemented using an iterative
dataflow analysis followed by a transformation pass can also be expressed
using various flavors of rewriting. Whenever possible we tend to prefer
the rewriting version because rewrite rules are easy to specify
separately and do not require programmers to define 
abstract interpretation lattices.

## Context-Sensitive Rewriting

Smart constructors in our graph IR can be context sensitive. For example,
reads of local variables examine the current effect context
to find the last assignment, implementing
a form of copy propagation (middle):
\begin{listing}
var x = 7     var x = 7    println(5)
x = 5         x = 5
println(x)    println(5)
\end{listing}
This renders the stores dead, and they will be removed
by dead code elimination later (right).

## Speculative Rewriting: Combining Analyses and Transformations

Many optimizations are mutually beneficial.  In the presence of loops,
optimizations need to make optimistic assumptions for the supporting analysis
to obtain best results.  If multiple analyses are run separately, each of them
effectively makes pessimistic assumptions about the outcome of all others.
Combined analyses avoid the phase ordering problem by solving everything at the
same time. Lerner, Grove, and Chambers showed a method of composing
optimizations by interleaving analyses and transformations
\cite{lerner02composingdataflow}.  We use a modified version of their algorithm that
works on structured loops instead of CFGs and using dependency information and
rewriting instead of explicit data flow lattices. Usually, rewriting is
semantics preserving, i.e.\ pessimistic. The idea is to drop that assumption.
As a corollary, we need
to rewrite speculatively and be able to rollback to a previous state to get
optimistic optimization. The algorithm proceeds as follows: for each
encountered loop, apply all possible transforms to the loop body, given empty
initial assumptions.  Analyze the result of the transformation: if any new
information is discovered throw away the transformed loop body and retransform
the original with updated assumptions.  Repeat until the analysis result has
reached a fixpoint and keep the last transformation as result.

Here is an example of speculative rewriting, showing the initial optimistic 
iteration (middle), with the fixpoint (right) reached after the second iteration:
\begin{listing}
var x = 7                 var x = 7          var x = 7 //dead
var c = 0                 var c = 0          var c = 0
while (c < 10) {          while (true) {     while (c < 10) {
  if (x < 10) print("!")    print("!")         print("!")
  else x = c                print(7)           print(7)
  print(x)                  print(0)           print(c)
  print(c)                  c = 1              c += 1
  c += 1                  }                  }
}
\end{listing}
This algorithm allows us to do all forward data flow analyses and transforms in
one uniform, combined pass driven by rewriting. In the example above, during the initial 
iteration (middle), separately
specified rewrites for variables and conditionals work together to 
determine that \code{x=c} is never executed. At the end of the loop body we
discover the write to \code{c}, which invalidates our initial optimistic
assumption \code{c=0}.  We rewrite the original body again with the augmented
information (right).  This time there is no additional knowledge discovered so
the last speculative rewrite becomes the final one.  


## Delayed Rewriting and Multi-Level IR
\label{sec:330delayed}

For some transformations, e.g.\ data structure representation lowering, we
do not execute rewrites now, but later, to give further immediate
rewrites a change to match on the current expression before
it is rewritten. This is a simple form of prioritizing different
rewrites, in this case optimizations over lowerings. It also
happens to be a central idea behind telescoping 
languages \cite{kennedy05telescoping}.

We perform simplifications eagerly, after each transform phase.
Thus we guarantee that CSE, DCE etc. have been applied on
high-level operations before they are translated into
lower-level equivalents, on which optimizations would
be much harder to apply.

We call the mechanism to express this form of rewrites
\emph{delayed} rewriting. Here is an example that delayedly 
transforms a plus operation on Vectors into an operation on arrays:
\begin{listing}
def infix_plus(a: Rep[Vector[Double]], b: Rep[Vector[Double]]) = {
  VectorPlus(a,b) atPhase(lowering) {
    val data = Array.fill(a.length) { i => a(i) + b(i) }
    vector_with_backing_array(data)
  }
}
\end{listing}
The transformation is only carried out at phase \code{lowering}.
Before that, the IR node remains a \code{VectorPlus} node, which
allows other smart constructor rewrites to kick in that  
match their arguments against \code{VectorPlus}.

Technically, delayed rewriting is implemented using a worklist
transformer that keeps track of the rewrites to be
performed during the next iteration. The added convenience
over using a transformer directly is that programmers can
define simple lowerings inline without needing to subclass
and install a transformer trait.




# Splitting and Combining Statements

Since our graph IR contains structured expressions, optimizations need to 
work with compound statements. Reasoning about compound statements is not
easy: For example, our simple dead code elimination algorithm will not 
be able to remove only pieces of a compound expression. Our solution
is simple yet effective: We eagerly split many kinds of compound
statements, assuming optimistically that only parts will be needed.
We find out which parts through the regular DCE algorithm.
Afterwards we reassemble the remaining pieces. 

## Effectful Statements

A good example of statement splitting are effectful conditionals:
\begin{listing}
var a, b, c = ...      var a, b, c = ...      var a, c = ...
if (cond) {            if (cond)              if (cond)
  a = 9                  a = 9                  a = 9
  b = 1                if (cond)              else
} else                   b = 1                  c = 3
  c = 3                if (!cond)             println(a+c)
println(a+c)             c = 3
                       println(a+c)     
\end{listing}
From the conditional in the initial program (left), splitting creates
three separate expressions, one for each referenced variable (middle).
Pattern rewrites are executed when building the split nodes but
do not have any effect here.
Dead code elimination removes the middle one because variable b is
not used, and the remaining conditionals are merged back together (right).
Of course successful merging requires to keep track
of how expressions have been split.







## Data Structures
\label{sec:361struct}

Splitting is also very effective for data structures, as often only parts of
a data structure are used or modified.
We can define a generic framework for data structures:
\begin{listing}
trait StructExp extends BaseExp {
  abstract class StructTag
  case class Struct[T](tag: StructTag, elems: Map[String,Rep[Any]]) extends Def[T]
  case class Field[T](struct: Rep[Any], key: String) extends Def[T]

  def struct[T](tag: StructTag, elems: Map[String,Rep[Any]]) = Struct(tag, elems)
  def field[T](struct: Rep[Any], key: String): Rep[T] = struct match {
    case Def(Struct(tag, elems)) => elems(index).asInstanceOf[Rep[T]]
    case _ => Field[T](struct, index)
  }
}
\end{listing}
There are two IR node types, one for structure creation and one for field access.
The structure creation node contains a hash map that holds (static) field identifiers
and (dynamic) field values. It also contains a \code{tag} that can be used to
hold further information about the nature of the data structure.
The interface for field accesses is method \code{field}, which pattern matches
on its argument and, if that is a \code{Struct} creation, looks up the desired value
from the embedded hash map.


We continue by adding a rule that makes the result 
of a conditional a \code{Struct} if the branches return \code{Struct}:
\begin{listing}
override def ifThenElse[T](cond: Rep[Boolean], a: Rep[T], b: Rep[T]) = 
(a,b) match {
  case (Def(Struct(tagA,elemsA)), Def(Struct(tagB, elemsB))) => 
    assert(tagA == tagB)
    assert(elemsA.keySet == elemsB.keySet)
    Struct(tagA, elemsA.keySet map (k => ifThenElse(cond, elemsA(k), elemsB(k))))
  case _ => super.ifThenElse(cond,a,b)
}
\end{listing}
Similar rules are added for many of the other core IR node types.
DCE can remove individual elements of the data structure that are never used.
During code generation and tree traversals, the remaining parts of the 
split conditional are merged back together.

We will study examples of this struct abstraction in Section~\ref{sec:455struct} and
an extension to unions and inheritance in Section~\ref{sec:455inherit}.

## Representation Conversion
\label{sec:360soa}

A natural extension of this mechanism is a generic array-of-struct to struct-of-array transform. 
The definition is analogous to that of conditionals. We override the array constructor \code{arrayFill} 
that represents expressions of the form \c|Array.fill(n) { i => body }|
to create a struct with an array for each component of the body if the body itself
is a Struct: 
\begin{listing}
override def arrayFill[T](size: Exp[Int], v: Sym[Int], body: Def[T]) = body match {
  case Block(Def(Struct(tag, elems))) => 
    struct[T](ArraySoaTag(tag,size), 
      elems.map(p => (p._1, arrayFill(size, v, Block(p._2)))))
  case _ => super.arrayFill(size, v, body)
}
\end{listing}
Note that we tag the result struct with an \code{ArraySoaTag} to keep track
of the transformation. This class is defined as follows:
\begin{listing}
case class ArraySoaTag(base: StructTag, len: Exp[Int]) extends StructTag
\end{listing}

We also override the methods that are used to access array elements
and return the length of an array to do the right thing for transformed
arrays:
\begin{listing}
override def infix_apply[T](a: Rep[Array[T]], i: Rep[Int]) = a match {
  case Def(Struct(ArraySoaTag(tag,len),elems)) =>
    struct[T](tag, elems.map(p => (p._1, infix_apply(p._2, i))))
  case _ => super.infix_at(a,i)
}
override def infix_length[T](a: Rep[Array[T]]): Rep[Int] = a match {
  case Def(Struct(ArraySoaTag(tag, len), elems)) => len
  case _ => super.infix_length(a)
}
\end{listing}

Examples for this struct of array transformation are shown in 
Section~\ref{sec:455structUse} and Chapter~\ref{chap:460fusionUse}. 


# Loop Fusion and Deforestation
\label{sec:360fusionComp}

\newcommand{\yield}[0]{\leftarrow}
\newcommand{\G}[0]{\mathcal{G}}

The use of independent and freely composable traversal operations such as
\code{v.map(..).sum} is preferable to explicitly coded loops. However, naive
implementations of these operations would be expensive and entail lots of
intermediate data structures.  We provide a novel loop fusion algorithm for
data parallel loops and traversals (see Chapter~\ref{chap:460fusionUse}
for examples of use). The core loop abstraction is
\begin{listing}
loop(s) $\overline{\mathtt{{x=}}\G}$ { i => $\overline{E[ \mathtt{x} \yield \mathtt{f(i)} ]}$ }
\end{listing}
where \code{s} is the size of the loop and \code{i} the loop
variable ranging over $[0,\mathtt{s})$. A loop can compute
multiple results $\overline{\mathtt{x}}$, each of which is associated
with a generator $\G$, one of \code{Collect}, which creates a flat array-like
data structure, \code{Reduce($\oplus$)}, which reduces values with
the associative operation $\oplus$, or \code{Bucket($\G$)}, which
creates a nested data structure, grouping generated values by key
and applying $\G$ to those with matching key. Loop bodies consist
of yield statements \code{x $\yield$ f(i)} that define values
passed to generators (of this loop or an outer loop), embedded
in some outer context $E[.]$ that might consist of other loops
or conditionals. For \code{Bucket} generators yield takes
(key,value) pairs.



\begin{figure}
\begin{center}
\begin{minipage}{10cm}
Generator kinds: $\mathcal{G} ::= $ \code{Collect} $|$ \code{Reduce($\oplus$)} $|$ \code{Bucket($\mathcal{G}$)} \\
Yield statement: xs $\yield$ x \\
Contexts: $E[.] ::= $ loops and conditionals \\[2em]

\emph{Horizontal case (for all types of generators):}
~\\
\begin{mlisting}
       loop(s) x1=$\G_1$ { i1 => $E_1[$ x1 $\yield$ f1(i1) $]$ }
       loop(s) y1=$\G_2$ { i2 => $E_2[$ x2 $\yield$ f2(i2) $]$ }
\end{mlisting}
\vspace{-5pt}{\hspace{6mm}\rule{9cm}{0.25pt}}
\begin{mlisting}
     loop(s) x1=$\G_1$, x2=$\G_2$ { i => 
              $E_1[$ x1 $\yield$ f1(i) $]$; $E_2[$ x2 $\yield$ f2(i) $]$ }
\end{mlisting}
~\\
\emph{Vertical case (consume collect):}
~\\
\begin{mlisting}
      loop(s) x1=Collect { i1 => $E_1[$ x1 $\yield$ f1(i1) $]$ }
    loop(x1.size) x2=$\G$ { i2 => $E_2[$ x2 $\yield$ f2(x1(i2)) $]$ }
\end{mlisting}
\vspace{-5pt}{\hspace{3mm}\rule{10cm}{0.25pt}}
\begin{mlisting}
   loop(s) x1=Collect, x2=$\G$ { i => 
                $E_1[$ x1 $\yield$ f1(i); $E_2[$ x2 $\yield$ f2(f1(i)) $]]$ }
\end{mlisting}
~\\
\emph{Vertical case (consume bucket collect):}
~\\
\begin{mlisting}
            loop(s) x1=Bucket(Collect) { i1 => 
                $E_1[$ x1 $\yield$ (k1(i1), f1(i1)) $]$ }
      loop(x1.size) x2=Collect { i2 =>  
        loop(x1(i2).size) y=$\G$ { j => 
          $E_2[$ y $\yield$ f2(x1(i2)(j)) $]$ }; x2 $\yield$ y }
\end{mlisting}
\vspace{-5pt}{\hspace{3mm}\rule{10cm}{0.25pt}}
\begin{mlisting}
    loop(s) x1=Bucket(Collect), x2=Bucket($\G$) { i => 
        $E_1[$ x1 $\yield$ (k1(i), f1(i));
            $E_2[$ x2 $\yield$ (k1(i), f2(f1(i))) $]]$ }
\end{mlisting}
\end{minipage}
\end{center}

\caption{\label{fig-fusion} Loop fusion}
\end{figure}



The fusion rules are summarized in Figure~\ref{fig-fusion}.
This model is expressive enough to model many common collection
operations:
\begin{mlisting}
x=v.map(f)     loop(v.size) x=Collect { i => x $\yield$ f(v(i)) }
x=v.sum        loop(v.size) x=Reduce(+) { i =>  x $\yield$ v(i) }
x=v.filter(p)  loop(v.size) x=Collect { i => if (p(v(i))) 
                                                x $\yield$ v(i) }
x=v.flatMap(f) loop(v.size) x=Collect { i => val w = f(v(i))
                         loop(w.size) { j => x $\yield$ w(j) }}
x=v.distinct   loop(v.size) x=Bucket(Reduce(rhs)) { i => 
                                        x $\yield$ (v(i), v(i)) }
\end{mlisting}
Other operations are accommodated by generalizing slightly. Instead of
implementing a \code{groupBy} operation that returns a sequence of
(Key, Seq[Value]) pairs we can return the keys and
values in separate data structures. The equivalent of \code{(ks,vs)=v.groupBy(k).unzip}
is:
\begin{mlisting}
loop(v.size) ks=Bucket(Reduce(rhs)),vs=Bucket(Collect) { i => 
  ks $\yield$ (v(i), v(i)); vs $\yield$ (v(i), v(i)) }
\end{mlisting}

In Figure~\ref{fig-fusion},
multiple instances of \code{f1(i)} are subject to CSE and not evaluated twice.
Substituting \code{x1(i2)} with \code{f1(i)} will remove a reference to \code{x1}.
If \code{x1} is not used anywhere else, it will also be subject to DCE.
Within fused loop bodies, unifying index variable \code{i} and substituting
references will trigger the uniform forward transformation pass.
Thus, fusion not only removes intermediate data structures but also provides
additional optimization opportunities inside fused loop bodies
(including fusion of nested loops).

Fixed size array construction \code{Array(a,b,c)} can be expressed as
\begin{listing}
loop(3) x=Collect { case 0 => x $\yield$ a 
                    case 1 => x $\yield$ b case 2 => x $\yield$ c }
\end{listing}
and concatenation \code{xs ++ ys} as \code{Array(xs,ys).flatMap(i=>i)}:
\begin{listing}
loop(2) x=Collect { case 0 => loop(xs.size) { i => x $\yield$ xs(i) } 
                    case 1 => loop(ys.size) { i => x $\yield$ ys(i) }}
\end{listing}
Fusing these patterns with a consumer will duplicate the consumer code into each match 
case. Implementations should have some kind of cutoff value to prevent code explosion.
Code generation does not need to emit actual loops for fixed array constructions
but can just produce the right sequencing of yield operations.


Examples for the fusion algorithm are shown in Section~\ref{subsec:fusion}
and Chapter~\ref{chap:460fusionUse}.


*/

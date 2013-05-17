.. contents::

From Interpreter to Compiler
============================

A staged interpreter is a compiler. This is useful, because an
interpreter is usually much easier to implement than a compiler. In
this section, we illustrate how to turn a vanilla interpreter into a
compiler, using lightweight modular staging (LMS). The gist is to let
LMS generate code for the interpreter specialized to a particular
program -- the program is fixed at staging time, while the input(s) to
the program may vary in the generated code. Hence, staging an
interpreter should be as simple as wrapping the types of expressions
that vary in ``Rep[_]`` while leaving the types of expressions we want
specialized as is.

As a case study, we stage a simple regular expression matcher. Our
vanilla regular expression matcher is invoked on a regular expression
string and an input string. The staged regular expression matcher is
invoked on a regular expression constant string and a staged input
string of type ``Rep[String]``, and generates code specialized to match
any input string against the constant regular expression pattern.

We could further optimize the generated code by additional staged
transformations, but here, we only illustrate the basic process of
staging an interpreter. This process is widely applicable. For
example, we used the same process to stage a bytecode interpreter into
a bytecode compiler.

Regular Expression Matcher
--------------------------

We start with a small regular expression matcher, ported to Scala from
`a C version, written by Rob Pike and Brian Kernighan
<http://www.cs.princeton.edu/courses/archive/spr09/cos333/beautiful.html>`_.

Vanilla Interpreter
```````````````````

.. includecode:: ../test/scala/lms/tutorial/re.scala
   :include: matchsearch

.. includecode:: ../test/scala/lms/tutorial/re.scala
   :include: matchhere

.. includecode:: ../test/scala/lms/tutorial/re.scala
   :include: matchstar

.. includecode:: ../test/scala/lms/tutorial/re.scala
   :include: matchchar

Staged Interpreter
``````````````````

The staged interpreter simply consist in wrapping the variable
parameters in ``Rep[_]`` types. Otherwise, the code is the same.

.. includecode:: ../test/scala/lms/tutorial/sre.scala
   :include: matchsearch

.. includecode:: ../test/scala/lms/tutorial/sre.scala
   :include: matchhere

.. includecode:: ../test/scala/lms/tutorial/sre.scala
   :include: matchstar

.. includecode:: ../test/scala/lms/tutorial/sre.scala
   :include: matchchar

Generated Code
``````````````

As an example, here is the code generated for `^ab`.

.. includecode:: ../out/sre__bab.check.scala
   :language: guess

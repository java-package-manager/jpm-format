# IntelliJ inspections

IntelliJ IDEA's Java inspections that are on by default, reimplemented on javac's syntax tree: 149 of them.
Each id is IntelliJ's short name, so `@SuppressWarnings("<id>")` and `//noinspection <id>` written for
IntelliJ suppress it here too, as `// jpm-ignore lint/<category>/<id>: <reason>` does. They run with
`linter.recommended` (the default) and are configured like the other rules, under
`linter.rules.<category>.<id>` (see [rules.md](rules.md)).

Some use types: they are more precise when jpm-format runs in a jpm project whose dependencies are
installed, and those marked **needs types** run only then.

Not reimplemented: inspections that need data-flow analysis (`ConstantValue`, `DataFlowIssue`,
`UnusedAssignment`, `MismatchedCollectionQueryUpdate`), the whole project (`unused`, `SameParameterValue`,
`UnusedReturnValue`, `EmptyMethod`), or the project's language level (the "can be replaced with" migrations
such as `Convert2Lambda`, `PatternVariableCanBeUsed` or `EnhancedSwitchMigration`); and IntelliJ-specific
ones (nullability annotations, `@Contract`, injected languages, IntelliJ's own APIs).

## correctness

Code that is wrong: it does not do what it says, or always fails.

### InfiniteLoopStatement

A `while (true)`, `for (;;)` or `do ... while (true)` loop that nothing leaves: no `break` to it, `return`, `throw`, `System.exit` or `fail(...)`, so it can only end with an exception.

### InfiniteRecursion

A method whose first statement calls the method itself, so it can only end with a `StackOverflowError`. Without types, only calls that pass the parameters on unchanged are reported.

### SerialVersionUIDNotStaticFinal

A `serialVersionUID` field that is not `static final` (or not `long`), which serialization ignores.

### EqualsWhichDoesntCheckParameterClass

An `equals(Object)` that never checks the class of its argument (with `instanceof`, `getClass()` or a cast in a `try`), so it can throw `ClassCastException`.

### IteratorHasNextCallsIteratorNext

A `hasNext()` that calls `next()` on the same iterator, which moves it on.

### ComparatorMethodParameterNotUsed

A `compare(a, b)` that does not use one of its parameters, so it cannot be a valid ordering.

### WrongPackageStatement

A `package` declaration that does not match the folder the file is in.

### PackageInfoWithoutPackage

A `package-info.java` without a `package` declaration, so its Javadoc and annotations apply to nothing.

### ComparisonToNaN

A comparison with `Double.NaN` or `Float.NaN`, which is always false (`!=`: always true); use `isNaN()`.

### DivideByZero

Division or remainder by the integer literal zero, `x / 0` or `x %= 0`, which throws `ArithmeticException`.

### ShiftOutOfRange

A shift by a constant outside 0 to 31 for an `int` (0 to 63 for a `long`), which Java takes modulo 32 (64). Without types, shifts of `int` literals and shifts by 64 or more are reported.

### NumericOverflow

A constant expression of integer literals that overflows its type, `24 * 60 * 60 * 1000 * 1000`; an `L` on one literal makes it `long`.

### IncompatibleMask

`(x & a) == b` where `b` has bits that `a` lacks, or `(x | a) == b` where `a` has bits `b` lacks: always false (`!=`: always true).

### ComparatorResultComparison

The result of `compare()` or `compareTo()` compared with `1` or `-1`; only its sign is specified. The fix compares with `0`.

### MathRandomCastToInt

`(int) Math.random()`, which is always 0: the cast comes before any multiplication.

### NewObjectEquality

`x == new T(...)`, which is always false: a new object is never identical to an existing one.

### CollectionAddedToSelf

A collection added to itself, `list.add(list)` or `map.put(map, v)`, which makes `hashCode()` and `toString()` recurse forever.

### ThrowableNotThrown

An exception created and thrown away, `new IllegalStateException("...");` without `throw`. Without types, classes named `...Exception`, `...Error` or `Throwable` are taken as exceptions.

### SystemRunFinalizersOnExit

A call to `System.runFinalizersOnExit()` or `Runtime.runFinalizersOnExit()`, which is unsafe and removed since Java 11.

### ThreadRun

`thread.run()`, which runs the thread's code on the calling thread; `start()` runs it on the new one. Without types, only `new Thread(...).run()` is recognized.

### SynchronizeOnNonFinalField

`synchronized (field)` on a field that is not `final`: when it changes, threads lock different objects.

### SynchronizationOnGetClass

`synchronized (getClass())`, which locks a different object in each subclass; lock `T.class`.

### NonAtomicOperationOnVolatileField

`v++`, `v += x` or `v = v + x` on a `volatile` field outside `synchronized`: the read and the write are separate, so updates from other threads can be lost.

### OptionalAssignedToNull

`null` assigned to a variable of type `Optional`, or returned from a method that returns one; use `Optional.empty()`.

### NumberEquality

Two boxed numbers (`Integer`, `Long`, `BigDecimal` ...) compared with `==` or `!=`, which compares identity; use `equals()`. **Needs types.**

### ArrayEquals

`a.equals(b)` on an array, which compares identity; use `Arrays.equals(a, b)`. The fix rewrites it. **Needs types.**

### ArrayHashCode

`a.hashCode()` on an array, which hashes its identity; use `Arrays.hashCode(a)`. **Needs types.**

### ImplicitArrayToString

An array converted to text by concatenation, `print`/`println`, `append` or `String.valueOf`, which gives `[I@1b6d3586`; use `Arrays.toString()`. **Needs types.**

### BigDecimalMethodWithoutRoundingCalled

`BigDecimal.divide(x)` or `setScale(n)` without a rounding mode, which throws `ArithmeticException` when the result does not fit. **Needs types.**

### MalformedFormatString

A format string (`String.format`, `printf`, `formatted`) with an unknown conversion, or with more or fewer specifiers than arguments.

### UnpredictableBigDecimalConstructorCall

`new BigDecimal(0.1)` with a `double` literal, which is the binary approximation (0.1000000000000000055...); `new BigDecimal("0.1")` is exact. The fix uses the string.

### MismatchedStringCase

`s.toUpperCase().equals("abc")` and the like: a string in one case compared with a literal that has letters of the other case, which never matches.

### LoggingPlaceholderCountMatchesArgumentCount

An SLF4J or Log4j 2 call whose message has a different number of `{}` placeholders than it has arguments (a last exception argument does not count). Without types, loggers are recognized by the names `log` and `logger`.

## suspicious

Code that is probably wrong, or easily misread.

### EmptyStatementBody

An `if`, loop or `switch` whose body is empty: `;` or `{}` with no comment in it.

### EmptySynchronizedStatement

A `synchronized` block with nothing in it.

### ReturnFromFinallyBlock

A `return` inside a `finally` block, which discards any exception thrown in the `try`.

### ThrowFromFinallyBlock

A `throw` inside a `finally` block, not caught there, which replaces any exception thrown in the `try`.

### ContinueOrBreakFromFinallyBlock

A `break` or `continue` that leaves a `finally` block, which discards any exception thrown in the `try`.

### FinallyBlockCannotCompleteNormally

A `finally` block that always ends in a `return`, `throw`, `break` or `continue`, so the `try` never completes as written.

### LoopStatementsThatDontLoop

A loop whose body always ends in a `break`, `return` or `throw` and never `continue`s, so it runs at most once.

### AssignmentUsedAsCondition

An assignment as the condition of an `if`, loop or `?:`, `if (a = b)`, usually a mistyped `==`.

### AssignmentToCatchBlockParameter

An assignment to the parameter of a `catch`, which hides the exception that was caught.

### DuplicateCondition

A condition that appears twice in an `if` ... `else if` chain, or twice in a chain of `&&` or `||`; the second time it decides nothing.

### TextLabelInSwitchStatement

A label among the statements of a `switch` case, `case 1: foo: ...`, usually a mistyped `case`.

### SuspiciousIndentAfterControlStatement

A statement indented as if it were in the body of the `if` or loop before it, which has no braces: only the first statement is the body.

### OctalLiteral

An integer literal with a leading zero, `010`, which is octal (8), often by mistake.

### MethodNameSameAsClassName

A method named like its class, which looks like a constructor but has a return type.

### NonFinalFieldInEnum

A field of an enum that is not `final`: enum constants are shared, so their state should not change.

### NonSerializableWithSerialVersionUIDField

A `serialVersionUID` in a class that is not `Serializable`, where it means nothing. Without types, only classes that extend and implement nothing are reported.

### FinalizeNotProtected

A `public` `finalize()` method, which lets any code call it; it should be `protected`.

### ConfusingMainMethod

A method named `main` that is not `public static void main(String[])` in a class, so it is not the program's entry point.

### CloneableImplementsClone

A class that implements `Cloneable` without overriding `clone()`.

### UnaryPlus

A unary `+`, which does nothing to a number and often hides a mistyped `+=` or `++`.

### SillyAssignment

A variable assigned to itself, `x = x`, often a missing `this.` in `this.x = x`.

### ExpressionComparedToItself

An expression compared with itself, `a == a` or `a < a`, which is constant. With types, `x != x` on a `double` or `float` (the NaN test) is not reported.

### EqualsWithItself

`a.equals(a)`, `a.compareTo(a)` or `Objects.equals(a, a)`: an object compared with itself.

### AssertWithSideEffects

An `assert` whose condition assigns or increments something, which only happens when assertions are enabled.

### ClassGetClass

`T.class.getClass()`, which is `Class.class`, not `T.class`.

### NoExplicitFinalizeCalls

A call to `finalize()`, which only the garbage collector should make (`super.finalize()` inside `finalize()` is fine).

### SynchronizationOnLocalVariableOrMethodParameter

`synchronized` on a local variable that holds a new object, or on a method parameter, which other threads are unlikely to lock.

### IntegerDivisionInFloatingPointContext

An integer division whose result is used as a `float` or `double`, `double d = a / b`, which drops the fraction before it is converted. **Needs types.**

### IntegerMultiplicationImplicitCastToLong

An `int` multiplication (or left shift) whose result is used as a `long`, `long ms = days * 86400000`, which overflows before it is widened. **Needs types.**

### MathRoundingWithIntArgument

`Math.round()`, `ceil()`, `floor()` or `rint()` of an integer, which is already whole: probably an integer division that should not be one. **Needs types.**

### ComparisonOfShortAndChar

A `short` compared with a `char` with `==` or `!=`, which can differ for values above `Short.MAX_VALUE`. **Needs types.**

### LossyConversionCompoundAssignment

A compound assignment that narrows silently, `int i; i += 1.5;`: the result is cast back to the variable's type. **Needs types.**

### ReplaceAllDot

`replaceAll(".", ...)`, `split(".")` and the like: the argument is a regular expression, in which `.` matches any character and `|` matches the empty string.

### TrailingWhitespacesInTextBlock

Spaces at the end of a line in a text block, which the compiler strips; `\s` or `\040` keeps one.

### ReplaceOnLiteralHasNoEffect

`"text".replace("x", ...)` on a literal that does not contain what it replaces, which returns the literal unchanged.

### SuspiciousDateFormat

A date pattern with `YYYY` (week-based year) next to month and day, or `DD` (day of year) next to a month: almost always meant as `yyyy` and `dd`.

### LoggerInitializedWithForeignClass

A logger created with another class, `LoggerFactory.getLogger(Other.class)`, usually copied from another file: its messages are logged under the wrong name.

## complexity

Code that does nothing, or more than it needs to.

### UnnecessaryReturn

A `return;` as the last thing a `void` method, constructor or lambda does. The fix deletes it.

### UnnecessaryContinue

A `continue` as the last thing a loop's body does. The fix deletes it.

### UnnecessaryBreak

A `break` as the last statement of a `case ->` block, which never falls through. The fix deletes it.

### UnusedLabel

A label that no `break` or `continue` names. The fix removes the label.

### UnnecessaryLabelOnBreakStatement

`break label;` where the label names the loop or `switch` the `break` would leave anyway. The fix removes the label.

### UnnecessaryLabelOnContinueStatement

`continue label;` where the label names the innermost loop. The fix removes the label.

### EmptyFinallyBlock

A `finally` block with nothing in it.

### EmptyTryBlock

A `try` block with nothing in it and no resources.

### CaughtExceptionImmediatelyRethrown

The last `catch` of a `try` that only rethrows what it caught, `catch (E e) { throw e; }`, which changes nothing.

### TrivialIf

`if (c) return true; else return false;` (or `return false` after the `if`), and the same with an assignment, which is `return c;`. The fix rewrites it.

### IfStatementWithIdenticalBranches

An `if` whose `then` and `else` branches are the same code.

### DuplicateBranchesInSwitch

Two branches of a `switch` with the same code, which can be merged into one with both labels (not pattern labels, which cannot be merged).

### RedundantLabeledSwitchRuleCodeBlock

A `case ->` block with a single statement, `case 1 -> { f(); }` or `case 1 -> { yield x; }`, which can be the statement or expression alone. The fix unwraps it.

### UnnecessaryLocalVariable

A local variable that is returned or thrown right after it is declared, `T x = e; return x;`. The fix returns the expression.

### TryWithIdenticalCatches

Two `catch` blocks with the same code, which can be one multi-catch, `catch (A | B e)`.

### RedundantExplicitClose

A `close()` at the end of a `try`-with-resources block on one of its resources, which the `try` closes anyway. The fix deletes it.

### UnnecessarySemicolon

A `;` that ends nothing: between members or imports, after a class, a lone `;` statement in a block, or after the last resource of a `try`. The fix deletes it.

### ExtendsObject

`class A extends Object`, which every class does anyway. The fix removes it.

### TypeParameterExtendsObject

A type parameter or wildcard bounded by `Object`, `<T extends Object>`, which every type is. The fix removes the bound.

### FinalPrivateMethod

`final` on a `private` method, which cannot be overridden anyway. The fix removes it.

### FinalStaticMethod

`final` on a `static` method, which cannot be overridden anyway. The fix removes it.

### FinalMethodInFinalClass

`final` on a method of a `final` class, or of an enum or record, which cannot be subclassed. The fix removes it.

### EmptyInitializer

An empty initializer block, `{}` or `static {}`, in a class. The fix deletes it.

### RedundantMethodOverride

An `@Override` method that only calls the method it overrides with the same arguments, `return super.f(a);`, and changes nothing else: not its visibility, return type or exceptions. **Needs types.**

### RedundantRecordConstructor

A record's compact constructor with nothing in it, or a canonical constructor that only assigns each component to its field, which the record does anyway.

### DuplicateThrows

An exception listed twice in a method's `throws` clause.

### SimplifiableConditionalExpression

A `?:` with a `boolean` literal branch: `c ? true : false` is `c`, `c ? false : true` is `!c`, `c ? x : false` is `c && x`, `c ? true : x` is `c || x`. The fix rewrites it.

### ConstantConditionalExpression

A `?:` whose condition is a `boolean` literal, `true ? a : b`. The fix keeps the branch it picks.

### ConditionalExpressionWithIdenticalBranches

A `?:` whose two branches are the same expression, `c ? x : x`. The fix keeps one.

### DoubleNegation

A negation of a negation, `!!x` or `!(a != b)`. The fix removes both.

### UnnecessaryUnaryMinus

A unary minus that another operator could absorb: `-(-x)`, `a + -b` (`a - b`), `a - -b` (`a + b`), `a += -b` (`a -= b`).

### PointlessArithmeticExpression

Arithmetic that does nothing: `x * 1`, `x / 1`, `x - 0`, `x + 0`, or is always zero, `x * 0`. `x + 0` is reported only when types show it is not a `String` concatenation.

### PointlessBitwiseExpression

Bitwise operations that do nothing, `x | 0`, `x ^ 0`, `x & -1`, `x << 0`, or are always zero, `x & 0`.

### PointlessNullCheck

A null check next to an `instanceof` of the same value, `x != null && x instanceof T`: `instanceof` is false for `null` anyway. The fix removes the null check.

### OptionalOfNullableMisuse

`Optional.ofNullable(null)`, which is `Optional.empty()`, or `Optional.ofNullable` of a literal or new object, which is never null: `Optional.of`.

### UnnecessaryToStringCall

`x.toString()` where the conversion happens anyway: in a string concatenation, `append()`, `print()`/`println()` or `String.valueOf()`. The fix removes the call.

### UnnecessaryCallToStringValueOf

`String.valueOf(x)` where the conversion happens anyway: in a string concatenation, `append()` or `print()`/`println()` (not for `char[]`). The fix removes the call. **Needs types.**

## performance

Code that does needless work.

### InstantiatingObjectToGetClassObject

`new T().getClass()`, which creates an object only to get `T.class`. The fix uses the class literal.

### BusyWait

`Thread.sleep()` in a loop, which waits by polling; a lock, condition or blocking queue waits for the event.

### StringEqualsEmptyString

`s.equals("")`, which is `s.isEmpty()`. The fix rewrites it.

### ArraysAsListWithZeroOrOneArgument

`Arrays.asList()` with no argument, which is `Collections.emptyList()`, or with one element (not an array), which is `Collections.singletonList(x)`.

### StringBufferReplaceableByStringBuilder

A local `StringBuffer` that never leaves its method, which needs none of `StringBuffer`'s locking: `StringBuilder` does the same. The fix changes the type.

### RedundantStringFormatCall

`String.format("text")` with no arguments and no format specifiers, which is the text itself. The fix uses the literal.

### StringConcatenationInLoops

A `String` built with `+=` (or `s = s + ...`) in a loop, which copies it each time; a `StringBuilder` does not.

### CharsetObjectCanBeUsed

A standard charset named by a string, `getBytes("UTF-8")`, where `StandardCharsets.UTF_8` can be passed: no lookup, and no `UnsupportedEncodingException`. The fix uses the constant.

### UnnecessaryTemporaryOnConversionToString

`new Integer(x).toString()` or `Integer.valueOf(x).toString()`, which creates an object only to convert it: `Integer.toString(x)`. The fix rewrites it.

### UnnecessaryTemporaryOnConversionFromString

`Integer.valueOf(s).intValue()` or `new Integer(s).intValue()` on a `String`, which is `Integer.parseInt(s)`. Without types, only string literals are recognized. The fix rewrites it.

### BooleanConstructor

`new Boolean(x)`, deprecated for removal: `Boolean.valueOf(x)` returns the shared instances. The fix rewrites it.

### CachedNumberConstructorCall

`new Integer(x)` and the like for `Long`, `Short`, `Byte` and `Character`, deprecated for removal: `valueOf` uses the cache. The fix rewrites it.

### ToArrayCallWithZeroLengthArrayArgument

`c.toArray(new T[c.size()])`, which is slower than `c.toArray(new T[0])` and not safe if `c` changes meanwhile. The fix passes an empty array.

### IOStreamConstructor

`new FileInputStream(...)` or `new FileOutputStream(...)`, which have a finalizer; `Files.newInputStream()` and `Files.newOutputStream()` do not. A stream of a `FileDescriptor` (`FileDescriptor.out`), which `Files` cannot open, is left alone.

### StringConcatenationArgumentToLogCall

A `trace`, `debug` or `info` message built by concatenation, `log.debug("x = " + x)`, which builds the string even when the level is off; `log.debug("x = {}", x)` does not.

## style

Code that says what it does in a roundabout way.

### ConditionalBreakInInfiniteLoop

`while (true) { if (c) break; ... }`: the `if` can be the loop's condition, `while (!c)`.

### ForLoopReplaceableByWhile

A `for` loop with a condition but neither initializer nor update, `for (; c; )`, which is a `while` loop. The fix rewrites it.

### SwitchStatementWithTooFewBranches

A `switch` statement with fewer than two `case` labels, which reads better as an `if`.

### DefaultNotLastCaseInSwitch

A `default` that is not the last branch of its `switch`.

### CodeBlock2Expr

A lambda whose block body is a single `return x;` or expression statement, which can be the expression alone: `x -> { return f(x); }` is `x -> f(x)`. The fix rewrites it.

### CStyleArrayDeclaration

Array brackets after the name, `int a[]` or `int f()[]`, instead of after the type, `int[] a`. The fix moves them.

### LongLiteralsEndingWithLowercaseL

A `long` literal ending in a lowercase `l`, which looks like `1`. The fix makes it `L`.

### ProtectedMemberInFinalClass

A `protected` field or method in a `final` class that extends nothing, which no subclass can use: it is package-private in effect.

### NewClassNamingConvention

A class, interface, enum, record or annotation whose name is not UpperCamelCase (`[A-Z][A-Za-z\d]*`).

### FieldMayBeFinal

A `private` field that is never assigned after it is initialized: in its declaration, or once at the top of every constructor (static fields: in a static initializer). It can be `final`.

### OptionalUsedAsFieldOrParameterType

A field or method parameter of type `Optional`, which is meant for return values only.

### ManualMinMaxCalculation

`a > b ? a : b` and the like, which is `Math.max(a, b)` or `Math.min(a, b)`.

### TrivialStringConcatenation

Concatenation with the empty string to convert a value, `"" + x`; `String.valueOf(x)` says so.

### RedundantClassCall

`T.class.isInstance(x)` and `T.class.cast(x)` on a class literal, which are `x instanceof T` and `(T) x`. The fix rewrites them.

### ThrowablePrintStackTrace

`e.printStackTrace()`, which writes to standard error instead of a log.

### ThrowablePrintedToSystemOut

An exception printed with `System.out.println(e)` or `System.err.println(e)`, which loses its stack trace. Without types, only `catch` parameters are recognized.

### AccessStaticViaInstance

A static field or method used through an instance, `obj.staticMethod()`, which reads as if it depended on `obj`. The fix uses the class. **Needs types.**

### SizeReplaceableByIsEmpty

`x.size() == 0` or `x.length() > 0` and the like, which `x.isEmpty()` says directly. With types, any type that has `isEmpty()`; without, only `size()`. The fix rewrites it.

### IndexOfReplaceableByContains

`s.indexOf(x) != -1`, `>= 0`, `== -1` or `< 0`, which is `s.contains(x)` or its negation, for a string or list argument (not a `char`). Without types, calls with a string literal argument. The fix rewrites it.

### UnnecessaryStringEscape

An escape that is not needed: `\'` in a string, `\"` in a `char` literal or in a text block (unless it breaks up `"""`). The fix removes the backslash.

### UnnecessaryUnicodeEscape

A `\uXXXX` escape for a printable ASCII character in a literal, `\u0041` for `A`. The fix writes the character.

### SystemGetProperty

`System.getProperty()` of a property the library gives directly: `line.separator` is `System.lineSeparator()`, `file.separator` and `path.separator` are `File.separator` and `File.pathSeparator`. The fix uses them.

### MisorderedAssertEqualsArguments

`assertEquals(actual, CONSTANT)` with the constant second: JUnit takes the expected value first, so failure messages come out backwards. TestNG, which takes it second, is recognized by its imports.

### SimplifiableAssertion

An assertion a more specific one says better: `assertTrue(a.equals(b))` is `assertEquals`, `assertTrue(!x)` is `assertFalse(x)`, `assertTrue(x == null)` is `assertNull(x)`, `assertEquals(true, x)` is `assertTrue(x)`. The fix rewrites it.

### DanglingJavadoc

A Javadoc comment (`/** ... */`) that documents nothing: not right before a class, member, initializer, package or module declaration (other comments may come between). Javadoc ignores it; a block comment `/* ... */` says what is meant.

### JavadocDeclaration

A Javadoc tag that does not match its declaration: `@param` for a parameter or type parameter that does not exist, a duplicate `@param`, or `@return` on a `void` method or constructor.

### CommentedOutCode

Two or more lines of comments that are Java code: statements, members or a class that compile. Version control keeps old code; a comment should say why.

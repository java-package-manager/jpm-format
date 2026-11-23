package dev.jpm.format.inspections;

import dev.jpm.format.api.Finding;
import dev.jpm.format.api.RuleTester;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One case per inspection: a source where {@code »} marks the start of each finding, and nothing else is reported.
 * A case starting with {@code typed:} runs with types (resolved against the JDK).
 */
class InspectionsTest {
    private static final Map<String, String> CASES = new LinkedHashMap<>();

    static {
        CASES.put("UnnecessaryReturn", """
                class A { void f(int x) { if (x > 0) { g(); »return; } else { »return; } } int h() { return 1; }
                  void g() { Runnable r = () -> { g(); »return; }; return2(); } void return2() {} }""");
        CASES.put("UnnecessaryContinue", """
                class A { void f(int[] a) { for (int x : a) { if (x > 0) { g(); »continue; } } while (true) { »continue; } } void g() {} }""");
        CASES.put("UnnecessaryBreak", """
                class A { void f(int x) { switch (x) { case 1 -> { g(); »break; } default -> g(); } } void g() {} }""");
        CASES.put("UnusedLabel", """
                class A { void f() { »outer: for (;;) { break; } used: for (;;) { break used; } } }""");
        CASES.put("UnnecessaryLabelOnBreakStatement", """
                class A { void f() { l: for (;;) { »break l; } o: for (;;) { for (;;) { break o; } } } }""");
        CASES.put("UnnecessaryLabelOnContinueStatement", """
                class A { void f() { l: for (int i = 0; i < 3; i++) { »continue l; } } }""");
        CASES.put("EmptyStatementBody", """
                class A { void f(boolean b) { »if (b); »while (b) {} if (b) { /* why */ } »for (;;); } }""");
        CASES.put("EmptyFinallyBlock", """
                class A { void f() { try { g(); } finally »{} } void g() {} }""");
        CASES.put("EmptyTryBlock", """
                class A { void f() { try »{} finally { g(); } } void g() {} }""");
        CASES.put("EmptySynchronizedStatement", """
                class A { void f() { »synchronized (this) {} } }""");
        CASES.put("ReturnFromFinallyBlock", """
                class A { int f() { try { return 1; } finally { »return 2; } } }""");
        CASES.put("ThrowFromFinallyBlock", """
                class A { void f() { try { g(); } finally { »throw new RuntimeException(); } }
                  void h() { try { g(); } finally { try { throw new RuntimeException(); } catch (RuntimeException e) {} } } void g() {} }""");
        CASES.put("ContinueOrBreakFromFinallyBlock", """
                class A { void f() { for (;;) { try { g(); } finally { »break; } } } void h() { try { g(); } finally { for (;;) { break; } } } void g() {} }""");
        CASES.put("FinallyBlockCannotCompleteNormally", """
                class A { int f() { try { return 1; } »finally { return 2; } } }""");
        CASES.put("InfiniteLoopStatement", """
                class A { void f() { »while (true) { g(); } } void h() { while (true) { if (x()) break; } } void g() {} boolean x() { return true; } }""");
        CASES.put("LoopStatementsThatDontLoop", """
                class A { int f(int[] a) { »for (int x : a) { return x; } return 0; } }""");
        CASES.put("ConditionalBreakInInfiniteLoop", """
                class A { void f() { »while (true) { if (x()) break; g(); } } void g() {} boolean x() { return true; } }""");
        CASES.put("ForLoopReplaceableByWhile", """
                class A { void f() { »for (; x(); ) { } for (;;) { break; } } boolean x() { return true; } }""");
        CASES.put("AssignmentUsedAsCondition", """
                class A { void f(boolean a, boolean b) { if (»a = b) {} if (a == b) {} } }""");
        CASES.put("AssignmentToCatchBlockParameter", """
                class A { void f() { try { } catch (RuntimeException e) { »e = null; } } }""");
        CASES.put("CaughtExceptionImmediatelyRethrown", """
                class A { void f() { try { g(); } catch (RuntimeException e) { »throw e; } } void g() {} }""");
        CASES.put("TrivialIf", """
                class A { boolean f(int x) { »if (x > 0) { return true; } else { return false; } }
                  boolean g(int x) { »if (x > 0) return false; return true; } }""");
        CASES.put("IfStatementWithIdenticalBranches", """
                class A { void f(boolean b) { »if (b) { g(); } else { g(); } } void g() {} }""");
        CASES.put("DuplicateCondition", """
                class A { void f(int x, boolean a, boolean b) { if (x == 1) {} else if (x == 2) {} else if (»x == 1) {} if (a && b && »a) {} } }""");
        CASES.put("SwitchStatementWithTooFewBranches", """
                class A { void f(int x) { »switch (x) { case 1: g(); break; default: break; } switch (x) { case 1, 2: g(); } } void g() {} }""");
        CASES.put("DefaultNotLastCaseInSwitch", """
                class A { void f(int x) { switch (x) { »default: break; case 1: break; } } }""");
        CASES.put("TextLabelInSwitchStatement", """
                class A { void f(int x) { switch (x) { case 1: »foo: g(); break; } } void g() {} }""");
        CASES.put("DuplicateBranchesInSwitch", """
                class A { void f(int x) { switch (x) { case 1: g(1); break; case 2: h(); break; »case 3: g(1); break; } } void g(int i) {} void h() {} }""");
        CASES.put("RedundantLabeledSwitchRuleCodeBlock", """
                class A { int f(int x) { switch (x) { case 1 -> »{ g(); } default -> { g(); g(); } }
                  return switch (x) { case 1 -> »{ yield 2; } default -> 3; }; } void g() {} }""");
        CASES.put("SuspiciousIndentAfterControlStatement", """
                class A {
                    void f(boolean b) {
                        if (b)
                            g();
                            »g();
                        if (b) g();
                        g();
                    }
                    void g() {}
                }""");
        CASES.put("UnnecessaryLocalVariable", """
                class A { int f() { »int x = g(); return x; } int g() { int y = 1; y++; return y; } }""");
        CASES.put("CodeBlock2Expr", """
                class A { java.util.function.IntUnaryOperator f = x -> »{ return x + 1; }; Runnable r = () -> »{ g(); }; void g() {} }""");
        CASES.put("TryWithIdenticalCatches", """
                class A { void f() { try { g(); } catch (IllegalStateException e) { h(e); } catch (»IllegalArgumentException e) { h(e); } }
                  void g() {} void h(Exception e) {} }""");
        CASES.put("RedundantExplicitClose", """
                class A { void f() throws Exception { try (java.io.InputStream in = open()) { in.read(); »in.close(); } } java.io.InputStream open() { return null; } }""");
        CASES.put("InfiniteRecursion", """
                class A { int f(int x) { return »f(x); } int g(int x) { if (x == 0) return 0; return g(x - 1); } }""");
        CASES.put("UnnecessarySemicolon", """
                import java.util.List;»;
                class A { int x;»; final int a = 1, b = 2; void f() { »; } enum E { X, Y; void m() {} } enum F { P, Q»; } }»;""");
        CASES.put("CStyleArrayDeclaration", """
                class A { int »a[]; int[] b; void f(String »args[]) {} }""");
        CASES.put("LongLiteralsEndingWithLowercaseL", """
                class A { long a = »10l; long b = 10L; }""");
        CASES.put("OctalLiteral", """
                class A { int a = »010; int b = 0; int c = 0x10; }""");
        CASES.put("ExtendsObject", """
                class A »extends Object {} class B extends A {}""");
        CASES.put("TypeParameterExtendsObject", """
                class A<T »extends Object> { java.util.List<»? extends Object> a; java.util.List<? extends A<T>> b; }""");
        CASES.put("FinalPrivateMethod", """
                class A { private »final void f() {} private void g() {} }""");
        CASES.put("FinalStaticMethod", """
                class A { static »final void f() {} final void g() {} }""");
        CASES.put("FinalMethodInFinalClass", """
                final class A { public »final void f() {} } class B { final void g() {} }""");
        CASES.put("ProtectedMemberInFinalClass", """
                final class A { »protected int x; »protected void f() {} } final class B extends A2 { protected void f() {} } class A2 {}""");
        CASES.put("MethodNameSameAsClassName", """
                class A { void »A() {} A(int x) {} }""");
        CASES.put("NonFinalFieldInEnum", """
                enum E { X, Y; int »count; final int ok = 1; static int »shared; }""");
        CASES.put("SerialVersionUIDNotStaticFinal", """
                class A implements java.io.Serializable { private long »serialVersionUID = 1L; }
                class B implements java.io.Serializable { private static final long serialVersionUID = 1L; }""");
        CASES.put("NonSerializableWithSerialVersionUIDField", """
                class A { private static final long »serialVersionUID = 1L; }""");
        CASES.put("FinalizeNotProtected", """
                class A { »public void finalize() {} }""");
        CASES.put("EmptyInitializer", """
                class A { »static {} { /* why */ } »{} }""");
        CASES.put("ConfusingMainMethod", """
                class A { public static int »main(String[] args) { return 0; } }
                class B { public static void main(String[] args) {} } class C { void main() {} }""");
        CASES.put("CloneableImplementsClone", """
                class »A implements Cloneable {} class B implements Cloneable { public B clone() { return this; } }""");
        CASES.put("EqualsWhichDoesntCheckParameterClass", """
                class A { int x; public boolean »equals(Object o) { return x == ((A) o).x; } }
                class B { int x; public boolean equals(Object o) { return o instanceof B b && x == b.x; } }""");
        CASES.put("IteratorHasNextCallsIteratorNext", """
                abstract class A implements java.util.Iterator<String> { public boolean hasNext() { return »next() != null; } }""");
        CASES.put("ComparatorMethodParameterNotUsed", """
                class A implements java.util.Comparator<String> { public int compare(String a, String »b) { return a.length(); } }""");
        CASES.put("RedundantMethodOverride", """
                typed:class A { public String f(int x) { return ""; } protected String g() { return ""; } }
                class B extends A { @Override public String »f(int x) { return super.f(x); } @Override public String g() { return super.g(); } }""");
        CASES.put("RedundantRecordConstructor", """
                record P(int x, int y) { »P {} } record Q(int x) { Q(int x) { this.x = Math.abs(x); } }""");
        CASES.put("WrongPackageStatement", """
                path:src/a/b/A.java:»package a.c; class A {}""");
        CASES.put("PackageInfoWithoutPackage", """
                path:src/a/package-info.java:»/** Docs. */
                """);
        CASES.put("NewClassNamingConvention", """
                class Good {} class »bad_Name {}""");
        CASES.put("DuplicateThrows", """
                class A { void f() throws java.io.IOException, »java.io.IOException {} }""");
        CASES.put("FieldMayBeFinal", """
                class A { private int »a = 1; private int b = 1; private int »c; private int d;
                  A() { c = 2; d = 3; } void f() { b++; d = 4; } int g() { return a + b + c + d; } }""");
        CASES.put("OptionalUsedAsFieldOrParameterType", """
                import java.util.Optional;
                class A { »Optional<String> name; void f(»Optional<String> o) {} Optional<String> g() { return Optional.empty(); } }""");
        CASES.put("SimplifiableConditionalExpression", """
                class A { boolean f(boolean c, boolean x) { boolean a = »c ? true : false; return »c ? x : false; } }""");
        CASES.put("ConstantConditionalExpression", """
                class A { int f(int a, int b) { return »true ? a : b; } }""");
        CASES.put("ConditionalExpressionWithIdenticalBranches", """
                class A { int f(boolean c, int a) { return »c ? a : a; } }""");
        CASES.put("ManualMinMaxCalculation", """
                class A { int f(int a, int b) { return »a > b ? a : b; } int g(int a, int b) { return a > b ? b + 1 : a; } }""");
        CASES.put("DoubleNegation", """
                class A { boolean f(boolean b, int x, int y) { return »!!b || »!(x != y); } }""");
        CASES.put("UnaryPlus", """
                class A { int f(int x) { return »+x; } }""");
        CASES.put("UnnecessaryUnaryMinus", """
                class A { int f(int x, int y) { int a = »-(-x); x += »-y; return x - -1; } }""");
        CASES.put("PointlessArithmeticExpression", """
                class A { int f(int x) { return »»x * 1 + »x / 1 + x - 0 + x * 2; } double g(int n) { return 1.0 * n; } }""");
        CASES.put("PointlessBitwiseExpression", """
                class A { int f(int x) { return (»x | 0) + (»x & 0) + (»x << 0) + (x & 0xff); } }""");
        CASES.put("ComparisonToNaN", """
                class A { boolean f(double d) { return »d == Double.NaN; } }""");
        CASES.put("DivideByZero", """
                class A { int f(int x) { x %= 1; return »x / 0; } }""");
        CASES.put("ShiftOutOfRange", """
                class A { int f() { return »1 << 33; } long g(long x) { return »x << 64; } }""");
        CASES.put("NumericOverflow", """
                class A { int a = »24 * 60 * 60 * 1000 * 1000; long b = 24L * 60 * 60 * 1000 * 1000; int c = 3 * 4; }""");
        CASES.put("IncompatibleMask", """
                class A { boolean f(int x) { return »(x & 0x10) == 0x20 || (x & 0x30) == 0x20; } }""");
        CASES.put("SillyAssignment", """
                class A { int x; void f(int y) { »y = y; this.x = y; } }""");
        CASES.put("ExpressionComparedToItself", """
                class A { boolean f(int a, int b) { return »a == a || a == b; } }""");
        CASES.put("EqualsWithItself", """
                class A { boolean f(String s, String t) { return »s.equals(s) || s.equals(t) || »java.util.Objects.equals(t, t); } }""");
        CASES.put("ComparatorResultComparison", """
                class A { boolean f(String a, String b) { return »a.compareTo(b) == 1 || a.compareTo(b) > 0; } }""");
        CASES.put("PointlessNullCheck", """
                class A { boolean f(Object o) { return »o != null && o instanceof String; } }""");
        CASES.put("AssertWithSideEffects", """
                class A { void f(int x) { assert »x++ > 0; assert x > 0; } }""");
        CASES.put("MathRandomCastToInt", """
                class A { int f() { return »(int) Math.random() * 10; } int g() { return (int) (Math.random() * 10); } }""");
        CASES.put("NewObjectEquality", """
                class A { boolean f(Object o) { return »o == new Object(); } }""");
        CASES.put("TrivialStringConcatenation", """
                class A { String f(int x) { return »"" + x; } String g(int x) { return "x" + x; } }""");
        CASES.put("CollectionAddedToSelf", """
                class A { void f(java.util.List<Object> l) { »l.add(l); l.add(this); } }""");
        CASES.put("InstantiatingObjectToGetClassObject", """
                class A { Class<?> f() { return »new A().getClass(); } }""");
        CASES.put("ClassGetClass", """
                class A { Class<?> f() { return »A.class.getClass(); } }""");
        CASES.put("RedundantClassCall", """
                class A { boolean f(Object o) { return »String.class.isInstance(o); } String g(Object o) { return »String.class.cast(o); } }""");
        CASES.put("ThrowableNotThrown", """
                class A { void f() { »new IllegalStateException("x"); throw new IllegalStateException("y"); } }""");
        CASES.put("ThrowablePrintStackTrace", """
                class A { void f() { try { } catch (RuntimeException e) { »e.printStackTrace(); } } }""");
        CASES.put("ThrowablePrintedToSystemOut", """
                class A { void f() { try { } catch (RuntimeException e) { System.out.println(»e); System.out.println("x"); } } }""");
        CASES.put("NoExplicitFinalizeCalls", """
                class A { protected void finalize() throws Throwable { super.finalize(); } void f(A a) throws Throwable { »a.finalize(); } }""");
        CASES.put("SystemRunFinalizersOnExit", """
                class A { void f() { »System.runFinalizersOnExit(true); } }""");
        CASES.put("ThreadRun", """
                class A { void f(Runnable r) { »new Thread(r).run(); new Thread(r).start(); } }""");
        CASES.put("BusyWait", """
                class A { void f() throws Exception { while (x()) { »Thread.sleep(10); } Thread.sleep(5); } boolean x() { return true; } }""");
        CASES.put("SynchronizeOnNonFinalField", """
                class A { Object lock = new Object(); final Object ok = new Object(); void f() { synchronized (»lock) {} synchronized (ok) {} } }""");
        CASES.put("SynchronizationOnLocalVariableOrMethodParameter", """
                class A { void f(Object p) { Object l = new Object(); synchronized (»p) {} synchronized (»l) {} } }""");
        CASES.put("SynchronizationOnGetClass", """
                class A { void f() { synchronized (»getClass()) {} synchronized (A.class) {} } }""");
        CASES.put("NonAtomicOperationOnVolatileField", """
                class A { volatile int n; int m; void f() { »n++; m++; } synchronized void g() { n++; } }""");
        CASES.put("OptionalAssignedToNull", """
                import java.util.Optional;
                class A { Optional<String> f() { Optional<String> o = »null; return »null; } }""");
        CASES.put("OptionalOfNullableMisuse", """
                import java.util.Optional;
                class A { Object f(String s) { Optional.ofNullable(s); Optional.ofNullable("x"); return »Optional.ofNullable(null); } }""".replace(
                "Optional.ofNullable(\"x\")", "»Optional.ofNullable(\"x\")"));
        CASES.put("NumberEquality", """
                typed:class A { boolean f(Integer a, Integer b, int c) { return »a == b || a == c; } }""");
        CASES.put("ArrayEquals", """
                typed:class A { boolean f(int[] a, int[] b) { return »a.equals(b); } }""");
        CASES.put("ArrayHashCode", """
                typed:class A { int f(int[] a) { return »a.hashCode(); } }""");
        CASES.put("ImplicitArrayToString", """
                typed:class A { String f(int[] a) { System.out.println(»a); return "a = " + »a; } }""");
        CASES.put("IntegerDivisionInFloatingPointContext", """
                typed:class A { double f(int a, int b) { double d = »a / b; return d; } int g(int a, int b) { return a / b; } }""");
        CASES.put("IntegerMultiplicationImplicitCastToLong", """
                typed:class A { long f(int days) { long ms = »days * 86400000; return ms; } }""");
        CASES.put("MathRoundingWithIntArgument", """
                typed:class A { long f(int a, int b) { return »Math.round(a / b); } }""");
        CASES.put("ComparisonOfShortAndChar", """
                typed:class A { boolean f(short s, char c) { return »s == c; } }""");
        CASES.put("LossyConversionCompoundAssignment", """
                typed:class A { void f(int i, double d) { »i += d; i += 1; } }""");
        CASES.put("AccessStaticViaInstance", """
                typed:class A { static int n; static void g() {} void f(A a) { »a.g(); int x = »a.n; A.g(); } }""");
        CASES.put("BigDecimalMethodWithoutRoundingCalled", """
                typed:import java.math.*;
                class A { BigDecimal f(BigDecimal a, BigDecimal b) { a.divide(b, RoundingMode.HALF_UP); return »a.divide(b); } }""");
        CASES.put("StringEqualsEmptyString", """
                class A { boolean f(String s) { return »s.equals(""); } }""");
        CASES.put("SizeReplaceableByIsEmpty", """
                class A { boolean f(java.util.List<String> l) { return »l.size() == 0 || »l.size() > 0; } }""");
        CASES.put("IndexOfReplaceableByContains", """
                class A { boolean f(String s) { return »s.indexOf("x") != -1 || s.indexOf('y') < 0 || »s.indexOf("z") < 0; } }""");
        CASES.put("ArraysAsListWithZeroOrOneArgument", """
                import java.util.Arrays;
                class A { Object f() { Arrays.asList(1, 2); return »Arrays.asList(); } }""");
        CASES.put("UnnecessaryToStringCall", """
                class A { String f(Object o, StringBuilder sb) { sb.append(»o.toString()); return "o = " + »o.toString(); } }""");
        CASES.put("UnnecessaryCallToStringValueOf", """
                typed:class A { String f(int x, char[] c) { return "x = " + »String.valueOf(x) + String.valueOf(c); } }""");
        CASES.put("StringBufferReplaceableByStringBuilder", """
                class A { String f() { »StringBuffer sb = new StringBuffer(); sb.append(1); return sb.toString(); }
                  StringBuffer g() { StringBuffer b = new StringBuffer(); return b; } }""");
        CASES.put("RedundantStringFormatCall", """
                class A { String f(int x) { String.format("%d", x); return »String.format("plain"); } }""");
        CASES.put("ReplaceAllDot", """
                class A { String[] f(String s) { s.replaceAll(»".", "x"); return s.split("\\\\."); } }""");
        CASES.put("StringConcatenationInLoops", """
                class A { String f(String[] a) { String s = ""; for (String x : a) { »s += x; } return s; } }""");
        CASES.put("UnnecessaryStringEscape", """
                class A { String s = "it»\\'s"; char c = '»\\"'; char d = '\\''; }""");
        CASES.put("UnnecessaryUnicodeEscape", """
                class A { String s = "»\\u0041b"; String q = "\\u0022"; }""".replace("\\u0022", "\\\\u0022"));
        CASES.put("TrailingWhitespacesInTextBlock", "class A { String s = \"\"\"\n    one»   \n    two\n    \"\"\"; }");
        CASES.put("CharsetObjectCanBeUsed", """
                class A { byte[] f(String s) throws Exception { return s.getBytes(»"UTF-8"); } }""");
        CASES.put("SystemGetProperty", """
                class A { String f() { return »System.getProperty("line.separator"); } }""");
        CASES.put("MalformedFormatString", """
                typed:class A { void f(int a) { String.format(»"%d %d", a); String.format("%d%%", a); String.format(»"%q", a); } }""");
        CASES.put("ReplaceOnLiteralHasNoEffect", """
                class A { String f() { return »"abc".replace("x", "y") + "abc".replace("a", "b"); } }""");
        CASES.put("UnnecessaryTemporaryOnConversionToString", """
                class A { String f(int x) { return »Integer.valueOf(x).toString(); } }""");
        CASES.put("UnnecessaryTemporaryOnConversionFromString", """
                class A { int f() { return »Integer.valueOf("12").intValue(); } }""");
        CASES.put("BooleanConstructor", """
                class A { Boolean f(String s) { return »new Boolean(s); } }""");
        CASES.put("CachedNumberConstructorCall", """
                class A { Integer f(int x) { return »new Integer(x); } }""");
        CASES.put("UnpredictableBigDecimalConstructorCall", """
                class A { Object f() { new java.math.BigDecimal("0.1"); return »new java.math.BigDecimal(0.1); } }""");
        CASES.put("MismatchedStringCase", """
                class A { boolean f(String s) { return »s.toUpperCase().equals("abc") || s.toUpperCase().equals("ABC"); } }""");
        CASES.put("SuspiciousDateFormat", """
                class A { Object f() { new java.text.SimpleDateFormat("yyyy-MM-dd"); return new java.text.SimpleDateFormat(»"YYYY-MM-dd"); } }""");
        CASES.put("ToArrayCallWithZeroLengthArrayArgument", """
                class A { String[] f(java.util.List<String> l) { return l.toArray(»new String[l.size()]); } }""");
        CASES.put("IOStreamConstructor", """
                class A { Object f(java.io.File f) throws Exception { return »new java.io.FileInputStream(f); } }""");
        CASES.put("MisorderedAssertEqualsArguments", """
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class A { void f(int x) { »assertEquals(x, 3); assertEquals(3, x); } }""");
        CASES.put("SimplifiableAssertion", """
                import static org.junit.jupiter.api.Assertions.*;
                class A { void f(Object o, boolean b) { »assertTrue(o == null); »assertEquals(true, b); »assertTrue(!b); assertTrue(b); } }""");
        CASES.put("LoggerInitializedWithForeignClass", """
                class A { static final Object LOG = LoggerFactory.getLogger(»B.class); static final Object OK = LoggerFactory.getLogger(A.class); } class B {}""");
        CASES.put("LoggingPlaceholderCountMatchesArgumentCount", """
                class A { Logger log; void f(int a, Exception e) { log.info(»"{} {}", a); log.info("{}", a); log.warn("failed {}", a, e); } }""");
        CASES.put("StringConcatenationArgumentToLogCall", """
                class A { Logger log; void f(int a) { log.debug(»"a = " + a); log.debug("a = {}", a); log.error("a = " + a); } }""");
        CASES.put("DanglingJavadoc", """
                class A { /** Documents f. */ void f() { »/** Not documenting anything. */ int x = 1; } }""");
        CASES.put("JavadocDeclaration", """
                class A {
                    /**
                     * Does things.
                     *
                     * @param x the x
                     * »@param y not a parameter
                     * »@return nothing
                     */
                    void f(int x) {}
                }""");
        CASES.put("CommentedOutCode", """
                class A {
                    void f() {
                        »// int x = compute();
                        // use(x);

                        // A plain comment, about why.
                    }
                }""");
    }

    @Test
    void everyInspectionHasACase() {
        Set<String> missing = new TreeSet<>();
        for (Inspection i : Inspections.all()) if (!CASES.containsKey(i.id())) missing.add(i.id());
        assertTrue(missing.isEmpty(), "no test case for " + missing);
    }

    @Test
    void eachInspectionReportsWhereMarked() {
        List<String> failures = new ArrayList<>();
        for (Inspection i : Inspections.all()) {
            String marked = CASES.get(i.id());
            if (marked == null) continue;
            boolean typed = marked.startsWith("typed:");
            if (typed) marked = marked.substring("typed:".length());
            String path = null;
            if (marked.startsWith("path:")) {
                int colon = marked.indexOf(':', 5);
                path = marked.substring(5, colon);
                marked = marked.substring(colon + 1);
            }
            List<Integer> expected = new ArrayList<>();
            StringBuilder src = new StringBuilder();
            for (int k = 0; k < marked.length(); k++) {
                if (marked.charAt(k) == '»') expected.add(src.length());
                else src.append(marked.charAt(k));
            }
            RuleTester t = RuleTester.of(i);
            if (typed) t = t.withTypes();
            if (path != null) t = t.path(path);
            List<Integer> got = new ArrayList<>();
            List<String> messages = new ArrayList<>();
            for (Finding f : t.check(src.toString())) {
                if (!f.rule().equals(i.id())) {
                    messages.add(f.brief());
                    continue;
                }
                got.add(f.start());
                messages.add(f.start() + " " + f.message());
            }
            if (!got.equals(expected)) failures.add(i.id() + ": expected " + expected + ", got " + messages);
        }
        assertEquals(List.of(), failures);
    }
}

package de.dominicsteinhoefel.pluggabl.analysis.test

import de.dominicsteinhoefel.pluggabl.analysis.SymbolicExecutionAnalysis
import de.dominicsteinhoefel.pluggabl.analysis.test.SymbolicExecutionTestHelper.compareLeaves
import de.dominicsteinhoefel.pluggabl.expr.*
import de.dominicsteinhoefel.pluggabl.theories.IntTheory.IntValue
import de.dominicsteinhoefel.pluggabl.theories.IntTheory.plus
import org.junit.Test

class SimpleSymbolicExecutionAnalysisTests {
    private val SOOT_CLASS_PATH = listOf(
        "./build/classes/kotlin/test",
        "./src/test/lib/java-8-openjdk-amd64-rt.jar",
        "./src/test/lib/java-8-openjdk-amd64-jce.jar",
        "./src/test/lib/kotlin-stdlib-1.4.21.jar"
    )

    @Test
    fun testSimpleTwoBranchedMethod() {
        val analysis = SymbolicExecutionAnalysis.create(
            "de.dominicsteinhoefel.pluggabl.testcase.SimpleMethods",
            "int simpleTwoBranchedMethod(int)",
            SOOT_CLASS_PATH
        )

        analysis.symbolicallyExecute()

        val sInput = analysis.getLocal("input")
        val sResult = analysis.getLocal("result")
        val sTest = analysis.getLocal("test")
        val sStack = analysis.getLocal("\$stack3")

        val inputPlusOne = plus(sInput, IntValue(1))
        val inputPlusOneIsFortyTwo = EqualityConstr.create(inputPlusOne, IntValue(42))

        val expected = listOf(
            SymbolicExecutionState(
                SymbolicConstraintSet.from(NegatedConstr.create(inputPlusOneIsFortyTwo)),
                ParallelStore.create(
                    ElementaryStore(
                        sTest,
                        inputPlusOne
                    ),
                    ParallelStore.create(
                        ElementaryStore(sStack, plus(inputPlusOne, IntValue(3))),
                        ElementaryStore(sResult, plus(inputPlusOne, IntValue(3)))
                    )
                )
            ),
            SymbolicExecutionState(
                SymbolicConstraintSet.from(inputPlusOneIsFortyTwo),
                ParallelStore.create(
                    ElementaryStore(
                        sTest,
                        plus(plus(inputPlusOne, IntValue(2)), IntValue(4))
                    ), ElementaryStore(
                        sResult,
                        plus(plus(inputPlusOne, IntValue(2)), IntValue(4))
                    )
                )
            )
        )

        compareLeaves(expected, analysis)
    }

    @Test
    fun testSimpleTwoBranchedMethodWithMerge() {
        val analysis = SymbolicExecutionAnalysis.create(
            "de.dominicsteinhoefel.pluggabl.testcase.SimpleMethods",
            "int simpleTwoBranchedMethodWithMerge(int)",
            SOOT_CLASS_PATH
        )

        analysis.symbolicallyExecute()

        val sInput = analysis.getLocal("input")
        val sTest = analysis.getLocal("test")
        val sResult = analysis.getLocal("result")

        val inputPlusOne = plus(sInput, IntValue(1))

        val summary = ValueSummary.create(
            GuardedExpression(
                NegatedConstr.create(EqualityConstr.create(inputPlusOne, IntValue(42))),
                plus(inputPlusOne, IntValue(3))
            ),
            GuardedExpression(
                EqualityConstr.create(inputPlusOne, IntValue(42)),
                plus(inputPlusOne, IntValue(2))
            )
        )

        val expected = listOf(
            SymbolicExecutionState(
                SymbolicConstraintSet(),
                ParallelStore.create(
                    ElementaryStore(
                        sTest,
                        plus(
                            summary, IntValue(4)
                        )
                    ),
                    ElementaryStore(
                        sResult,
                        plus(
                            summary, IntValue(4)
                        )
                    )
                )
            )
        )

        compareLeaves(expected, analysis)
    }
}
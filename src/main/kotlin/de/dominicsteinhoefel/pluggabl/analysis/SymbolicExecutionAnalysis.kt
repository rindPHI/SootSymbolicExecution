package de.dominicsteinhoefel.pluggabl.analysis

import de.dominicsteinhoefel.pluggabl.expr.*
import de.dominicsteinhoefel.pluggabl.util.NewNamesCreator
import de.dominicsteinhoefel.pluggabl.util.SootBridge
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import soot.Body
import soot.G
import soot.Local
import soot.jimple.GotoStmt
import soot.jimple.IfStmt
import soot.jimple.Stmt
import soot.jimple.toolkits.annotation.logic.Loop
import soot.jimple.toolkits.annotation.logic.LoopFinder
import soot.toolkits.graph.ExceptionalUnitGraph
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet

class SymbolicExecutionAnalysis internal constructor(
    private val body: Body,
    private val root: Stmt = body.units.first as Stmt,
    private val stopAtNodes: List<Stmt> = emptyList(),
    private val ignoreTopLoop: Boolean = false
) {
    val cfg: ExceptionalUnitGraph = ExceptionalUnitGraph(body)

    internal val stmtToInputSESMap = HashMap<Stmt, List<SymbolicExecutionState>>()
    private val stmtToOutputSESMap = HashMap<Stmt, List<SymbolicExecutionState>>()
    private val loops = LoopFinder().getLoops(body)
    private val loopLeafSESMap = LinkedHashMap<Loop, SymbolicExecutionState>()

    private val rules = arrayOf(AssignRule, IfRule, LeafRule, DummyRule, IgnoreAndWarnRule)

    companion object {
        val logger: Logger = LoggerFactory.getLogger(SymbolicExecutionAnalysis::class.simpleName)

        fun create(clazz: String, methodSig: String): SymbolicExecutionAnalysis {
            G.reset()
            return SymbolicExecutionAnalysis(
                SootBridge.loadJimpleBody(clazz, methodSig)
                    ?: throw IllegalStateException("Could not load method $methodSig of class $clazz")
            )
        }

        fun create(
            body: Body,
            root: Stmt = body.units.first as Stmt,
            forceLeaves: List<Stmt> = emptyList(),
            ignoreTopLoop: Boolean = false
        ): SymbolicExecutionAnalysis {
            G.reset()
            return SymbolicExecutionAnalysis(body, root, forceLeaves, ignoreTopLoop)
        }
    }

    fun getInputSESs(node: Stmt) = stmtToInputSESMap[node] ?: emptyList()
    fun getOutputSESs(node: Stmt) = stmtToOutputSESMap[node] ?: emptyList()
    fun getLoopLeafSESs() = loopLeafSESMap.toMap()

    fun symbolicallyExecute() {
        val queue = LinkedList<Stmt>()

        fun <E> LinkedList<E>.addLastDistinct(elem: E) {
            if (!contains(elem)) addLast(elem)
        }

        queue.add(root)
        stmtToInputSESMap[root] = listOf(SymbolicExecutionState())

        while (!queue.isEmpty()) {
            val currStmt = queue.pop()

            if (stopAtNodes.contains(currStmt)) {
                continue
            }

            val assocLoop = loops.filter { it.head == currStmt }.let { if (it.size == 1) it[0] else null }

            val stmtIsHeadOfAnalyzedLoop = ignoreTopLoop && assocLoop?.head == root

            if (assocLoop != null && !stmtIsHeadOfAnalyzedLoop) {
                val loopIdx = loops.indexOf(assocLoop)
                val loopAnalysis = LoopAnalysis(
                    body,
                    assocLoop,
                    loopIdx,
                    stmtToInputSESMap,
                    stmtToOutputSESMap,
                    loopLeafSESMap,
                    this::propagateResultStateToSuccs
                )

                logger.trace("Analyzing loop ${loopIdx + 1}")
                loopAnalysis.executeLoopAndAnonymize()
                logger.trace("Done analyzing loop ${loopIdx + 1}")

                queue.addAll(assocLoop.loopExits.map { cfg.getSuccsOf(it) }.flatten().map { it as Stmt }
                    .filterNot(assocLoop.loopStatements::contains))
                continue
            }

            val inputStates = (stmtToInputSESMap[currStmt] ?: emptyList())

            if (cfg.getPredsOf(currStmt).size > inputStates.size && !stmtIsHeadOfAnalyzedLoop) {
                // Evaluation of predecessors not yet finished
                logger.trace(
                    "Postponing evaluation of statement ${currStmt}, " +
                            "${cfg.getPredsOf(currStmt).size - inputStates.size} predecessors not yet evaluated."
                )

                queue.addLast(currStmt)
                continue
            }

            val rule = getApplicableRule(currStmt, inputStates)
            val result = rule.apply(currStmt, inputStates).map { it.simplify() }

            if (result.size != cfg.getSuccsOf(currStmt).size) {
                throw IllegalStateException(
                    "Expected number ${result.size} of successors in SE graph " +
                            "does not match number of successors in CFG (${cfg.getSuccsOf(currStmt).size})"
                )
            }

            stmtToOutputSESMap[currStmt] = result

            // Only propagate result state if currStmt is no back edge to a loop header
            if (!(currStmt is GotoStmt && loops.any { it.head == currStmt.target })) {
                propagateResultStateToSuccs(currStmt, result)
                cfg.getSuccsOf(currStmt).map { it as Stmt }.forEach(queue::addLastDistinct)
            }

            // But propagate if we're currently doing a loop analysis
            if (ignoreTopLoop && (currStmt is GotoStmt && loops.any { it.head == currStmt.target && it.head == root })) {
                propagateResultStateToSuccs(currStmt, result)
            }
        }
    }

    private fun propagateResultStateToSuccs(
        currStmt: Stmt,
        result: List<SymbolicExecutionState>,
        ignore: List<Stmt> = emptyList()
    ) {
        cfg.getSuccsOf(currStmt).map { it as Stmt }
            .filterNot(ignore::contains).zip(result).forEach { (cfgNode, ses) ->
                stmtToInputSESMap[cfgNode] = listOf(
                    stmtToInputSESMap[cfgNode] ?: emptyList(),
                    listOf(ses)
                ).flatten()
            }
    }

    private fun getApplicableRule(
        currStmt: Stmt,
        inputStates: List<SymbolicExecutionState>
    ): SERule {
        val applicableRules = rules.filter { it.accepts(currStmt, inputStates) }
        logger.trace(
            "Applicable rules for statement type ${currStmt::class.simpleName}, " +
                    "${inputStates.size} input states: " +
                    applicableRules.joinToString(", ")
        )

        if (applicableRules.size != 1) {
            throw IllegalStateException(
                "Expected exactly one applicable rules for statement type " +
                        "${currStmt::class.simpleName}, found ${applicableRules.size}"
            )
        }

        return applicableRules[0]
    }
}
package org.allenai.ari.solvers.tableilp

import org.allenai.ari.solvers.tableilp.ilpsolver.{ IlpStatusFeasible, IlpStatus, ScipInterface }
import org.allenai.common.Logging

import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.mutable.ArrayBuffer

/** The alignment of a basic textual alignment unit (a term) in the ILP solution.
  *
  * @param term A basic alignment unit (a word, a chunk, a string associated with a cell, etc)
  * @param alignmentIds A sequence of alignment IDs that connect this term with other terms
  */
case class TermAlignment(
    term: String,
    alignmentIds: ArrayBuffer[Int]
) {
  def this(str: String) = this(str, ArrayBuffer.empty)
}

/** All alignments of each cell in a table's title row and content matrix to everything else.
  *
  * @param titleAlignments A TermAlignment for each cell in the title row
  * @param contentAlignments A TermAlignment for each cell in the content matrix
  */
case class TableAlignment(
  titleAlignments: Seq[TermAlignment],
  contentAlignments: Seq[Seq[TermAlignment]]
)

/** All alignments of each question constituent and each choice to everything else.
  *
  * @param qConsAlignments A TermAlignment for each question constituent
  * @param choiceAlignments A TermAlignment for each answer choice
  */
case class QuestionAlignment(
  qConsAlignments: Seq[TermAlignment],
  choiceAlignments: Seq[TermAlignment]
)

/** Metrics to capture ILP solution values and solution quality
  *
  * @param status Whether the solution is optimal, feasible, infeasible, etc.
  * @param lb The best found lower bound on the objective value.
  * @param ub The best found upper bound on the objective value.
  * @param optgap Optimality gap, defined as (ub - lb) / lb for a maximization problem
  */
case class SolutionQuality(
  status: IlpStatus,
  lb: Double,
  ub: Double,
  optgap: Double
)

/** The output of the ILP model; includes best answer choice, its score, the corresponding
  * alignments, solution quality stats, and timing stats.
  *
  * @param bestChoice The best choice determined by the ILP model
  * @param bestChoiceScore The score for the best choice (the ILP objective value)
  * @param tableAlignments A sequence of alignment information, one per table
  * @param questionAlignment Alignments for the question (including constituents and choices)
  */
case class IlpSolution(
  bestChoice: Int,
  bestChoiceScore: Double,
  tableAlignments: Seq[TableAlignment],
  questionAlignment: QuestionAlignment,
  solutionQuality: SolutionQuality
)

/** A container object to define Json protocol and have a main testing routine */
object IlpSolution {
  // The default json protocol has JsonFormat implemented only for immutable collections. Add it
  // for ArrayBuffer here.
  // Note: lift turns a one-sided converter (Writer or Reader) into a symmetric format that throws
  // an exception if an unimplemented method is called.
  implicit val termAlignmentJsonFormat: JsonFormat[TermAlignment] = lift(
    { pair: TermAlignment => (pair.term, pair.alignmentIds.toSeq).toJson }
  )
  implicit val tableAlignmentJsonFormat = jsonFormat2(TableAlignment.apply)
  implicit val questionAlignmentJsonFormat = jsonFormat2(QuestionAlignment.apply)
  implicit val ilpStatusJsonFormat: JsonFormat[IlpStatus] = lift(
    { status: IlpStatus => JsString(status.toString) }
  )
  implicit val solutionQualityJsonFormat = jsonFormat4(SolutionQuality.apply)
  implicit val ilpSolutionJsonFormat = jsonFormat5(IlpSolution.apply)

  /** Main method to test a sample alignment solution */
  def main(args: Array[String]) {
    val ilpSolution = IlpSolutionFactory.makeRandomIlpSolution
    println(ilpSolution.toJson)
  }
}

/** A container object to generate IlpSolution object based on the ILP model output */
object IlpSolutionFactory extends Logging {
  /** config: a threshold above which alignment is considered active */
  private val alignmentThreshold = 0.999

  /** Process the solution found by SCIP to deduce which parts of the question + tables align with
    * which other parts. This information can then be visualized or presented in another format.
    * @param allVariables all core decision variables in the ILP model
    * @param scipSolver a reference to the SCIP solver object
    * @param question the question
    * @param tables the tables used
    * @return an AlignmentSolution object
    */
  def makeIlpSolution(allVariables: AllVariables, scipSolver: ScipInterface,
    question: TableQuestion, tables: Seq[Table]): IlpSolution = {
    val qConsAlignments = question.questionCons.map(new TermAlignment(_))
    val choiceAlignments = question.choices.map(new TermAlignment(_))
    val tableAlignments = tables.map { table =>
      val titleAlignments = table.titleRow.map(new TermAlignment(_))
      val contentAlignments = table.contentMatrix.map(_.map(new TermAlignment(_)))
      TableAlignment(titleAlignments, contentAlignments)
    }

    // inter-table alignments
    val interTableAlignmentPairs: IndexedSeq[(TermAlignment, TermAlignment)] = for {
      entry <- allVariables.interTableVariables
      if scipSolver.getSolVal(entry.variable) > alignmentThreshold
    } yield {
      val cell1 = tableAlignments(entry.tableIdx1).contentAlignments(entry.rowIdx1)(entry.colIdx1)
      val cell2 = tableAlignments(entry.tableIdx2).contentAlignments(entry.rowIdx2)(entry.colIdx2)
      (cell1, cell2)
    }

    // intra-table alignments
    val intraTableAlignmentPairs: IndexedSeq[(TermAlignment, TermAlignment)] = for {
      entry <- allVariables.intraTableVariables
      if scipSolver.getSolVal(entry.variable) > alignmentThreshold
    } yield {
      val cell1 = tableAlignments(entry.tableIdx).contentAlignments(entry.rowIdx)(entry.colIdx1)
      val cell2 = tableAlignments(entry.tableIdx).contentAlignments(entry.rowIdx)(entry.colIdx2)
      (cell1, cell2)
    }

    // question table alignments
    val questionTableAlignmentPairs: IndexedSeq[(TermAlignment, TermAlignment)] = for {
      entry <- allVariables.questionTableVariables
      if scipSolver.getSolVal(entry.variable) > alignmentThreshold
    } yield {
      val cell = tableAlignments(entry.tableIdx).contentAlignments(entry.rowIdx)(entry.colIdx)
      val qCons = qConsAlignments(entry.qConsIdx)
      (cell, qCons)
    }

    // question title alignments
    val questionTitleAlignmentPairs: IndexedSeq[(TermAlignment, TermAlignment)] = for {
      entry <- allVariables.questionTitleVariables
      if scipSolver.getSolVal(entry.variable) > alignmentThreshold
    } yield {
      val cell = tableAlignments(entry.tableIdx).titleAlignments(entry.colIdx)
      val qCons = qConsAlignments(entry.qConsIdx)
      (cell, qCons)
    }

    // choice table alignments
    val choiceTableAlignmentPairs: IndexedSeq[(TermAlignment, TermAlignment)] = for {
      entry <- allVariables.qChoiceTableVariables
      if scipSolver.getSolVal(entry.variable) > alignmentThreshold
    } yield {
      val cell = tableAlignments(entry.tableIdx).contentAlignments(entry.rowIdx)(entry.colIdx)
      val qOptCons = choiceAlignments(entry.qConsIdx)
      (cell, qOptCons)
    }

    // populate `alignment' fields of alignment pairs with a unique alignmentId
    val allAlignmentPairs = interTableAlignmentPairs ++ intraTableAlignmentPairs ++
      questionTableAlignmentPairs ++ questionTitleAlignmentPairs ++ choiceTableAlignmentPairs
    val termToAlignmentId = allAlignmentPairs.zipWithIndex.flatMap {
      case ((strAlign1, strAlign2), alignmentId) =>
        Seq((strAlign1, alignmentId), (strAlign2, alignmentId))
    }
    // TODO: is it possible to do with without cell.alignment being an ArrayBuffer?
    // Note that it is assigned a value exactly once, with the "++=" below.
    // One way could be to keep separate matrices for strings and for alignment IDs
    // rather than a joint pair, StringAlignmentPair.
    termToAlignmentId.groupBy(t => System.identityHashCode(t._1)).foreach {
      case (_, termWithAlignmentIds) =>
        termWithAlignmentIds.head._1.alignmentIds ++= termWithAlignmentIds.map(_._2)
    }

    // create a new question alignment object
    val questionAlignment = QuestionAlignment(qConsAlignments, choiceAlignments)

    // choose answer choice and its score
    logger.debug("the number of the choices = " + allVariables.qChoiceTableVariables.length)
    val (bestChoiceIdx, bestChoiceScore) = if (allVariables.qChoiceTableVariables.nonEmpty) {
      val idx = allVariables.qChoiceTableVariables.map { choice =>
        scipSolver.getSolVal(choice.variable)
      }.zipWithIndex.maxBy(_._1)._2
      val choiceIdx = allVariables.qChoiceTableVariables(idx).qConsIdx
      (choiceIdx, scipSolver.getPrimalbound)
    } else {
      // The default, helpful for initial debugging
      (0, 0d)
    }

    val solutionQuality = SolutionQuality(scipSolver.getStatus, scipSolver.getPrimalbound,
      scipSolver.getDualbound, scipSolver.getGap)

    // return the alignment solution
    IlpSolution(bestChoiceIdx, bestChoiceScore, tableAlignments, questionAlignment, solutionQuality)
  }

  /** Load all tables, if and when needed */
  private lazy val tables = TableInterface.loadTables()

  /** Object to generate random values */
  private val r = scala.util.Random

  /** Generate up to maxNVals random integers, each less than maxVal */
  private def nextRandInts(maxNVals: Int, maxVal: Int): Seq[Int] = {
    val nvals = r.nextInt(maxNVals)
    (0 until nvals).map(_ => r.nextInt(maxVal))
  }

  /** Generate a random alignment solution object for visualizer testing */
  def makeRandomIlpSolution: IlpSolution = {
    // A sample question
    val questionChunks = Array("In", "New York State", "the", "shortest", "period",
      "of", "daylight", "occurs", "during", "which", "month")
    // Sample answer choices
    val choices = Array("January", "December", "June", "July")

    // Parameterize random integer generator
    val maxNVals = 4
    val maxVal = 13
    def getRandInts = nextRandInts(maxNVals, maxVal)

    // Create random alignments
    val qConsAlignments = questionChunks.map(TermAlignment(_, getRandInts.to[ArrayBuffer]))
    val choiceAlignments = choices.map(TermAlignment(_, getRandInts.to[ArrayBuffer]))
    val questionAlignment = QuestionAlignment(qConsAlignments, choiceAlignments)
    val tablesAlignments = tables.map { table =>
      val titleAlignments = table.titleRow.map(TermAlignment(_, getRandInts.to[ArrayBuffer]))
      val contentAlignments = table.contentMatrix.map { row =>
        row.map(TermAlignment(_, getRandInts.to[ArrayBuffer]))
      }
      TableAlignment(titleAlignments, contentAlignments)
    }

    // Select an arbitrary best choice and corresponding score
    val bestChoice = 1
    val bestChoiceScore = 0.5

    // Instantiate an arbitrary solution quality metric
    val solutionQuality = SolutionQuality(IlpStatusFeasible, 0.5d, 1d, 1d)

    // Return the solution with random alignments
    IlpSolution(bestChoice, bestChoiceScore, tablesAlignments, questionAlignment, solutionQuality)
  }
}

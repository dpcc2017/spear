package spear.plans

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spear.expressions.{Alias, Attribute, Expression, ExpressionID, InternalAlias, NamedExpression}
import spear.plans.logical.annotations.Explain
import spear.reflection.constructorParams
import spear.trees.TreeNode
import spear.types.StructType

trait QueryPlan[Plan <: TreeNode[Plan]] extends TreeNode[Plan] { self: Plan =>
  def output: Seq[Attribute]

  lazy val outputSet: Set[Attribute] = output.toSet

  lazy val schema: StructType = StructType fromAttributes output

  lazy val references: Seq[Attribute] = expressions.flatMap { (_: Expression).references }.distinct

  lazy val referenceSet: Set[Attribute] = references.toSet

  lazy val referenceIDs: Set[ExpressionID] = referenceSet map { _.expressionID }

  def expressions: Seq[Expression] = productIterator.flatMap {
    case element: Expression       => element :: Nil
    case Some(element: Expression) => element :: Nil
    case element: Traversable[_]   => element collect { case e: Expression => e }
    case _                         => Nil
  }.toSeq

  def transformExpressionsDown(rule: ExpressionRule): Plan = withExpressions {
    expressions map {
      _ transformDown rule
    }
  }

  def transformExpressionsUp(rule: ExpressionRule): Plan = withExpressions {
    expressions map {
      _ transformUp rule
    }
  }

  def collectFromExpressionsDown[T](rule: PartialFunction[Expression, T]): Seq[T] = {
    val builder = ArrayBuffer.newBuilder[T]

    transformExpressionsDown {
      case e: Expression if rule isDefinedAt e =>
        builder += rule(e)
        e
    }

    builder.result()
  }

  def collectFromExpressionsUp[T](rule: PartialFunction[Expression, T]): Seq[T] = {
    val builder = ArrayBuffer.newBuilder[T]

    transformExpressionsUp {
      case e: Expression if rule isDefinedAt e =>
        builder += rule(e)
        e
    }

    builder.result()
  }

  private def explainParamValue(value: Any, show: Any => String): Option[String] = value match {
    case arg if children contains arg =>
      // Hides child nodes since they will be printed as sub-tree nodes
      None

    case arg: Seq[_] if arg forall children.contains =>
      // If a `Seq` contains only child nodes, hides it entirely.
      None

    case arg: Seq[_] =>
      Some {
        arg flatMap {
          case e if children contains e => None
          case e                        => Some(show(e))
        } mkString ("[", ", ", "]")
      }

    case arg: Some[_] =>
      arg flatMap {
        case e: Any if children contains e => None
        case e: Any                        => Some("Some(" + show(e) + ")")
      }

    case arg =>
      Some(show(arg))
  }

  override def caption: String = {
    /**
     * String pairs representing constructor parameters of this $tree. Parameters annotated with
     * `Explain(hidden = true)` are not included here.
     */
    def doExplainParams(show: Any => String): Seq[(String, String)] = {
      val argNames: List[String] = constructorParams(getClass) map { _.name.toString }
      val annotations = constructorParamExplainAnnotations

      (argNames, productIterator.toSeq, annotations).zipped.map {
        case (_, _, maybeAnnotated) if maybeAnnotated exists { _.hidden() } =>
          None

        case (name, value, _) =>
          explainParamValue(value, show) map { name -> _ }
      }.flatten
    }

    def explainParams(show: Any => String): Seq[(String, String)] = {
      val remainingExpressions = mutable.Stack(expressions.indices: _*)

      doExplainParams {
        case _: Expression => s"$$${remainingExpressions.pop()}"
        case other         => other.toString
      }
    }

    val builder = mutable.StringBuilder.newBuilder

    builder ++= {
      val args = explainParams(_.toString).map { _.productIterator mkString "=" } mkString ", "
      val arrow = "\u21d2"
      val outputString = outputStrings mkString ("[", ", ", "]")
      Seq(nodeName, args, arrow, outputString) filter { _.nonEmpty } mkString " "
    }

    if (nestedTrees.nonEmpty) {
      builder += '\n'
      nestedTrees.init foreach { _.buildPrettyTree(2, children.isEmpty :: false :: Nil, builder) }
      nestedTrees.last.buildPrettyTree(2, children.isEmpty :: true :: Nil, builder)
    }

    builder.toString()
  }

  /**
   * Returns string representations of each output attribute of this query plan.
   */
  protected def outputStrings: Seq[String] = output map { _.debugString }

  protected def nestedTrees: Seq[TreeNode[_]] = expressions.zipWithIndex.map {
    case (e, index) => QueryPlan.ExpressionNode(index, e)
  } ++ {
    val nestedTreeMarks = constructorParamExplainAnnotations map { _ exists { _.nestedTree() } }
    productIterator.toSeq zip nestedTreeMarks collect { case (tree: TreeNode[_], true) => tree }
  }

  private type ExpressionRule = PartialFunction[Expression, Expression]

  protected def withExpressions(newExpressions: Seq[Expression]): Plan = {
    assert(newExpressions.length == expressions.length)

    val remainingNewExpressions = newExpressions.toBuffer
    var changed = false

    def popAndCompare(e: Expression): Expression = {
      val newExpression = remainingNewExpressions.head
      remainingNewExpressions.remove(0)
      changed = changed || !(newExpression same e)
      newExpression
    }

    val newArgs = productIterator.map {
      case e: Expression =>
        popAndCompare(e)

      case Some(e: Expression) =>
        Some(popAndCompare(e))

      case arg: Traversable[_] =>
        arg.map {
          case e: Expression => popAndCompare(e)
          case other         => other
        }.toSeq

      case arg: AnyRef =>
        arg

      case null =>
        null
    }.toArray

    if (changed) makeCopy(newArgs) else this
  }

  private lazy val constructorParamExplainAnnotations: Seq[Option[Explain]] =
    getClass.getDeclaredConstructors.head.getParameterAnnotations.map {
      _ collectFirst { case a: Explain => a }
    }
}

object QueryPlan {
  case class ExpressionNode(index: Int, expression: Expression)
    extends TreeNode[ExpressionNode] {

    override def children: Seq[ExpressionNode] = Nil

    override def caption: String = s"$$$index: ${expression.debugString}"
  }

  def normalizeExpressionIDs[Plan <: QueryPlan[Plan]](plan: Plan): Plan = {
    // Traverses the plan tree in post-order and collects all `NamedExpression`s with an ID.
    val namedExpressionsWithID = plan.collectUp {
      case node =>
        node collectFromExpressionsUp {
          case e: NamedExpression if e.isResolved => e
          case e: Alias                           => e
          case e: InternalAlias                   => e
        }
    }.flatten.distinct

    val ids = namedExpressionsWithID.map { _.expressionID }.distinct

    val groupedByID = namedExpressionsWithID.groupBy { _.expressionID }.toSeq sortBy {
      // Sorts by ID occurrence order to ensure determinism.
      case (expressionID, _) => ids indexOf expressionID
    }

    val rewrite = for {
      ((_, namedExpressions), index) <- groupedByID.zipWithIndex.toMap
      named <- namedExpressions
    } yield (named: Expression) -> (named withID ExpressionID(index))

    plan transformDown {
      case node => node transformExpressionsDown rewrite
    }
  }
}

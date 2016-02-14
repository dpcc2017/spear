package scraper.expressions.dsl

import scala.language.implicitConversions

import scraper.expressions._
import scraper.types.DataType

trait ExpressionDSL
  extends ArithmeticExpressionDSL
  with ComparisonDSL
  with LogicalOperatorDSL { this: Expression =>

  def as(alias: String): Alias = Alias(this, alias)

  def as(alias: Symbol): Alias = Alias(this, alias.name)

  def cast(dataType: DataType): Cast = Cast(this, dataType)

  def isNull: IsNull = IsNull(this)

  def notNull: IsNotNull = IsNotNull(this)

  def asc(nullsLarger: Boolean): SortOrder = SortOrder(this, Ascending, nullsLarger)

  def desc(nullsLarger: Boolean): SortOrder = SortOrder(this, Descending, nullsLarger)

  def in(list: Seq[Expression]): In = In(this, list)

  def in(first: Expression, rest: Expression*): In = this in (first +: rest)
}

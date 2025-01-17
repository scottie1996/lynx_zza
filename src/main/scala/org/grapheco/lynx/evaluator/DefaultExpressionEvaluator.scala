package org.grapheco.lynx.evaluator

import org.grapheco.lynx.procedure.{ProcedureException, ProcedureExpression, ProcedureRegistry}
import org.grapheco.lynx.types.composite.{LynxList, LynxMap}
import org.grapheco.lynx.types.property._
import org.grapheco.lynx.types.structural.{HasProperty, LynxNode, LynxNodeLabel, LynxPath, LynxPropertyKey, LynxRelationship, LynxRelationshipType}
import org.grapheco.lynx.types.time.{LynxDate, LynxDateTime, LynxDuration, LynxLocalDateTime, LynxTemporalValue, LynxTime}
import org.grapheco.lynx.types.{LynxValue, TypeSystem}
import org.grapheco.lynx.{LynxException, LynxType}
import org.grapheco.lynx.runner.{GraphModel, NodeFilter, RelationshipFilter}
import org.opencypher.v9_0.expressions.functions.{Collect, Id}
import org.opencypher.v9_0.expressions._
import org.opencypher.v9_0.util.symbols.{CTAny, CTBoolean, CTFloat, CTInteger, CTList, CTString, ListType}

import scala.math.abs
import scala.util.matching.Regex

/**
 * @ClassName DefaultExpressionEvaluator
 * @Description
 * @Author Hu Chuan
 * @Date 2022/4/27
 * @Version 0.1
 */
class DefaultExpressionEvaluator(graphModel: GraphModel, types: TypeSystem, procedures: ProcedureRegistry) extends ExpressionEvaluator {

  override def typeOf(expr: Expression, definedVarTypes: Map[String, LynxType]): LynxType = {
    expr match {
      case Parameter(name, parameterType) => parameterType
      case _: BooleanLiteral => CTBoolean
      case _: StringLiteral => CTString
      case _: IntegerLiteral => CTInteger
      case _: DoubleLiteral => CTFloat
      case CountStar() => CTInteger
      case ProcedureExpression(funcInov) => funcInov.function match {
        case Collect => CTList(typeOf(funcInov.args.head, definedVarTypes))
        case Id => CTInteger
        case _ => CTAny
      }
      case ContainerIndex(expr, _) => typeOf(expr, definedVarTypes) match {
        case ListType(cypherType) => cypherType
        case _ => CTAny
      }
      case Variable(name) => definedVarTypes(name)
      case _ => CTAny
    }
  }

  protected def evalPathStep(step: PathStep)(implicit ec: ExpressionContext): LynxPath = {
    step match {
      case NilPathStep => LynxPath.EMPTY
      case f: NodePathStep => {
        val path = f.next match {
          case m: MultiRelationshipPathStep => LynxPath.EMPTY
          case _ => LynxPath.startPoint(eval(f.node).asInstanceOf[LynxNode])
        }
        path.append(evalPathStep(f.next))
      }
      case m: MultiRelationshipPathStep => (m.rel match {
        case Variable(r) => ec.vars(r + "LINK")
        case _ => throw ProcedureException("")
      }).asInstanceOf[LynxPath]
        //.append(eval(m.toNode.get).asInstanceOf[LynxNode])
        //.append(evalPathStep(m.next))
      case s: SingleRelationshipPathStep => LynxPath.singleRel(eval(s.rel).asInstanceOf[LynxRelationship])
        .append(eval(s.toNode.get).asInstanceOf[LynxNode])
        .append(evalPathStep(s.next))
    }
  }

  private def safeBinaryOp(lhs: Expression, rhs: Expression, op: (LynxValue, LynxValue) => LynxValue)(implicit ec: ExpressionContext): Option[LynxValue] = {
    val l = eval(lhs)
    if (l.value == null) return None
    val r = eval(rhs)
    if (r.value == null) return None
    Some(op(l, r))
  }

  def judge(value: LynxValue): Boolean = value match {
    case LynxList(l) => l.nonEmpty
    case LynxBoolean(v) => v
    case LynxNull => false
    case o => throw EvaluatorTypeMismatch(o.lynxType.toString, "Boolean")
  }

  override def eval(expr: Expression)(implicit ec: ExpressionContext): LynxValue = {

    expr match {
      case HasLabels(expression, labels) =>
        eval(expression) match {
          case node: LynxNode => LynxBoolean(labels.forall(label => node.labels.map(_.value).contains(label.name)))
        }

      case pe: PathExpression => evalPathStep(pe.step)

      case ContainerIndex(expr, idx) => { //fixme: what's this
        {
          (eval(expr), eval(idx)) match {
            case (hp: HasProperty, i: LynxString) => hp.property(LynxPropertyKey(i.value))
            case (lm: LynxMap, key: LynxString) => lm.value.get(key.value)
            case (lm: LynxList, i: LynxInteger) =>
              if (i.value.toInt < 0)
                lm.value.reverse.lift(abs(i.value.toInt) - 1)
              else
                lm.value.lift(i.value.toInt)
          }
        }.getOrElse(LynxNull)
      }

      case fe: ProcedureExpression => {
        if (fe.aggregating) LynxValue(fe.args.map(eval(_)))
        else fe.procedure.execute(fe.args.map(eval(_)))
      }


      case Add(lhs, rhs) =>
        safeBinaryOp(lhs, rhs, (lvalue, rvalue) =>
          // TODO other cases
          (lvalue, rvalue) match {
            case (a: LynxNumber, b: LynxNumber) => a + b
            case (a: LynxString, b: LynxString) => LynxString(a.value + b.value)
            case (a: LynxString, b: LynxValue) => LynxString(a.value + b.toString)
            case (a: LynxList, b: LynxList) => LynxList(a.value ++ b.value)
            case (a: LynxLocalDateTime, b: LynxDuration) => a.plusDuration(b)
            case (a: LynxDate, b: LynxDuration) => a.plusDuration(b)
            case (a: LynxTime, b: LynxDuration) => a.plusDuration(b)
            case (a: LynxDuration, b: LynxDuration) => a.plusByMap(b)
            case (a: LynxDateTime, b: LynxDuration) => a.plusDuration(b)
          }).getOrElse(LynxNull)

      case Subtract(lhs, rhs) =>
        safeBinaryOp(lhs, rhs, (lvalue, rvalue) =>
          (lvalue, rvalue) match {
            case (a: LynxNumber, b: LynxNumber) => a - b
            case (a: LynxLocalDateTime, b: LynxDuration) => a.minusDuration(b)
            case (a: LynxDate, b: LynxDuration) => a.minusDuration(b)
            case (a: LynxDuration, b: LynxDuration) => a.minusByMap(b)
            case (a: LynxTime, b: LynxDuration) => a.minusDuration(b)
            case (a: LynxDateTime, b: LynxDuration) => a.minusDuration(b)
          }).getOrElse(LynxNull)

      case Ors(exprs) => LynxBoolean(exprs.map(eval(_)).exists(judge))

      case Ands(exprs) => LynxBoolean(exprs.map(eval).forall(judge))

      case Or(lhs, rhs) => LynxBoolean(judge(eval(lhs)) || judge(eval(rhs)))

      case And(lhs, rhs) => LynxBoolean(judge(eval(lhs)) && judge(eval(rhs)))

      case sdi: IntegerLiteral => LynxInteger(sdi.value)


      case Multiply(lhs, rhs) => { //todo add normal multi
        (eval(lhs), eval(rhs)) match {
          case (n: LynxNumber, m: LynxNumber) => { //todo add aggregating multi
            (n, m) match {
              case (d1: LynxFloat, d2: LynxFloat) => LynxFloat(d1.value * d2.value)
              case (d1: LynxFloat, d2: LynxInteger) => LynxFloat(d1.value * d2.value)
              case (d1: LynxInteger, d2: LynxFloat) => LynxFloat(d1.value * d2.value)
              case (d1: LynxInteger, d2: LynxInteger) => LynxInteger(d1.value * d2.value)
            }
          }
          case (d1: LynxDuration, d2: LynxInteger) => d1.multiplyInt(d2)
        }
      }

      case Divide(lhs, rhs) => {
        (eval(lhs), eval(rhs)) match {
          case (n: LynxNumber, m: LynxNumber) => n / m
          case (n: LynxDuration, m: LynxInteger) => n.divideInt(m)
          case (n, m) => throw EvaluatorTypeMismatch(n.lynxType.toString, "LynxNumber")
        }
      }

      case Modulo(lhs, rhs) => {
        (eval(lhs), eval(rhs)) match {
          case (n: LynxInteger, m: LynxInteger) => {
            n % m
          }
          case (n, m) => throw EvaluatorTypeMismatch(n.lynxType.toString, "LynxInteger")
        }
      }

      case NotEquals(lhs, rhs) => eval(Equals(lhs, rhs)(expr.position)) match {
        case LynxBoolean(v) => LynxBoolean(!v)
        case LynxNull => LynxNull
      }

      case Equals(lhs, rhs) => (eval(lhs), eval(rhs)) match {
        case (LynxNull, _) => LynxNull
        case (_, LynxNull) => LynxNull
        case (l, r) => LynxBoolean(l == r)
      }

      case GreaterThan(lhs, rhs) =>
        safeBinaryOp(lhs, rhs, (lvalue, rvalue) => {
          (lvalue, rvalue) match {
            // TODO: Make sure the
            case (a: LynxNumber, b: LynxNumber) => LynxBoolean(a.number.doubleValue() > b.number.doubleValue())
            case (a: LynxString, b: LynxString) => LynxBoolean(a.value > b.value)
            case _ => if (lvalue.getClass != rvalue.getClass) LynxNull else LynxBoolean(lvalue > rvalue)
          }
        }).getOrElse(LynxNull)

      case GreaterThanOrEqual(lhs, rhs) =>
        safeBinaryOp(lhs, rhs, (lvalue, rvalue) => {
          LynxBoolean(lvalue >= rvalue)
        }).getOrElse(LynxNull)

      case LessThan(lhs, rhs) =>
        eval(GreaterThan(rhs, lhs)(expr.position))

      case LessThanOrEqual(lhs, rhs) =>
        eval(GreaterThanOrEqual(rhs, lhs)(expr.position))

      case Not(in) => LynxBoolean(!judge(eval(in)))

      case IsNull(lhs) => {
        eval(lhs) match {
          case LynxNull => LynxBoolean(true)
          case _ => LynxBoolean(false)
        }
      }
      case IsNotNull(lhs) => {
        eval(lhs) match {
          case LynxNull => LynxBoolean(false)
          case _ => LynxBoolean(true)
        }
      }

      case v: Literal =>
        types.wrap(v.value)

      case v: ListLiteral =>
        LynxValue(v.expressions.map(eval(_)))

      case Variable(name) =>
        ec.vars(name)

      case Property(src, PropertyKeyName(name)) =>
        eval(src) match {
          case LynxNull => LynxNull
          case hp: HasProperty => hp.property(LynxPropertyKey(name)).getOrElse(LynxNull)
          case time: LynxDateTime => LynxValue(name match { //TODO add HasProperty into LynxDateTime and remove this case.
            case "epochMillis" => time.epochMillis
          })
          case map: LynxMap => map.get(name).getOrElse(LynxNull)
        }

      case In(lhs, rhs) =>
        eval(rhs) match {
          case LynxList(list) => LynxBoolean(list.contains(eval(lhs))) //todo add literal in list[func] test case
        }

      case Parameter(name, parameterType) =>
        types.wrap(ec.param(name))

      case RegexMatch(lhs, rhs) => {
        (eval(lhs), eval(rhs)) match {
          case (LynxString(str), LynxString(regStr)) => {
            val regex = new Regex(regStr) // TODO: opt
            val res = regex.findFirstMatchIn(str)
            if (res.isDefined) LynxBoolean(true)
            else LynxBoolean(false)
          }
          case (LynxNull, _) => LynxBoolean(false)
        }
      }

      case StartsWith(lhs, rhs) => {
        (eval(lhs), eval(rhs)) match {
          case (LynxString(str), LynxString(startStr)) => LynxBoolean(str.startsWith(startStr))
          case (LynxNull, _) => LynxBoolean(false)
        }
      }

      case EndsWith(lhs, rhs) => {
        (eval(lhs), eval(rhs)) match {
          case (LynxString(str), LynxString(endStr)) => LynxBoolean(str.endsWith(endStr))
          case (LynxNull, _) => LynxBoolean(false)
        }
      }

      case Contains(lhs, rhs) => {
        (eval(lhs), eval(rhs)) match {
          case (LynxString(str), LynxString(containsStr)) => LynxBoolean(str.contains(containsStr))
          case (LynxNull, _) => LynxBoolean(false)
        }
      }

      case CaseExpression(expression, alternatives, default) => {
        if (expression.isDefined) {
          val evalValue = eval(expression.get)
          evalValue match {
            case LynxNull => LynxNull
            case _ => {
              val expr = alternatives.find(
                alt => {
                  // case [xxx] when [yyy] then 1
                  // if [yyy] is a boolean, then [xxx] no use
                  val res = eval(alt._1)
                  if (res.isInstanceOf[LynxBoolean]) res.value.asInstanceOf[Boolean]
                  else eval(alt._1) == evalValue
                })
                .map(_._2).getOrElse(default.get)

              eval(expr)
            }
          }
        }
        else {
          val expr = alternatives.find(alt => eval(alt._1).value.asInstanceOf[Boolean]).map(_._2).getOrElse {
            default.orNull
          }
          if (expr != null) eval(expr)
          else LynxNull
        }
      }

      case MapExpression(items) => LynxMap(items.map { case (prop, expr) => prop.name -> eval(expr) }.toMap)

      //Only One-hop path-pattern is supported now
      case PatternExpression(pattern) => { // FIXME only one-hop supported now.

        val rightNode: NodePattern = pattern.element.rightNode
        val relationship: RelationshipPattern = pattern.element.relationship
        val leftNode: NodePattern = if (pattern.element.element.isSingleNode) {
          pattern.element.element.asInstanceOf[NodePattern]
        } else {
          throw EvaluatorException(s"PatternExpression is not fully supproted.")
        }

        //        val exist: Boolean = graphModel.paths(
        //          _transferNodePatternToFilter(leftNode),
        //          _transferRelPatternToFilter(relationship),
        //          _transferNodePatternToFilter(rightNode),
        //          relationship.direction, 1, 1
        //        ).exists(path => leftNode.variable.map(eval).forall(_.equals(path.startNode.orNull))  &&
        //         rightNode.variable.map(eval).forall(_.equals(path.endNode.orNull)))
        //        LynxBoolean(exist)
        LynxList(graphModel.paths(
          _transferNodePatternToFilter(leftNode),
          _transferRelPatternToFilter(relationship),
          _transferNodePatternToFilter(rightNode),
          relationship.direction, 1, 1
        ).filter(path => leftNode.variable.map(eval).forall(_.equals(path.startNode.orNull)) &&
          rightNode.variable.map(eval).forall(_.equals(path.endNode.orNull))).toList)
      }

      case ip: IterablePredicateExpression => {
        val variable = ip.variable
        val predicate = ip.innerPredicate
        val predicatePass: ExpressionContext => Boolean = if (predicate.isDefined) {
          ec => eval(predicate.get)(ec) == LynxBoolean.TRUE
        } else { _ => true } // if predicate not defined, should must return true?

        eval(ip.expression) match {
          case list: LynxList => {
            val ecList = list.v.map(i => ec.withVars(ec.vars + (variable.name -> i)))
            val result = ip match {
              case _: AllIterablePredicate => ecList.forall(predicatePass)
              case _: AnyIterablePredicate => ecList.exists(predicatePass)
              case _: NoneIterablePredicate => ecList.forall(predicatePass.andThen(!_))
              case _: SingleIterablePredicate => ecList.indexWhere(predicatePass) match {
                case -1 => false // none
                case i => !ecList.drop(i + 1).exists(predicatePass) // only one!
              }
            }
            LynxBoolean(result)
          }
          case _ => throw ProcedureException("The expression must returns a list.")
        }
      }

      case Pow(lhs, rhs) => (eval(lhs), eval(rhs)) match {
        case (number: LynxInteger, exponent: LynxInteger) => LynxInteger(Math.pow(number.value, exponent.value).toLong)
        case (number: LynxNumber, exponent: LynxNumber) => LynxFloat(Math.pow(number.toDouble, exponent.toDouble))
        case _ => throw ProcedureException("The expression must returns tow numbers.")
      }

      case ListSlice(list, from, to) => eval(list) match {
        case LynxList(list) => LynxList((from.map(eval), to.map(eval)) match {
          case (Some(LynxInteger(i)), Some(LynxInteger(j))) =>
            val left = if (i.toInt < 0) list.length + i.toInt else i.toInt
            val right = if (j.toInt < 0) list.length + j.toInt else j.toInt
            list.slice(left, right)
          case (Some(LynxInteger(i)), _) =>
            val idx = if (i.toInt < 0) list.length + i.toInt else i.toInt
            list.drop(idx)
          case (_, Some(LynxInteger(j))) =>
            val right = if (j.toInt < 0) list.length + j.toInt else j.toInt
            list.slice(0, right)
          case (_, _) => throw ProcedureException("The range must is a integer.")
        })
        case _ => throw ProcedureException("The expression must returns a list.")
      }

      case ReduceExpression(scope, init, list) => {
        val variableName = scope.variable.name
        val accumulatorName = scope.accumulator.name
        var accumulatorValue = eval(init)
        eval(list) match {
          case list: LynxList => {
            list.v.foreach(listValue => accumulatorValue = eval(scope.expression)(ec.withVars(ec.vars ++ Map(variableName -> listValue, accumulatorName -> accumulatorValue)))
            )
            accumulatorValue
          }
          case _ => throw ProcedureException("The expression must returns a list.")
        }
      }

      case ListComprehension(scope, expression) => {
        val variableName = scope.variable.name
        eval(expression) match {
          case list: LynxList => {
            var result = list
            if (scope.innerPredicate.isDefined) {
              result = LynxList(list.v.filter {
                listValue => eval(scope.innerPredicate.get)(ec.withVars(ec.vars + (variableName -> listValue))).asInstanceOf[LynxBoolean].value
              })
            }

            if (scope.extractExpression.isDefined) {
              result = result.map {
                listValue =>
                  eval(scope.extractExpression.get)(ec.withVars(ec.vars + (variableName -> listValue)))
              }
            }

            result
          }
          case _ => throw ProcedureException("The expression must returns a list.")
        }
      }

      case DesugaredMapProjection(name, items, includeAllProps) => LynxMap(items.map(item => item.key.name -> eval(item.exp)(ec)).toMap)

      /*
        eg: [(a)-[r:ACTION_IN]->(b) WHERE b:Movie | b.released]
        namedPath: None
        pattern: (a)-[r:ACTION_IN]->(b)
        predicate: HasLabels(b, Movie)
        projection: Property(b, released)
       */
      case PatternComprehension(namedPath: Option[LogicalVariable], pattern: RelationshipsPattern,
      predicate: Option[Expression], projection: Expression) => {
        // TODO
        ???
      }
    }
  }

  override def aggregateEval(expr: Expression)(ecs: Seq[ExpressionContext]): LynxValue = {
    expr match {
      case fe: ProcedureExpression =>
        if (fe.aggregating) {
          val listArgs = {
            if (fe.distinct) {
              LynxList(ecs.map(eval(fe.args.head)(_)).distinct.toList)
            } else {
              LynxList(ecs.map(eval(fe.args.head)(_)).toList)
            }
          } //todo: ".head": any multi-args situation?
          val otherArgs = fe.args.drop(1).map(eval(_)(ecs.head)) // 2022.09.15: Added handling of other args, but the default first one is list
          fe.procedure.execute(Seq(listArgs) ++ otherArgs)
        } else {
          throw ProcedureException("aggregate by nonAggregating procedure.")
        }
      case CountStar() => LynxInteger(ecs.length)
    }
  }

  def _transferNodePatternToFilter(nodePattern: NodePattern)(implicit ec: ExpressionContext): NodeFilter = {
    val properties: Map[LynxPropertyKey, LynxValue] = nodePattern.properties match {
      case None => Map()
      case Some(MapExpression(seqOfProps)) => seqOfProps.map {
        case (propertyKeyName, propValueExpr) => LynxPropertyKey(propertyKeyName.name) -> LynxValue(eval(propValueExpr))
      }.toMap
    }
    NodeFilter(nodePattern.labels.map(label => LynxNodeLabel(label.name)), properties)
  }

  def _transferRelPatternToFilter(relationshipPattern: RelationshipPattern)(implicit ec: ExpressionContext): RelationshipFilter = {
    val props: Map[LynxPropertyKey, LynxValue] = relationshipPattern.properties match {
      case None => Map()
      case Some(MapExpression(seqOfProps)) => seqOfProps.map {
        case (propertyKeyName, propValueExpr) => LynxPropertyKey(propertyKeyName.name) -> LynxValue(eval(propValueExpr))
      }.toMap
    }
    RelationshipFilter(relationshipPattern.types.map(relType => LynxRelationshipType(relType.name)), props)
  }
}

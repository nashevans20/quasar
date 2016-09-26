/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.mongodb

import quasar.Predef._
import quasar._, Planner.{PlannerError, InternalError}
import quasar.std._
import quasar.fp._
import quasar.fp.ski._
import quasar.fs.DataCursor
import quasar.std.{StdLibSpec, StdLibTestRunner}
import quasar.physical.mongodb.fs._, bsoncursor._
import quasar.physical.mongodb.planner.MongoDbPlanner
import quasar.physical.mongodb.workflow._

import matryoshka._, Recursive.ops._
import org.specs2.execute._
import scalaz._, Scalaz._
import shapeless.Nat

/** Test the implementation of the standard library for MongoDb's aggregation
  * pipeline (aka ExprOp).
  *
  * NB: because compilation to the Expression AST can't currently be separated
  * from the rest of the MongoDb planner, this test runs the whole planner and
  * then simply fails if it finds that the generated plan required map-reduce.
  */
class MongoDbExprStdLibSpec extends MongoDbStdLibSpec {
  val notHandled = Skipped("not implemented in aggregation")

  /** Identify constructs that are expected not to be implemented in the pipeline. */
  def shortCircuit[N <: Nat](backend: BackendName, func: GenericFunc[N], args: List[Data]): Result \/ Unit = func match {
    case StringLib.Length   => notHandled.left
    case StringLib.Integer  => notHandled.left
    case StringLib.Decimal  => notHandled.left
    case StringLib.ToString => notHandled.left

    case DateLib.TimeOfDay if is2_6(backend) => Skipped("not implemented in aggregation on MongoDB 2.6").left

    case MathLib.Power if !is3_2(backend) => Skipped("not implemented in aggregation on MongoDB < 3.2").left

    case _                  => ().right
  }

  def compile(queryModel: MongoQueryModel, coll: Collection, lp: Fix[LogicalPlan])
      : PlannerError \/ (Crystallized[WorkflowF], BsonField.Name) = {
    val wrapped =
      Fix(StructuralLib.MakeObject(
        LogicalPlan.Constant(Data.Str("result")),
        lp))

    val ctx = QueryContext(queryModel, κ(None), κ(None))

    MongoDbPlanner.plan(wrapped, ctx).run.value
      .flatMap { wf =>
        val singlePipeline = wf.op.cata[Boolean] {
          case IsSource(_)   => true
          case IsPipeline(p) => p.src
          case _             => false
        }
        if (singlePipeline) wf.right else InternalError("compiled to map-reduce").left
      }
      .strengthR(BsonField.Name("result"))
  }
}

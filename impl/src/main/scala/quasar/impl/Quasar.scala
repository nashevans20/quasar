/*
 * Copyright 2020 Precog Data
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

package quasar.impl

import slamdata.Predef._

import quasar.{Condition, RateLimiting}
import quasar.api.QueryEvaluator
import quasar.api.datasource.{DatasourceRef, DatasourceType, Datasources}
import quasar.api.destination.{DestinationRef, DestinationType, Destinations}
import quasar.api.discovery.{Discovery, SchemaConfig}
import quasar.api.push.ResultPush
import quasar.api.resource.{ResourcePath, ResourcePathType}
import quasar.api.table.{TableRef, Tables}
import quasar.api.scheduler.{Scheduler, Schedulers, SchedulerRef, SchedulerModule}
import quasar.common.PhaseResultTell
import quasar.connector.{QueryResult, ResourceSchema}
import quasar.connector.datasource.Datasource
import quasar.connector.destination.{Destination, DestinationModule}
import quasar.connector.evaluate._
import quasar.connector.render.ResultRender
import quasar.contrib.std.uuid._
import quasar.ejson.implicits._
import quasar.impl.datasource.{AggregateResult, CompositeResult}
import quasar.impl.datasources._
import quasar.impl.datasources.middleware._
import quasar.impl.destinations._
import quasar.impl.discovery.DefaultDiscovery
import quasar.impl.evaluate._
import quasar.impl.implicits._
import quasar.impl.push.DefaultResultPush
import quasar.impl.scheduler._
import quasar.impl.storage.IndexedStore
import quasar.impl.table.DefaultTables
import quasar.qscript.{construction, Map => QSMap}

import java.util.UUID
import scala.concurrent.ExecutionContext

import argonaut.Json
import argonaut.JsonScalaz._

import cats.{~>, Functor, Show}
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.kernel.Hash
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.show._

import fs2.Stream
import fs2.job.JobManager

import matryoshka.data.Fix

import org.slf4s.Logging

import shims.{monadToScalaz, functorToCats, functorToScalaz, orderToScalaz, showToCats, equalToCats}

final class Quasar[F[_], R, C <: SchemaConfig](
    val datasources: Datasources[F, Stream[F, ?], UUID, Json],
    val destinations: Destinations[F, Stream[F, ?], UUID, Json],
    val tables: Tables[F, UUID, SqlQuery],
    val queryEvaluator: QueryEvaluator[Resource[F, ?], SqlQuery, Stream[F, R]],
    val discovery: Discovery[Resource[F, ?], Stream[F, ?], UUID, C],
    val resultPush: ResultPush[F, UUID, UUID],
    val schedulers: Schedulers[F, UUID, UUID, Json, Json])

@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
object Quasar extends Logging {

  type EvalResult[F[_]] = Either[QueryResult[F], AggregateResult[F, QSMap[Fix, QueryResult[F]]]]

  /** What it says on the tin. */
  def apply[F[_]: ConcurrentEffect: ContextShift: MonadQuasarErr: PhaseResultTell: Timer, R, C <: SchemaConfig, A: Hash](
      datasourceRefs: IndexedStore[F, UUID, DatasourceRef[Json]],
      destinationRefs: IndexedStore[F, UUID, DestinationRef[Json]],
      schedulerRefs: IndexedStore[F, UUID, SchedulerRef[Json]],
      tableRefs: IndexedStore[F, UUID, TableRef[SqlQuery]],
      queryFederation: QueryFederation[Fix, Resource[F, ?], QueryAssociate[Fix, Resource[F, ?], EvalResult[F]], Stream[F, R]],
      resultRender: ResultRender[F, R],
      resourceSchema: ResourceSchema[F, C, (ResourcePath, CompositeResult[F, QueryResult[F]])],
      rateLimiting: RateLimiting[F, A],
      byteStores: ByteStores[F, UUID])(
      datasourceModules: List[DatasourceModule],
      destinationModules: List[DestinationModule],
      schedulerModules: List[SchedulerModule])(
      implicit
      ec: ExecutionContext)
      : Resource[F, Quasar[F, R, C]] = {

    val destModules =
      DestinationModules[F](destinationModules)

    val sModules =
      SchedulerModules[F](schedulerModules)


    for {
      _ <- Resource.liftF(warnDuplicates[F, DatasourceModule, DatasourceType](datasourceModules)(_.kind))
      _ <- Resource.liftF(warnDuplicates[F, DestinationModule, DestinationType](destinationModules)(_.destinationType))

      freshUUID = Sync[F].delay(UUID.randomUUID)

      (dsErrors, onCondition) <- Resource.liftF(DefaultDatasourceErrors[F, UUID])

      report = (id: UUID, c: Condition[Throwable]) => c match {
        case cond @ Condition.Abnormal(ex: Exception) => onCondition(id, Condition.abnormal(ex))
        case Condition.Abnormal(_) => ().pure[F]
        case Condition.Normal() => onCondition(id, Condition.normal())
      }

      dsModules =
        DatasourceModules[Fix, F, UUID, A](datasourceModules, rateLimiting, byteStores)
          .withMiddleware(AggregatingMiddleware(_, _))
          .withMiddleware(ConditionReportingMiddleware(report)(_, _))

      dsCache <- ResourceManager[F, UUID, QuasarDatasource[Fix, Resource[F, ?], Stream[F, ?], CompositeResult[F, QueryResult[F]], ResourcePathType]]
      datasources <- Resource.liftF(DefaultDatasources(freshUUID, datasourceRefs, dsModules, dsCache, dsErrors, byteStores))

      destCache <- ResourceManager[F, UUID, Destination[F]]
      destinations <- Resource.liftF(DefaultDestinations(freshUUID, destinationRefs, destCache, destModules))

      sCache <- ResourceManager[F, UUID, Scheduler[F, UUID, Json]]
      schedulers <- Resource.liftF(DefaultSchedulers(freshUUID, schedulerRefs, sCache, sModules))

      lookupRunning =
        (id: UUID) => datasources.quasarDatasourceOf(id).map(_.map(_.modify(reifiedAggregateDs)))

      sqlEvaluator =
        Sql2Compiler[Fix, F]
          .map((_, None))
          .andThen(QueryFederator(ResourceRouter(UuidString, lookupRunning)))
          .mapF(Resource.liftF(_))
          .andThen(queryFederation)

      tables = DefaultTables(freshUUID, tableRefs)

      discovery = DefaultDiscovery(datasources.quasarDatasourceOf, resourceSchema)

      jobManager <- JobManager[F, (UUID, UUID), Nothing]()

      push <- Resource.liftF(DefaultResultPush[F, UUID, UUID, SqlQuery, R](
        tableRefs.lookup,
        sqlEvaluator,
        destinations.destinationOf(_).map(_.toOption),
        jobManager,
        resultRender))

    } yield new Quasar(datasources, destinations, tables, sqlEvaluator, discovery, push, schedulers)
  }

  ////

  private def warnDuplicates[F[_]: Sync, A, B: Show](l: List[A])(fn: A => B): F[Unit] =
    Sync[F].delay(l.groupBy(fn) foreach {
      case (b, grouped) =>
        if (grouped.length > 1) {
          log.warn(s"Found duplicate modules for type ${b.show}")
        }
    })

  private val rec = construction.RecFunc[Fix]

  private def reifiedAggregateDs[F[_]: Functor, G[_], H[_], P <: ResourcePathType]
      : Datasource[F, G, ?, CompositeResult[H, QueryResult[H]], P] ~> Datasource[F, G, ?, EvalResult[H], P] =
    new (Datasource[F, G, ?, CompositeResult[H, QueryResult[H]], P] ~> Datasource[F, G, ?, EvalResult[H], P]) {
      def apply[A](ds: Datasource[F, G, A, CompositeResult[H, QueryResult[H]], P]) = {
        val l = Datasource.ploaders[F, G, A, CompositeResult[H, QueryResult[H]], A, EvalResult[H], P]
        l.modify(_.map(_.map(reifyAggregateStructure)))(ds)
      }
    }

  private def reifyAggregateStructure[F[_], A](s: Stream[F, (ResourcePath, A)])
      : Stream[F, (ResourcePath, QSMap[Fix, A])] =
    s map { case (rp, a) =>
      (rp, QSMap(a, rec.Hole))
    }
}

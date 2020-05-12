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

package quasar.api.scheduler

import slamdata.Predef._

import quasar.Condition

import SchedulerError._

trait Schedulers[F[_], I, II, C, CC] {
  type Module
  type ModuleType

  def addScheduler(ref: SchedulerRef[C]): F[Either[CreateError[C], I]]
  def schedulerRef(i: I): F[Either[SchedulerNotFound[I], SchedulerRef[C]]]
  def removeScheduler(i: I): F[Condition[SchedulerError[I, C]]]
  def replaceScheduler(i: I, ref: SchedulerRef[C]): F[Condition[SchedulerError[I, C]]]
  def supportedTypes: F[Set[SchedulerType]]

  def enableModule(m: Module): F[Unit]
  def disableModule(ty: ModuleType): F[Unit]
}

object Schedulers {
  type Aux[F[_], I, II, C, CC, M, MT] = Schedulers[F, I, II, C, CC] {
    type Module = M
    type ModuleType = MT
  }
}